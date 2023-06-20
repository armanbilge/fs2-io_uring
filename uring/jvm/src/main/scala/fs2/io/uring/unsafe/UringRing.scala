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

import io.netty.incubator.channel.uring.NativeAccess._
import io.netty.incubator.channel.uring.UringCompletionQueue
import io.netty.incubator.channel.uring.UringSubmissionQueue

/**
  * The UringRing class represents a complete io_uring ring with both submission and completion queues.
  * It provides methods to interact with the submission and completion queues, such as submitting operations,
  * accessing the file descriptor of the ring, and closing the ring.
  * 
  * @param ringBuffer The RingBuffer associated with the io_uring ring.
  */
class UringRing(private val ringBuffer: RingBuffer) {
  // The completion queue associated with the ring.
  private[this] val uringCompletionQueue: UringCompletionQueue = UringCompletionQueue(ringBuffer)

  // The submission queue associated with the ring.
  private[this] val uringSubmissionQueue: UringSubmissionQueue = UringSubmissionQueue(ringBuffer)

  /**
    * Constructs a new UringRing instance with the default ring buffer size.
    */
  def this() = this(createRingBuffer())

  /**
    * Constructs a new UringRing instance with the specified ring buffer size.
    *
    * @param size of the ring buffer.
    */
  def this(size: Int) = this(createRingBuffer(size))


  /**
    * Constructs a new UringRing instance with the specified ring buffer size and 
    * SQE (Submission Queue Entry) async threshold.
    *
    * @param size of the ring buffer.
    * @param sqeAsyncThreshold The threshold value for determining whether an 
    * SQE should be submitted asynchronously.
    */
  def this(size: Int, sqeAsyncThreshold: Int) =
    this(createRingBuffer(size, sqeAsyncThreshold))

  /**
    * @return the UringCompletionQueue associated with the ring.
    */
  def ioUringCompletionQueue(): UringCompletionQueue = uringCompletionQueue

  /**
    * @return the UringSubmissionQueue associated with the ring.
    */
  def ioUringSubmissionQueue(): UringSubmissionQueue = uringSubmissionQueue

  /**
    * Submits pending operations in the queue to the kernel for processing.
    *
    * @return The number of operations successfully submitted.
    */
  def submit(): Int = uringSubmissionQueue.submit()

  /**
    * @return The file descriptor of the ring buffer.
    */
  def fd(): Int = ringBuffer.fd()

  /**
    * Closes the ring, realising any associated resources.
    */
  def close(): Unit = ringBuffer.close()
}

object UringRing {
  /**
    * Creates a new UringRing instance with the default ring buffer size.
    *
    * @return a new UringRing instance.
    */
  def apply(): UringRing = new UringRing()

  /**
    * Creates a new UringRing instance with the specified ring buffer size.
    *
    * @param size of the ring buffer.
    * @return a new UringRing instance.
    */
  def apply(size: Int): UringRing = new UringRing(size)

  /**
    * Creates a new UringRing instance with the specified ring buffer size 
    * and SQE (Submission Queue Entry) async threshold.
    *
    * @param size of the ring buffer.
    * @param sqeAsyncThreshold The threshold value for determining whether an SQE should be
    * submitted asynchronously.
    * @return a new UringRing instance. 
    */
  def apply(size: Int, sqeAsyncThreshold: Int): UringRing = new UringRing(size, sqeAsyncThreshold)

}
