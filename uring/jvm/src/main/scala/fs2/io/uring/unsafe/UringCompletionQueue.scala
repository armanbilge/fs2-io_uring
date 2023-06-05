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

class UringCompletionQueue(ring: RingBuffer) {

  private val completionQueue: IOUringCompletionQueue = ring.ioUringCompletionQueue()

  def hashCompletitions(): Boolean = completionQueue.hasCompletions()

  def processs(cb: IOUringCompletionQueueCallback): Int = completionQueue.process(cb)

  def ioUringWaitCqe(): Unit = completionQueue.ioUringWaitCqe()

  def ringAddress(): Long = completionQueue.ringAddress

  def ringFd(): Int = completionQueue.ringFd

  def ringSize(): Int = completionQueue.ringSize
}
