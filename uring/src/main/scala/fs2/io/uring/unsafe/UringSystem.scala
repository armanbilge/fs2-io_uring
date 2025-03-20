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
import cats.effect.unsafe.PollingSystem
import cats.effect.unsafe.PollingContext
import cats.effect.unsafe.PollResult
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

  override def close(): Unit = ???

  override def makeApi(ctx: PollingContext[Poller]): Api = ???

  override def makePoller(): Poller = ???

  override def closePoller(poller: Poller): Unit = ???

  override def poll(poller: Poller, nanos: Long): PollResult = ???

  override def processReadyEvents(poller: Poller): Boolean = ???

  override def needsPoll(poller: Poller): Boolean = ???

  override def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ???

  override def metrics(poller: Poller): PollerMetrics = ???

  final class Poller private[UringSystem] (ring: Ptr[io_uring]) {

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

    private[UringSystem] def needsPoll(): Boolean =
      pendingSubmissions || !callbacks.isEmpty()

    private[UringSystem] def poll(nanos: Long): Boolean = {

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
      val invokedCbs = processCqes(cqes)

      if (pendingSubmissions && rtn == -EBUSY) {
        // submission failed, so try again
        rtn = io_uring_submit(ring)
        while (rtn == -EBUSY) {
          processCqes(cqes)
          rtn = io_uring_submit(ring)
        }
      }

      pendingSubmissions = false
      invokedCbs
    }

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
