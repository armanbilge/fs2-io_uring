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
import scala.scalanative.posix.errno._
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

  def getSqe(cb: Either[Throwable, Int] => Unit): Ptr[io_uring_sqe] = {
    pendingSubmissions = true
    val sqe = io_uring_get_sqe(ring)
    io_uring_sqe_set_data(sqe, cb)
    callbacks.add(cb)
    sqe
  }

  def poll(timeout: Duration): Boolean = {

    val timeoutIsZero = timeout == Duration.Zero
    val timeoutIsInfinite = timeout == Duration.Inf

    if ((timeoutIsInfinite || timeoutIsZero) && callbacks.isEmpty())
      false // nothing to do here. refer to scaladoc on PollingExecutorScheduler#poll
    else {

      var rtn = if (timeoutIsZero) {
        if (pendingSubmissions)
          io_uring_submit(ring)
        else 0
      } else {

        val timeoutSpec =
          if (timeoutIsInfinite) {
            null
          } else {
            val ts = stackalloc[__kernel_timespec]()
            val sec = timeout.toSeconds
            ts.tv_sec = sec
            ts.tv_nsec = (timeout - sec.seconds).toNanos
            ts
          }

        val cqe = stackalloc[Ptr[io_uring_cqe]]()
        if (pendingSubmissions) {
          io_uring_submit_and_wait_timeout(ring, cqe, 0.toUInt, timeoutSpec, null)
        } else {
          io_uring_wait_cqe_timeout(ring, cqe, timeoutSpec)
        }
      }

      val cqes = stackalloc[Ptr[io_uring_cqe]](maxEvents.toLong)
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
  }

  private[this] def processCqes(_cqes: Ptr[Ptr[io_uring_cqe]]): Unit = {
    var cqes = _cqes

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
  }

}

private[uring] object UringExecutorScheduler {

  def apply(pollEvery: Int, maxEvents: Int): (UringExecutorScheduler, () => Unit) = {
    val alloc = new Array[Byte](sizeof[io_uring].toInt)
    @inline def ring = alloc.at(0).asInstanceOf[Ptr[io_uring]]

    val flags = IORING_SETUP_SUBMIT_ALL

    // the submission queue size need not exceed pollEvery
    // every submission is accompanied by async suspension,
    // and at most pollEvery suspensions can happen per iteration
    io_uring_queue_init(pollEvery.toUInt, ring, flags.toUInt)

    val cleanup = () => io_uring_queue_exit(ring)

    (new UringExecutorScheduler(ring, pollEvery, maxEvents), cleanup)
  }

}
