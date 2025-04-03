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
import cats.effect.std.Mutex
import cats.effect.unsafe.{PollingSystem, PollingContext, PollResult}
import cats.effect.unsafe.metrics.PollerMetrics
import cats.syntax.all._

import java.util.Collections
import java.util.IdentityHashMap
import java.util.Set
import scala.scalanative.posix.errno._
import scala.scalanative.posix.pollEvents._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import uring._
import uringOps._

object UringSystem extends PollingSystem {

  private final val MaxEvents = 64

  type Api = Uring with FileDescriptorPoller

  def close(): Unit = ()

  def makeApi(ctx: PollingContext[Poller]): Api = new ApiImpl(ctx)

  def makePoller(): Poller = {
    val ring = util.malloc[io_uring]()

    val flags = IORING_SETUP_SUBMIT_ALL |
      IORING_SETUP_COOP_TASKRUN |
      IORING_SETUP_TASKRUN_FLAG |
      IORING_SETUP_SINGLE_ISSUER |
      IORING_SETUP_DEFER_TASKRUN

    // the submission queue size need not exceed 64
    // every submission is accompanied by async suspension,
    // and at most 64 suspensions can happen per iterationxs
    val e = io_uring_queue_init(MaxEvents.toUInt, ring, flags.toUInt)
    if (e < 0) throw IOExceptionHelper(e)

    new Poller(ring)
  }

  def closePoller(poller: Poller): Unit = poller.close()

  def poll(poller: Poller, nanos: Long): PollResult = poller.poll(nanos)

  def processReadyEvents(poller: Poller): Boolean = {
    val cqes = stackalloc[Ptr[io_uring_cqe]](MaxEvents.toLong)
    poller.processReadyEvents(cqes)
  }

  def needsPoll(poller: Poller): Boolean = poller.needsPoll()

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ()

  def metrics(poller: Poller): PollerMetrics = poller.metrics()

  private final class ApiImpl private[UringSystem] (ctx: PollingContext[Poller])
      extends Uring
      with FileDescriptorPoller {
    private[this] val noopRelease: Int => IO[Unit] = _ => IO.unit

    def call(prep: Ptr[io_uring_sqe] => Unit, mask: Int => Boolean): IO[Int] =
      exec(prep, mask)(noopRelease)

    def bracket(prep: Ptr[io_uring_sqe] => Unit, mask: Int => Boolean)(
        release: Int => IO[Unit]
    ): Resource[IO, Int] =
      Resource.makeFull[IO, Int](poll => poll(exec(prep, mask)(release)))(release(_))

    private def exec(prep: Ptr[io_uring_sqe] => Unit, mask: Int => Boolean)(
        release: Int => IO[Unit]
    ): IO[Int] =
      IO.cont {
        new Cont[IO, Int, Int] {
          def apply[F[_]](implicit
              F: MonadCancelThrow[F]
          ): (Either[Throwable, Int] => Unit, F[Int], IO ~> F) => F[Int] = { (resume, get, lift) =>
            F.uncancelable { poll =>
              val submit = IO.async_[ULong] { cb =>
                ctx.accessPoller { ring =>
                  val sqe = ring.getSqe(resume)
                  prep(sqe)
                  cb(Right(sqe.user_data))
                }
              }

              lift(submit)
                .flatMap { addr =>
                  F.onCancel(
                    poll(get),
                    lift(cancel(addr)).ifM(
                      F.unit,
                      // if cannot cancel, fallback to get
                      get.flatMap { rtn =>
                        if (rtn < 0 && !mask(-rtn)) F.raiseError(IOExceptionHelper(-rtn))
                        else lift(release(rtn))
                      }
                    )
                  )
                }
                .flatTap(e => F.raiseWhen(e < 0 && !mask(-e))(IOExceptionHelper(-e)))
            }
          }
        }
      }

    private[this] def cancel(addr: __u64): IO[Boolean] =
      IO.async_[Int] { cb =>
        ctx.accessPoller { ring =>
          val sqe = ring.getSqe(cb)
          io_uring_prep_cancel64(sqe, addr, 0)
        }
      }.map(_ == 0) // true if we actually canceled

    def registerFileDescriptor(
        fd: Int,
        reads: Boolean,
        writes: Boolean
    ): Resource[IO, FileDescriptorPollHandle] =
      Resource.eval {
        (Mutex[IO], Mutex[IO]).mapN { (readMutex, writeMutex) =>
          new FileDescriptorPollHandle {

            def pollReadRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
              readMutex.lock.surround {
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
              writeMutex.lock.surround {
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

  final class Poller private[UringSystem] (ring: Ptr[io_uring]) {

    private[this] var pendingSubmissions: Boolean = false
    private[this] val callbacks: Set[Either[Throwable, Int] => Unit] =
      Collections.newSetFromMap(new IdentityHashMap)

    private[this] val pollerMetrics = new PollerMetrics {
      override def operationsOutstandingCount(): Int = 0

      override def totalOperationsSubmittedCount(): Long = 0

      override def totalOperationsSucceededCount(): Long = 0

      override def totalOperationsErroredCount(): Long = 0

      override def totalOperationsCanceledCount(): Long = 0

      override def acceptOperationsOutstandingCount(): Int = 0

      override def totalAcceptOperationsSubmittedCount(): Long = 0L

      override def totalAcceptOperationsSucceededCount(): Long = 0L

      override def totalAcceptOperationsErroredCount(): Long = 0L

      override def totalAcceptOperationsCanceledCount(): Long = 0L

      override def connectOperationsOutstandingCount(): Int = 0

      override def totalConnectOperationsSubmittedCount(): Long = 0L

      override def totalConnectOperationsSucceededCount(): Long = 0L

      override def totalConnectOperationsErroredCount(): Long = 0L

      override def totalConnectOperationsCanceledCount(): Long = 0L

      override def readOperationsOutstandingCount(): Int = 0

      override def totalReadOperationsSubmittedCount(): Long = 0

      override def totalReadOperationsSucceededCount(): Long = 0

      override def totalReadOperationsErroredCount(): Long = 0

      override def totalReadOperationsCanceledCount(): Long = 0

      override def writeOperationsOutstandingCount(): Int = 0

      override def totalWriteOperationsSubmittedCount(): Long = 0

      override def totalWriteOperationsSucceededCount(): Long = 0

      override def totalWriteOperationsErroredCount(): Long = 0

      override def totalWriteOperationsCanceledCount(): Long = 0
    }

    private[UringSystem] def metrics(): PollerMetrics = metrics

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

    private[UringSystem] def needsPoll(): Boolean =
      pendingSubmissions || !callbacks.isEmpty()

    private[UringSystem] def poll(nanos: Long): PollResult = {

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
            ts.tv_sec = nanos / 1_000_000_000
            ts.tv_nsec = nanos % 1_000_000_000
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
      val invokedCbs = processReadyEvents(cqes)

      if (pendingSubmissions && rtn == -EBUSY) {
        rtn = io_uring_submit(ring)
        while (rtn == -EBUSY) {
          processReadyEvents(cqes)
          rtn = io_uring_submit(ring)
        }
      }

      pendingSubmissions = false

      if (invokedCbs) {
        if (rtn < MaxEvents) PollResult.Complete else PollResult.Incomplete
      } else PollResult.Interrupted

    }

    private[UringSystem] def processReadyEvents(cqes: Ptr[Ptr[io_uring_cqe]]): Boolean =
      processCqes(cqes)

    private[this] def processCqes(_cqes: Ptr[Ptr[io_uring_cqe]]): Boolean = {
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
      filledCount > 0
    }
  }

}
