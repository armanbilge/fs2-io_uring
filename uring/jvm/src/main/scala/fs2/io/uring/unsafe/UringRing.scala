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

import io.netty.channel.unix.FileDescriptor
import NativeAccess._

/** The UringRing class represents a complete io_uring ring with both submission and completion queues.
  * It provides methods to interact with the submission and completion queues, such as submitting operations,
  * accessing the file descriptor of the ring, and closing the ring.
  *
  * @param ringBuffer The RingBuffer associated with the io_uring ring.
  */
final class UringRing(private val ringBuffer: RingBuffer) {
  // The completion queue associated with the ring.
  private[this] val uringCompletionQueue: UringCompletionQueue = UringCompletionQueue(ringBuffer)

  // The submission queue associated with the ring.
  private[this] val uringSubmissionQueue: UringSubmissionQueue = UringSubmissionQueue(ringBuffer)

  /** Constructs a new UringRing instance with the default ring buffer size.
    */
  def this() = this(createRingBuffer())

  /** Constructs a new UringRing instance with the specified ring buffer size.
    *
    * @param size of the ring buffer.
    */
  def this(size: Int) = this(createRingBuffer(size))

  /** Constructs a new UringRing instance with the specified ring buffer size and
    * SQE (Submission Queue Entry) async threshold.
    *
    * @param size of the ring buffer.
    * @param sqeAsyncThreshold The threshold value for determining whether an
    * SQE should be submitted asynchronously.
    */
  def this(size: Int, sqeAsyncThreshold: Int) =
    this(createRingBuffer(size, sqeAsyncThreshold))

  /** @return the UringCompletionQueue associated with the ring.
    */
  def ioUringCompletionQueue(): UringCompletionQueue = uringCompletionQueue

  /** @return the UringSubmissionQueue associated with the ring.
    */
  def ioUringSubmissionQueue(): UringSubmissionQueue = uringSubmissionQueue

  /** Submits pending operations in the queue to the kernel for processing.
    *
    * @return The number of operations successfully submitted.
    */
  def submit(): Int = uringSubmissionQueue.submit()

  /** @return The file descriptor of the ring buffer.
    */
  def fd(): Int = ringBuffer.fd()

  /** Closes the ring, realising any associated resources.
    */
  def close(): Unit = ringBuffer.close()
}

object UringRing {

  /** Creates a new UringRing instance with the default ring buffer size.
    *
    * @return a new UringRing instance.
    */
  def apply(): UringRing = new UringRing()

  /** Creates a new UringRing instance with the specified ring buffer size.
    *
    * @param size of the ring buffer.
    * @return a new UringRing instance.
    */
  def apply(size: Int): UringRing = new UringRing(size)

  /** Creates a new UringRing instance with the specified ring buffer size
    * and SQE (Submission Queue Entry) async threshold.
    *
    * @param size of the ring buffer.
    * @param sqeAsyncThreshold The threshold value for determining whether an SQE should be
    * submitted asynchronously.
    * @return a new UringRing instance.
    */
  def apply(size: Int, sqeAsyncThreshold: Int): UringRing = new UringRing(size, sqeAsyncThreshold)

}

class UringSubmissionQueue(private val ring: RingBuffer) {

  private[this] val submissionQueue: IOUringSubmissionQueue = ring.ioUringSubmissionQueue()

  def enqueueSqe(
      op: Byte,
      flags: Int,
      rwFlags: Int,
      fd: Int,
      bufferAddress: Long,
      length: Int,
      offset: Long,
      data: Short
  ): Boolean = {
    println(
      s"[SQ] Enqueuing a new Sqe with: OP: $op, flags: $flags, rwFlags: $rwFlags, fd: $fd, bufferAddress: $bufferAddress, length: $length, offset: $offset, extraData: $data"
    )
    submissionQueue.enqueueSqe(op, flags, rwFlags, fd, bufferAddress, length, offset, data)

  }

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

  def sendMsgRing(flags: Int, fd: Int, length: Int, data: Short): Boolean =
    submissionQueue.enqueueSqe(OP.IORING_OP_MSG_RING, flags, 0, fd, 0, length, 0, data)
}

object UringSubmissionQueue {
  final val SQE_SIZE = 64

  final val IORING_OP_ASYNC_CANCEL: Byte = 14.toByte

  final val SQE_USER_DATA_FIELD = 32

  def apply(ring: RingBuffer): UringSubmissionQueue = new UringSubmissionQueue(ring)
}

/** The UringCompletionQueue class represents a completion queue for the io_uring subsystem in the Netty library.
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

  /** Creates a new UringCompletionQueue instance associated with the specified RingBuffer.
    *
    * @param ring The RingBuffer associated with the completion queue.
    * @return A new UringCompletionQueue instance.
    */
  def apply(ring: RingBuffer): UringCompletionQueue = new UringCompletionQueue(ring)
}

/** The UringCompletionQueueCallback trait defines a callback interface for handling completion events
  * from the io_uring completion queue. It extends the IOUringCompletionQueueCallback trait and provides
  * a method handle to process the completion event.
  */
trait UringCompletionQueueCallback extends IOUringCompletionQueueCallback {
  def handle(fd: Int, res: Int, flags: Int, op: Byte, data: Short): Unit
}

/** Provides direct access to the native methods and functionalities
  * of the io_uring subsystem in Netty.
  */
