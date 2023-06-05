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

import cats.effect.unsafe.PollingSystem
import io.netty.incubator.channel.uring.UringRing
import io.netty.incubator.channel.uring.UringSubmissionQueue

import java.util.Collections
import java.util.IdentityHashMap
import java.util.Set


object UringSystem extends PollingSystem {

  override def makeApi(register: (Poller => Unit) => Unit): Api = ???

  override def makePoller(): Poller = ???

  override def closePoller(poller: Poller): Unit = ???

  override def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean = ???

  override def needsPoll(poller: Poller): Boolean = ???

  override def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ???

  final class Poller private[UringSystem] (ring: UringRing) {

    private[this] var pendingSubmissions: Boolean = false
    private[this] val callbacks: Set[Either[Throwable, Int] => Unit] =
      Collections.newSetFromMap(new IdentityHashMap)

    private[UringSystem] def getSqe(cb: Either[Throwable, Int] => Unit): UringSubmissionQueue = {
      pendingSubmissions = true
      val sqe = ring.ioUringSubmissionQueue()
      // TODO: We modify the "data" from the sqe
      callbacks.add(cb)
      sqe
    }

    private[UringSystem] def close(): Unit = ring.close()

    private[UringSystem] def needsPoll(): Boolean = pendingSubmissions || !callbacks.isEmpty()

    private[UringSystem] def poll(nanos: Long): Boolean = ???

    private[this] def processCqes(_cqes: List[UringSubmissionQueue]): Boolean = ???

  }

}
