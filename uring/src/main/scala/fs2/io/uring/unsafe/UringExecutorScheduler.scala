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

package fs2.io.uring.unsafe

import cats.effect.unsafe.PollingExecutorScheduler

import java.util.Collections
import java.util.IdentityHashMap
import java.util.Set
import scala.concurrent.duration._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import uring._
import uringOps._

private[uring] final class UringExecutorScheduler(
    ring: Ptr[io_uring],
    pollEvery: Int,
    maxEvents: Int
) extends PollingExecutorScheduler(pollEvery) {

  private[this] var pendingSubmissions: Boolean = false
  private[this] val callbacks: Set[Either[Throwable, Int] => Unit] =
    Collections.newSetFromMap(new IdentityHashMap)

  def poll(timeout: Duration): Boolean = {

    val timeoutIsZero = timeout == Duration.Zero
    val timeoutIsInfinite = timeout == Duration.Inf
    val noCallbacks = callbacks.isEmpty()

    if ((timeoutIsInfinite || timeoutIsZero) && noCallbacks)
      false // nothing to do here. refer to scaladoc on PollingExecutorScheduler#poll
    else {

      if (timeoutIsZero) {
        if (pendingSubmissions) io_uring_submit(ring)
      } else {

        val timeoutSpec =
          if (timeoutIsInfinite) {
            if (pendingSubmissions) io_uring_submit(ring)
            null
          } else {
            val ts = stackalloc[__kernel_timespec]()
            val sec = timeout.toSeconds
            ts.tv_sec = sec
            ts.tv_nsec = (timeout - sec.seconds).toNanos
            ts
          }

        val cqe = stackalloc[Ptr[io_uring_cqe]]()
        io_uring_wait_cqe_timeout(ring, cqe, timeoutSpec)
      }

      var cqes = stackalloc[Ptr[io_uring_cqe]](maxEvents.toLong)
      val filledCount = io_uring_peek_batch_cqe(ring, cqes, maxEvents.toUInt).toInt

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

      pendingSubmissions = false

      !callbacks.isEmpty()
    }
  }

}

private[uring] object UringExecutorScheduler {

  def apply(pollEvery: Int, maxEvents: Int): (UringExecutorScheduler, () => Unit) = {
    implicit val zone = Zone.open()
    val ring = alloc[io_uring]()

    // the submission queue size need not exceed pollEvery
    // every submission is accompanied by async suspension,
    // and at most pollEvery suspensions can happen per iteration
    io_uring_queue_init(pollEvery.toUInt, ring, 0.toUInt)

    val cleanup = () => {
      io_uring_queue_exit(ring)
      zone.close()
    }

    (new UringExecutorScheduler(ring, pollEvery, maxEvents), cleanup)
  }

}
