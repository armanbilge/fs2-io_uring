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

class UringSubmissionQueue(private val ring: RingBuffer) {
  private val submissionQueue: IOUringSubmissionQueue = ring.ioUringSubmissionQueue()

  def enqueueSqe(
      op: Byte,
      flags: Int,
      rwFlags: Int,
      fd: Int,
      bufferAddress: Long,
      length: Int,
      offset: Long,
      data: Short
  ): Boolean =
    submissionQueue.enqueueSqe(op, flags, rwFlags, fd, bufferAddress, length, offset, data)

  def incrementHandledFds(): Unit = submissionQueue.incrementHandledFds()

  def decrementHandledFds(): Unit = submissionQueue.decrementHandledFds()

  def addTimeout(nanoSeconds: Long, extraData: Short): Boolean =
    submissionQueue.addTimeout(nanoSeconds, extraData)

  def addPollIn(fd: Int): Boolean = submissionQueue.addPollIn(fd)

  def addPollRdHup(fd: Int): Boolean = submissionQueue.addPollRdHup(fd)

  def addPollOut(fd: Int): Boolean = submissionQueue.addPollOut(fd)

  def addRecvmsg(fd: Int, msgHdr: Long, extraData: Short): Boolean =
    submissionQueue.addRecvmsg(fd, msgHdr, extraData)

  def addSendmsg(fd: Int, msgHdr: Long, extraData: Short): Boolean =
    submissionQueue.addSendmsg(fd, msgHdr, extraData)

  def addSendmsg(fd: Int, msgHdr: Long, flags: Int, extraData: Short): Boolean =
    submissionQueue.addSendmsg(fd, msgHdr, flags, extraData)

  def addRead(fd: Int, bufferAddress: Long, pos: Int, limit: Int, extraData: Short): Boolean =
    submissionQueue.addRead(fd, bufferAddress, pos, limit, extraData)

  def addEventFdRead(
      fd: Int,
      bufferAddress: Long,
      pos: Int,
      limit: Int,
      extraData: Short
  ): Boolean = submissionQueue.addEventFdRead(fd, bufferAddress, pos, limit, extraData)

  def addWrite(fd: Int, bufferAddress: Long, pos: Int, limit: Int, extraData: Short): Boolean =
    submissionQueue.addWrite(fd, bufferAddress, pos, limit, extraData)

  def addRecv(fd: Int, bufferAddress: Long, pos: Int, limit: Int, extraData: Short): Boolean =
    submissionQueue.addRecv(fd, bufferAddress, pos, limit, extraData)

  def addSend(fd: Int, bufferAddress: Long, pos: Int, limit: Int, extraData: Short): Boolean =
    submissionQueue.addSend(fd, bufferAddress, pos, limit, extraData)

  def addAccept(fd: Int, address: Long, addressLength: Long, extraData: Short): Boolean =
    submissionQueue.addAccept(fd, address, addressLength, extraData)

  def addPollRemove(fd: Int, pollMask: Int): Boolean = submissionQueue.addPollRemove(fd, pollMask)

  def addConnect(
      fd: Int,
      socketAddress: Long,
      socketAddressLength: Long,
      extraData: Short
  ): Boolean = submissionQueue.addConnect(fd, socketAddress, socketAddressLength, extraData)

  def addWritev(fd: Int, iovecArrayAddress: Long, length: Int, extraData: Short): Boolean =
    submissionQueue.addWritev(fd, iovecArrayAddress, length, extraData)

  def addClose(fd: Int, extraData: Short): Boolean = submissionQueue.addClose(fd, extraData)

  def submit(): Int = submissionQueue.submit()

  def submitAndWait(): Int = submissionQueue.submitAndWait()

  def count(): Long = submissionQueue.count()

  def release(): Unit = submissionQueue.release()

  def setData[A <: AnyRef](data: A): Unit =
    // TODO: We need to set data in UringSystem.Poller.getSqe
    ???

  def userData(): Long =
    // TODO: We need to access the userData in UringSystem.ApiImpl.exec
    ???

  def prepCancel(userData: Long, flags: Int): Unit =
    // TODO: We need to cancel in UringSystem.ApiImpl.cancel
    ???
}

object UringSubmissionQueue {
  def apply(ring: RingBuffer): UringSubmissionQueue = new UringSubmissionQueue(ring)
}
