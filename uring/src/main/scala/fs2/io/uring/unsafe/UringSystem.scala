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
import cats.effect.FileDescriptorPoller
import cats.effect.FileDescriptorPollHandle
import cats.effect.IO
import cats.effect.kernel.Cont
import cats.effect.kernel.MonadCancelThrow
import cats.effect.kernel.Resource
import cats.effect.std.Semaphore
import cats.effect.unsafe.PollingSystem
import cats.syntax.all._

import java.util.Collections
import java.util.IdentityHashMap
import java.util.Set
import scala.concurrent.ExecutionContext
import scala.scalanative.posix.errno._
import scala.scalanative.posix.pollEvents._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import uring._
import uringOps._

object UringSystem extends PollingSystem {

  private final val MaxEvents = 64

  def makePoller(ec: ExecutionContext, data: () => PollData): Poller =
    new Poller(ec, data)

  def makePollData(): PollData = {
    val ring = util.malloc[io_uring]()

    // the submission queue size need not exceed 64
    // every submission is accompanied by async suspension,
    // and at most 64 suspensions can happen per iteration
    val e = io_uring_queue_init(64.toUInt, ring, 0.toUInt)
    if (e < 0) throw IOExceptionHelper(-e)

    new PollData(ring)
  }

  def closePollData(data: PollData): Unit =
    data.close()

  def poll(data: PollData, nanos: Long, reportFailure: Throwable => Unit): Boolean =
    data.poll(nanos)

  final class Poller private[UringSystem] (ec: ExecutionContext, data: () => PollData)
      extends Uring
      with FileDescriptorPoller {
    private[this] val noopRelease: Int => IO[Unit] = _ => IO.unit

    def call(prep: Ptr[io_uring_sqe] => Unit): IO[Int] =
      exec(prep)(noopRelease)

    def bracket(prep: Ptr[io_uring_sqe] => Unit)(release: Int => IO[Unit]): Resource[IO, Int] =
      Resource.makeFull[IO, Int](poll => poll(exec(prep)(release(_))))(release(_))

    private def exec(prep: Ptr[io_uring_sqe] => Unit)(release: Int => IO[Unit]): IO[Int] =
      IO.cont {
        new Cont[IO, Int, Int] {
          def apply[F[_]](implicit
              F: MonadCancelThrow[F]
          ): (Either[Throwable, Int] => Unit, F[Int], IO ~> F) => F[Int] = { (resume, get, lift) =>
            F.uncancelable { poll =>
              val submit = IO {
                val sqe = data().getSqe(resume)
                prep(sqe)
                sqe.user_data
              }

              lift(submit)
                .flatMap { addr =>
                  F.onCancel(
                    poll(get),
                    lift(cancel(addr)).ifM(
                      F.unit,
                      // if cannot cancel, fallback to get
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
      }.evalOn(ec)

    private[this] def cancel(addr: __u64): IO[Boolean] =
      IO.async_[Int] { cb =>
        val sqe = data().getSqe(cb)
        io_uring_prep_cancel64(sqe, addr, 0)
      }.map(_ == 0) // true if we actually canceled

    def registerFileDescriptor(
        fd: Int,
        reads: Boolean,
        writes: Boolean
    ): Resource[IO, FileDescriptorPollHandle] =
      Resource.eval {
        (Semaphore[IO](1), Semaphore[IO](1)).mapN { (readSemaphore, writeSemaphore) =>
          new FileDescriptorPollHandle {

            def pollReadRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
              readSemaphore.permit.surround {
                a.tailRecM { a =>
                  f(a).flatTap { r =>
                    if (r.isRight)
                      IO.unit
                    else
                      call(io_uring_prep_poll_add(_, fd, POLLIN.toUInt))
                  }
                }
              }

            def pollWriteRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
              writeSemaphore.permit.surround {
                a.tailRecM { a =>
                  f(a).flatTap { r =>
                    if (r.isRight)
                      IO.unit
                    else
                      call(io_uring_prep_poll_add(_, fd, POLLOUT.toUInt))
                  }
                }
              }
          }

        }
      }
  }

  final class PollData private[UringSystem] (ring: Ptr[io_uring]) {

    private[this] var pendingSubmissions: Boolean = false
    private[this] val callbacks: Set[Either[Throwable, Int] => Unit] =
      Collections.newSetFromMap(new IdentityHashMap)

    private[UringSystem] def getSqe(cb: Either[Throwable, Int] => Unit): Ptr[io_uring_sqe] = {
      pendingSubmissions = true
      val sqe = io_uring_get_sqe(ring)
      io_uring_sqe_set_data(sqe, cb)
      callbacks.add(cb)
      sqe
    }

    private[UringSystem] def close(): Unit = {
      io_uring_queue_exit(ring)
      util.free(ring)
    }

    private[UringSystem] def poll(nanos: Long): Boolean =
      if (nanos <= 0 && callbacks.isEmpty())
        false
      else {

        var rtn = if (nanos == 0) {
          if (pendingSubmissions)
            io_uring_submit(ring)
          else 0
        } else {

          val timeoutSpec =
            if (nanos == -1) {
              null
            } else {
              val ts = stackalloc[__kernel_timespec]()
              ts.tv_sec = nanos / 1000000000
              ts.tv_nsec = nanos % 1000000000
              ts
            }

          val cqe = stackalloc[Ptr[io_uring_cqe]]()
          if (pendingSubmissions) {
            io_uring_submit_and_wait_timeout(ring, cqe, 0.toUInt, timeoutSpec, null)
          } else {
            io_uring_wait_cqe_timeout(ring, cqe, timeoutSpec)
          }
        }

        val cqes = stackalloc[Ptr[io_uring_cqe]](MaxEvents.toLong)
        processCqes(cqes)

        if (pendingSubmissions && rtn == -EBUSY) {
          // submission failed, so try again
          rtn = io_uring_submit(ring)
          while (rtn == -EBUSY) {
            processCqes(cqes)
            rtn = io_uring_submit(ring)
          }
        }

        pendingSubmissions = false

        !callbacks.isEmpty()
      }

    private[this] def processCqes(_cqes: Ptr[Ptr[io_uring_cqe]]): Unit = {
      var cqes = _cqes

      val filledCount = io_uring_peek_batch_cqe(ring, cqes, MaxEvents.toUInt).toInt

      var i = 0
      while (i < filledCount) {
        val cqe = !cqes

        val cb = io_uring_cqe_get_data[Either[Exception, Int] => Unit](cqe)
        cb(Right(cqe.res))
        callbacks.remove(cb)

        i += 1
        cqes += 1
      }

      io_uring_cq_advance(ring, filledCount.toUInt)
    }

  }

}