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

import java.util.Collections
import java.util.IdentityHashMap
import java.util.Set

object UringSystem extends PollingSystem {

  private final val MaxEvents = 64

  type Api = Uring

  type Address = Long

  override def makeApi(register: (Poller => Unit) => Unit): Api = new ApiImpl(register)

  override def makePoller(): Poller = {
    val ring = UringRing()
    // TODO: review potential errors and handle them
    new Poller(ring)
  }

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
          ): (Either[Throwable, Int] => Unit, F[Int], IO ~> F) => F[Int] = { (resume, get, lift) =>
            F.uncancelable { poll =>
              val submit = IO.async_[Long] { cb => // TODO: We need Unsigned Long here
                register { ring =>
                  val sqe = ring.getSqe(resume)
                  prep(sqe)
                  cb(Right(sqe.userData()))
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
      IO.async_[Int] { cb =>
        register { ring =>
          val sqe = ring.getSqe(cb)
          sqe.prepCancel(addr, 0)
        }
      }.map(_ == 0) // true if we canceled
  }

  final class Poller private[UringSystem] (ring: UringRing) {

    private[this] var pendingSubmissions: Boolean = false
    private[this] val callbacks: Set[Either[Throwable, Int] => Unit] =
      Collections.newSetFromMap(new IdentityHashMap)

    private[UringSystem] def getSqe(cb: Either[Throwable, Int] => Unit): UringSubmissionQueue = {
      pendingSubmissions = true
      val sqe = ring.ioUringSubmissionQueue()
      sqe.setData(cb)
      callbacks.add(cb)
      sqe
    }

    private[UringSystem] def close(): Unit = ring.close()

    private[UringSystem] def needsPoll(): Boolean = pendingSubmissions || !callbacks.isEmpty()

    private[UringSystem] def poll(nanos: Long): Boolean = ???

  }

}
