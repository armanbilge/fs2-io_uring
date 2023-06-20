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

package io.netty.incubator.channel.uring

/**
  * The UringCompletionQueue class represents a completion queue for the io_uring subsystem in the Netty library.
  * It provides methods to interact with the completion queue, such as checking for completions, processing completions,
  * waiting for completions, and accessing the underlying ring buffer.
  * 
  * @param ring The RingBuffer associated with the completion queue.
  */
class UringCompletionQueue(private val ring: RingBuffer) {

  // The IOUringCompletionQueue instance associated with the ring.
  private val completionQueue: IOUringCompletionQueue = ring.ioUringCompletionQueue()

  def hasCompletions(): Boolean = completionQueue.hasCompletions()

  def process(cb: IOUringCompletionQueueCallback): Int = completionQueue.process(cb)

  def ioUringWaitCqe(): Unit = completionQueue.ioUringWaitCqe()

  def ringAddress(): Long = completionQueue.ringAddress

  def ringFd(): Int = completionQueue.ringFd

  def ringSize(): Int = completionQueue.ringSize
}

object UringCompletionQueue {
  /**
    * Creates a new UringCompletionQueue instance associated with the specified RingBuffer.
    *
    * @param ring The RingBuffer associated with the completion queue.
    * @return A new UringCompletionQueue instance.
    */
  def apply(ring: RingBuffer): UringCompletionQueue = new UringCompletionQueue(ring)
}
