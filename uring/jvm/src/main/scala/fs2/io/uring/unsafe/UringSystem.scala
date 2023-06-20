/*
 * Copyright 2022 Arman Bilge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2.io.uring
package unsafe

import cats.~>
import cats.syntax.all._

import cats.effect.IO

import cats.effect.kernel.Resource
import cats.effect.kernel.MonadCancelThrow
import cats.effect.kernel.Cont

import cats.effect.unsafe.PollingSystem

import io.netty.incubator.channel.uring.UringRing
import io.netty.incubator.channel.uring.UringSubmissionQueue
import io.netty.incubator.channel.uring.UringCompletionQueueCallback

import java.util.concurrent.ConcurrentHashMap
import java.io.IOException

object UringSystem extends PollingSystem {

  private final val MaxEvents = 64

  type Api = Uring

  type Address = Long

  override def makeApi(register: (Poller => Unit) => Unit): Api = new ApiImpl(register)

  override def makePoller(): Poller =
    new Poller(UringRing())

  override def closePoller(poller: Poller): Unit = poller.close()

  override def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean =
    poller.poll(nanos)

  override def needsPoll(poller: Poller): Boolean = poller.needsPoll()

  override def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ()

  private final class ApiImpl(register: (Poller => Unit) => Unit) extends Uring {
    private[this] val noopRelease: Int => IO[Unit] = _ => IO.unit

    def call(prep: UringSubmissionQueue => Unit): IO[Int] =
      exec(prep)(noopRelease)

    def bracket(prep: UringSubmissionQueue => Unit)(release: Int => IO[Unit]): Resource[IO, Int] =
      Resource.makeFull[IO, Int](poll => poll(exec(prep)(release(_))))(release(_))

    private def exec(prep: UringSubmissionQueue => Unit)(release: Int => IO[Unit]): IO[Int] =
      IO.cont {
        new Cont[IO, Int, Int] {
          def apply[F[_]](implicit
              F: MonadCancelThrow[F]
          ): (Either[Throwable, Long] => Unit, F[Int], IO ~> F) => F[Int] = { (resume, get, lift) =>
            F.uncancelable { poll =>
              val submit = IO.async_[Long] { cb =>
                register { ring =>
                  val sqe = ring.getSqe(resume)
                  prep(sqe)

                  sqe.setData(cb)

                  val userData = sqe.encode(0, 0, sqe.counter)

                  cb(Right(userData))
                }
              }

              lift(submit)
                .flatMap { addr =>
                  F.onCancel(
                    poll(get),
                    lift(cancel(addr)).ifM(
                      F.unit,
                      get.flatMap { rtn =>
                        if (rtn < 0) F.raiseError(IOExceptionHelper(-rtn))
                        else lift(release(rtn))
                      }
                    )
                  )
                }
                .flatTap(e => F.raiseWhen(e < 0)(IOExceptionHelper(-e)))
            }
          }
        }
      }

    private[this] def cancel(addr: Address): IO[Boolean] =
      IO.async_[Long] { cb =>
        register { ring =>
          val sqe = ring.getSqe(cb)
          val wasCancelled = sqe.prepCancel(addr, 0)
          cb(Right(if (wasCancelled) 1 else 0))
        }
      }.map(_ == 1)

  }

  final class Poller private[UringSystem] (ring: UringRing) {

    private[this] var pendingSubmissions: Boolean = false
    private[this] val callbacks: ConcurrentHashMap[Int, Either[Throwable, Long] => Unit] =
      new ConcurrentHashMap[Int, Either[Throwable, Long] => Unit]()

    private[this] val usedIds = new java.util.BitSet()

    private[UringSystem] def getSqe(cb: Either[Throwable, Long] => Unit): UringSubmissionQueue = {
      pendingSubmissions = true

      val id = usedIds.nextClearBit(0)
      usedIds.set(id)
      callbacks.put(id, cb)

      val sqe = ring.ioUringSubmissionQueue()
      sqe.setData(cb)

      sqe
    }

    private[UringSystem] def close(): Unit = ring.close()

    private[UringSystem] def needsPoll(): Boolean = pendingSubmissions || !callbacks.isEmpty()

    private[UringSystem] def poll(nanos: Long): Boolean = {
      if (pendingSubmissions) {
        ring.submit()
      }

      val sqe = ring.ioUringSubmissionQueue()
      val cqe = ring.ioUringCompletionQueue()

      val completionQueueCallback = new UringCompletionQueueCallback {
        override def handle(res: Int, flags: Int, data: Long): Unit = {
          println(s"Completion event flag: $flags")

          val callback = sqe.userData(data).asInstanceOf[Either[IOException, Long] => Unit]
          if (res < 0) {
            callback(Left(new IOException("Error in completion queue entry")))
          } else {
            callback(Right(res.toLong))
          }
        }
      }

      // Check if there are any completions ready to be processed
      if (cqe.hasCompletions()) {
        val processedCount = cqe.process(completionQueueCallback)
        // Return true if any completion events were processed
        processedCount > 0
      } else if (nanos > 0) {
        // If a timeout is specified, block until at least one completion is ready
        // or the timeout expires
        cqe.ioUringWaitCqe()

        // Check again if there are completions after waiting
        val processedCount = cqe.process(completionQueueCallback)
        // Return true if any completion events were processed
        processedCount > 0
      } else {
        // No completions and no timeout specified
        false
      }
    }

  }

}
