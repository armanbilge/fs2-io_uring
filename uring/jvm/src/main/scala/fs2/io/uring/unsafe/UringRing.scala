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

import io.netty.incubator.channel.uring.NativeAccess.createRingBuffer
import io.netty.incubator.channel.uring.UringCompletionQueue
import io.netty.incubator.channel.uring.UringSubmissionQueue

class UringRing {
  private val ringBuffer: RingBuffer = createRingBuffer()
  private val uringCompletionQueue: UringCompletionQueue = new UringCompletionQueue(ringBuffer)
  private val uringSubmissionQueue: UringSubmissionQueue = new UringSubmissionQueue(ringBuffer)

  def ioUringCompletionQueue(): UringCompletionQueue = uringCompletionQueue

  def ioUringSubmissionQueue(): UringSubmissionQueue = uringSubmissionQueue

  def fd(): Int = ringBuffer.fd()

  def close(): Unit = ringBuffer.close()
}