object NativeAccess {
  val DEFAULT_RING_SIZE = Native.DEFAULT_RING_SIZE
  val DEFAULT_IOSEQ_ASYNC_THRESHOLD = Native.DEFAULT_IOSEQ_ASYNC_THRESHOLD
  val IORING_OP_POLL_WRITE = Native.IORING_OP_WRITE
  val IORING_OP_POLL_READ = Native.IORING_OP_READ

  val POLLIN = Native.POLLIN
  val POLLOUT = Native.POLLOUT

  def createRingBuffer(): RingBuffer =
    createRingBuffer(DEFAULT_RING_SIZE, DEFAULT_IOSEQ_ASYNC_THRESHOLD)

  def createRingBuffer(size: Int): RingBuffer =
    createRingBuffer(size, DEFAULT_IOSEQ_ASYNC_THRESHOLD)

  def createRingBuffer(size: Int, sqeAsyncThreshold: Int): RingBuffer =
    Native.createRingBuffer(size, sqeAsyncThreshold)

  def checkAllIOSupported(ringFd: Int): Unit =
    Native.checkAllIOSupported(ringFd)

  def checkKernelVersion(kernelVersion: String): Unit =
    Native.checkKernelVersion(kernelVersion)

  def ioUringEnter(ringFd: Int, toSubmit: Int, minComplete: Int, flags: Int): Int =
    Native.ioUringEnter(ringFd, toSubmit, minComplete, flags)

  def eventFdWrite(fd: Int, value: Long): Unit =
    Native.eventFdWrite(fd, value)

  def newBlockingEventFd: FileDescriptor =
    Native.newBlockingEventFd()

  def ioUringExit(
      submissionQueueArrayAddress: Long,
      submissionQueueRingEntries: Int,
      submissionQueueRingAddress: Long,
      submissionQueueRingSize: Int,
      completionQueueRingAddress: Long,
      completionQueueRingSize: Int,
      ringFd: Int
  ): Unit =
    Native.ioUringExit(
      submissionQueueArrayAddress,
      submissionQueueRingEntries,
      submissionQueueRingAddress,
      submissionQueueRingSize,
      completionQueueRingAddress,
      completionQueueRingSize,
      ringFd
    )
}

object Encoder {
  def encode(fd: Int, op: Byte, data: Short) = UserData.encode(fd, op, data)

  def decode(res: Int, flags: Int, udata: Long, callback: IOUringCompletionQueueCallback) =
    UserData.decode(res, flags, udata, callback)

}

object OP {
  val IORING_OP_NOP: Byte = 0
  val IORING_OP_READV: Byte = 1
  val IORING_OP_WRITEV: Byte = 2
  val IORING_OP_FSYNC: Byte = 3
  val IORING_OP_READ_FIXED: Byte = 4
  val IORING_OP_WRITE_FIXED: Byte = 5
  val IORING_OP_POLL_ADD: Byte = 6
  val IORING_OP_POLL_REMOVE: Byte = 7
  val IORING_OP_SYNC_FILE_RANGE: Byte = 8
  val IORING_OP_SENDMSG: Byte = 9
  val IORING_OP_RECVMSG: Byte = 10
  val IORING_OP_TIMEOUT: Byte = 11
  val IORING_OP_TIMEOUT_REMOVE: Byte = 12
  val IORING_OP_ACCEPT: Byte = 13
  val IORING_OP_ASYNC_CANCEL: Byte = 14
  val IORING_OP_LINK_TIMEOUT: Byte = 15
  val IORING_OP_CONNECT: Byte = 16
  val IORING_OP_FALLOCATE: Byte = 17
  val IORING_OP_OPENAT: Byte = 18
  val IORING_OP_CLOSE: Byte = 19
  val IORING_OP_FILES_UPDATE: Byte = 20
  val IORING_OP_STATX: Byte = 21
  val IORING_OP_READ: Byte = 22
  val IORING_OP_WRITE: Byte = 23
  val IORING_OP_FADVISE: Byte = 24
  val IORING_OP_MADVISE: Byte = 25
  val IORING_OP_SEND: Byte = 26
  val IORING_OP_RECV: Byte = 27
  val IORING_OP_OPENAT2: Byte = 28
  val IORING_OP_EPOLL_CTL: Byte = 29
  val IORING_OP_SPLICE: Byte = 30
  val IORING_OP_PROVIDE_BUFFERS: Byte = 31
  val IORING_OP_REMOVE_BUFFERS: Byte = 32
  val IORING_OP_TEE: Byte = 33
  val IORING_OP_SHUTDOWN: Byte = 34
  val IORING_OP_RENAMEAT: Byte = 35
  val IORING_OP_UNLINKAT: Byte = 36
  val IORING_OP_MKDIRAT: Byte = 37
  val IORING_OP_SYMLINKAT: Byte = 38
  val IORING_OP_LINKAT: Byte = 39
  val IORING_OP_MSG_RING: Byte = 40
  val IORING_OP_FSETXATTR: Byte = 41
  val IORING_OP_SETXATTR: Byte = 42
  val IORING_OP_FGETXATTR: Byte = 43
  val IORING_OP_GETXATTR: Byte = 44
  val IORING_OP_SOCKET: Byte = 45
  val IORING_OP_URING_CMD: Byte = 46
  val IORING_OP_SEND_ZC: Byte = 47
  val IORING_OP_SENDMSG_ZC: Byte = 48
  val IORING_OP_LAST: Byte = 49
}
