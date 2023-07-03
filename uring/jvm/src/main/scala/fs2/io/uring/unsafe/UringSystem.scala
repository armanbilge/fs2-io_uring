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
import io.netty.incubator.channel.uring.UringCompletionQueue
import io.netty.incubator.channel.uring.UringCompletionQueueCallback

import java.io.IOException

object UringSystem extends PollingSystem {

  private final val MaxEvents = 64

  type Api = Uring

  override def makeApi(register: (Poller => Unit) => Unit): Api = new ApiImpl(register)

  override def makePoller(): Poller = {
    val ring = UringRing()

    new Poller(ring)
  }

  override def closePoller(poller: Poller): Unit = poller.close()

  override def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean =
    poller.poll(nanos)

  override def needsPoll(poller: Poller): Boolean = poller.needsPoll()

  override def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ()

  private final class ApiImpl(register: (Poller => Unit) => Unit) extends Uring {
    private[this] val noopRelease: Long => IO[Unit] = _ => IO.unit

    def call(prep: UringSubmissionQueue => Unit): IO[Long] =
      exec(prep)(noopRelease)

    def bracket(prep: UringSubmissionQueue => Unit)(release: Long => IO[Unit]): Resource[IO, Long] =
      Resource.makeFull[IO, Long](poll => poll(exec(prep)(release(_))))(release(_))

    private def exec(prep: UringSubmissionQueue => Unit)(release: Long => IO[Unit]): IO[Long] =
      IO.cont {
        new Cont[IO, Long, Long] {
          def apply[F[_]](implicit
              F: MonadCancelThrow[F]
          ): (Either[Throwable, Long] => Unit, F[Long], IO ~> F) => F[Long] = {
            (resume, get, lift) =>
              F.uncancelable { poll =>
                val submit = IO.async_[Long] { cb =>
                  register { ring =>
                    val sqe = ring.getSqe(resume)
                    prep(sqe)
                    val userData: Long = sqe.encode(0, 0, sqe.getData())
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
                          if (rtn < 0) F.raiseError(IOExceptionHelper(-rtn.toInt))
                          else lift(release(rtn))
                        }
                      )
                    )
                  }
                  .flatTap(e => F.raiseWhen(e < 0)(IOExceptionHelper(-e.toInt)))
              }
          }
        }
      }

    private[this] def cancel(addr: Long): IO[Boolean] =
      IO.async_[Long] { cb =>
        register { ring =>
          val sqe = ring.getSqe(cb)
          val wasCancelled: Boolean = sqe.prepCancel(addr, 0)
          cb(Right(if (wasCancelled) 1 else 0))
        }
      }.map(_ == 1)

  }

  final class Poller private[UringSystem] (ring: UringRing) {

    private[this] var pendingSubmissions: Boolean = false
    private[this] val sqe: UringSubmissionQueue = ring.ioUringSubmissionQueue()
    private[this] val cqe: UringCompletionQueue = ring.ioUringCompletionQueue()

    private[UringSystem] def getSqe(cb: Either[Throwable, Long] => Unit): UringSubmissionQueue = {
      pendingSubmissions = true
      sqe.setData(cb)
      sqe
    }

    private[UringSystem] def close(): Unit = ring.close()

    private[UringSystem] def needsPoll(): Boolean = pendingSubmissions || !sqe.callbacksIsEmpty()

    private[UringSystem] def process(
        completionQueueCallback: UringCompletionQueueCallback
    ): Boolean =
      cqe.process(completionQueueCallback) > 0 // True if any completion events were processed

    private[UringSystem] def poll(nanos: Long): Boolean = {
      if (pendingSubmissions) {
        ring.submit()
        pendingSubmissions = false
      }

      val completionQueueCallback = new UringCompletionQueueCallback {
        override def handle(fd: Int, res: Int, flags: Int, op: Byte, data: Short): Unit = {
          val removedCallback = sqe.removeCallback(data)

          removedCallback.foreach { cb =>
            if (res < 0) cb(Left(new IOException("Error in completion queue entry")))
            else cb(Right(res.toLong))
          }
        }
      }

      if (cqe.hasCompletions()) {
        process(completionQueueCallback)
      } else if (nanos > 0) {
        // TODO sqe.addTimeout() and then:
        cqe.ioUringWaitCqe()

        // Check again if there are completions after waiting
        process(completionQueueCallback)
      } else {
        // No completions and no timeout specified
        false
      }
    }

  }

}
