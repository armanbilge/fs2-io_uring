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

/** Represents an io_uring Ring with both Submission Queue (SQ) and Completion Queue (CQ).
  *
  * It provides methods to interact with the queues, such as submitting operations,
  * accessing the file descriptor of the ring, and closing the ring.
  *
  * @param ringBuffer The RingBuffer associated with the Ring.
  */
final class UringRing(private[this] val ringBuffer: RingBuffer) {
  // The Completion Queue associated with the Ring.
  private[this] val uringCompletionQueue: UringCompletionQueue = UringCompletionQueue(ringBuffer)

  // The Submission Queue associated with the Ring.
  private[this] val uringSubmissionQueue: UringSubmissionQueue = UringSubmissionQueue(ringBuffer)

  /** Constructs a new Ring instance with the default Ring buffer size.
    */
  def this() = this(createRingBuffer())

  /** Constructs a new Ring instance with the specified Ring buffer size.
    *
    * @param size of the new Ring buffer.
    */
  def this(size: Int) = this(createRingBuffer(size))

  /** Constructs a new Ring instance with the specified Ring buffer size and
    * SQE (Submission Queue Entry) async threshold.
    *
    * @param size of the Ring buffer.
    * @param sqeAsyncThreshold The threshold value for determining whether an
    * SQE should be submitted asynchronously.
    */
  def this(size: Int, sqeAsyncThreshold: Int) =
    this(createRingBuffer(size, sqeAsyncThreshold))

  /** @return the Completion Queue (CQ) associated with the Ring.
    */
  def ioUringCompletionQueue(): UringCompletionQueue = uringCompletionQueue

  /** @return the Submission Queue (SQ) associated with the Ring.
    */
  def ioUringSubmissionQueue(): UringSubmissionQueue = uringSubmissionQueue

  /** Submits pending operations in the queue to the kernel for processing.
    *
    * @return The number of operations successfully submitted.
    */
  def submit(): Int = uringSubmissionQueue.submit()

  /** @return The file descriptor of the Ring buffer.
    */
  def fd(): Int = ringBuffer.fd()

  /** Closes the Ring, realising any associated resources.
    */
  def close(): Unit = ringBuffer.close()
}

object UringRing {

  /** Creates a new UringRing instance with the default Ring buffer size.
    *
    * @return a new Ring instance.
    */
  def apply(): UringRing = new UringRing()

  /** Creates a new UringRing instance with the specified Ring buffer size.
    *
    * @param size of the ring buffer.
    * @return a new Ring instance.
    */
  def apply(size: Int): UringRing = new UringRing(size)

  /** Creates a new Ring instance with the specified ring buffer size
    * and SQE (Submission Queue Entry) async threshold.
    *
    * @param size of the ring buffer.
    * @param sqeAsyncThreshold The threshold value for determining whether an SQE should be
    * submitted asynchronously.
    * @return a new UringRing instance.
    */
  def apply(size: Int, sqeAsyncThreshold: Int): UringRing = new UringRing(size, sqeAsyncThreshold)

}

/** Represents an io_uring Submission Queue (SQ).
  *
  * It provides methods for enqueuing different types of IO operations and controlling their execution.
  *
  * @param ring The RingBuffer used to queue IO operations.
  */
final class UringSubmissionQueue(private[this] val ring: RingBuffer) {

  // The Submission Queue instance associated with the Ring.
  private[this] val submissionQueue: IOUringSubmissionQueue = ring.ioUringSubmissionQueue()

  /** Creates a Submission Queue Entry (SQE) associates an IO operation and enqueues it to the Submission Queue.
    * @param op The type of IO operation to enqueue.
    * @param flags The flags for the IO operation.
    * @param rwFlags The flags for read/write operations.
    * @param fd The file descriptor associated with the IO operation.
    * @param bufferAddress The address of the buffer for read/write operations.
    * @param length The length of the buffer for read/write operations.
    * @param offset The offset at which to start read/write operations.
    * @param data Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
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

  /** Increment the number of handled file descriptors. */
  def incrementHandledFds(): Unit = submissionQueue.incrementHandledFds()

  /** Decrement the number of handled file descriptors. */
  def decrementHandledFds(): Unit = submissionQueue.decrementHandledFds()

  /** Add a timeout operation to the Submission Queue.
    * @param nanoSeconds The timeout duration in nanoseconds.
    * @param extraData Extra data (id) for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addTimeout(nanoSeconds: Long, extraData: Short): Boolean =
    submissionQueue.addTimeout(nanoSeconds, extraData)

  /** Enqueues an operation to the Submission Queue to add a poll on the input availability of a file descriptor.
    * @param fd The file descriptor to poll.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addPollIn(fd: Int): Boolean = submissionQueue.addPollIn(fd)

  /** Add a poll operation on the hang-up event of a file descriptor.
    * @param fd The file descriptor to poll.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addPollRdHup(fd: Int): Boolean = submissionQueue.addPollRdHup(fd)

  /** Add a poll operation on the output availability of a file descriptor.
    * @param fd The file descriptor to poll.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addPollOut(fd: Int): Boolean = submissionQueue.addPollOut(fd)

  /** Add a receive message operation from a file descriptor.
    * @param fd The file descriptor.
    * @param msgHdr The address of the message header.
    * @param extraData Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addRecvmsg(fd: Int, msgHdr: Long, extraData: Short): Boolean =
    submissionQueue.addRecvmsg(fd, msgHdr, extraData)

  /** Enqueues a send message operation to a file descriptor.
    * @param fd The file descriptor.
    * @param msgHdr The address of the message header.
    * @param extraData Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addSendmsg(fd: Int, msgHdr: Long, extraData: Short): Boolean =
    submissionQueue.addSendmsg(fd, msgHdr, extraData)

  /** Enqueues a send message operation to a file descriptor, with specific flags.
    * @param fd The file descriptor.
    * @param msgHdr The address of the message header.
    * @param flags The flags for the send message operation.
    * @param extraData Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addSendmsg(fd: Int, msgHdr: Long, flags: Int, extraData: Short): Boolean =
    submissionQueue.addSendmsg(fd, msgHdr, flags, extraData)

  /** Add a read operation from a file descriptor.
    * @param fd The file descriptor.
    * @param bufferAddress The address of the buffer where to store the data.
    * @param pos The position in the buffer to start storing data.
    * @param limit The maximum number of bytes to read.
    * @param extraData Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addRead(fd: Int, bufferAddress: Long, pos: Int, limit: Int, extraData: Short): Boolean =
    submissionQueue.addRead(fd, bufferAddress, pos, limit, extraData)

  /** Enqueues an operation to read data from an event file descriptor.
    * @param fd The file descriptor.
    * @param bufferAddress The address of the buffer where the read data should be placed.
    * @param pos The position in the buffer to start placing data.
    * @param limit The maximum number of bytes to read.
    * @param extraData Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addEventFdRead(
      fd: Int,
      bufferAddress: Long,
      pos: Int,
      limit: Int,
      extraData: Short
  ): Boolean = submissionQueue.addEventFdRead(fd, bufferAddress, pos, limit, extraData)

  /** Enqueues a write operation to a file descriptor.
    * @param fd The file descriptor.
    * @param bufferAddress The address of the buffer containing the data to write.
    * @param pos The position in the buffer to start writing data.
    * @param limit The maximum number of bytes to write.
    * @param extraData Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addWrite(fd: Int, bufferAddress: Long, pos: Int, limit: Int, extraData: Short): Boolean =
    submissionQueue.addWrite(fd, bufferAddress, pos, limit, extraData)

  /** Enqueues a receive operation from a file descriptor.
    * @param fd The file descriptor.
    * @param bufferAddress The address of the buffer where the received data should be placed.
    * @param pos The position in the buffer to start placing data.
    * @param limit The maximum number of bytes to receive.
    * @param extraData Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addRecv(fd: Int, bufferAddress: Long, pos: Int, limit: Int, extraData: Short): Boolean =
    submissionQueue.addRecv(fd, bufferAddress, pos, limit, extraData)

  /** Enqueues a send operation to a file descriptor.
    * @param fd The file descriptor.
    * @param bufferAddress The address of the buffer containing the data to send.
    * @param pos The position in the buffer to start sending data.
    * @param limit The maximum number of bytes to send.
    * @param extraData Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addSend(fd: Int, bufferAddress: Long, pos: Int, limit: Int, extraData: Short): Boolean =
    submissionQueue.addSend(fd, bufferAddress, pos, limit, extraData)

  /** Enqueues an accept operation for a file descriptor.
    * @param fd The file descriptor.
    * @param address The address where the details of the incoming connection will be stored.
    * @param addressLength The length of the address structure.
    * @param extraData Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addAccept(fd: Int, address: Long, addressLength: Long, extraData: Short): Boolean =
    submissionQueue.addAccept(fd, address, addressLength, extraData)

  /** Enqueues an operation to remove a poll event from the monitoring of a file descriptor.
    * @param fd The file descriptor.
    * @param pollMask The mask for the poll events to be removed.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addPollRemove(fd: Int, pollMask: Int): Boolean = submissionQueue.addPollRemove(fd, pollMask)

  /** Enqueues a connection operation for a file descriptor to a socket address.
    * @param fd The file descriptor.
    * @param socketAddress The address of the socket to connect to.
    * @param socketAddressLength The length of the socket address.
    * @param extraData Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addConnect(
      fd: Int,
      socketAddress: Long,
      socketAddressLength: Long,
      extraData: Short
  ): Boolean = submissionQueue.addConnect(fd, socketAddress, socketAddressLength, extraData)

  /** Enqueues an operation to write data to a file descriptor from multiple buffers.
    * @param fd The file descriptor.
    * @param iovecArrayAddress The address of an array of iovec structures, each specifying a buffer.
    * @param length The total length of the data to write.
    * @param extraData Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addWritev(fd: Int, iovecArrayAddress: Long, length: Int, extraData: Short): Boolean =
    submissionQueue.addWritev(fd, iovecArrayAddress, length, extraData)

  /** Enqueues a close operation for a file descriptor.
    * @param fd The file descriptor to close.
    * @param extraData Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def addClose(fd: Int, extraData: Short): Boolean = submissionQueue.addClose(fd, extraData)

  /** Submit all enqueued operations in the Submission Queue to the kernel for execution.
    * @return The number of submitted operations.
    */
  def submit(): Int = submissionQueue.submit()

  /** Submit all enqueued operations in the Submission Queue to the kernel for execution and wait for them to complete.
    * @return The number of submitted operations.
    */
  def submitAndWait(): Int = submissionQueue.submitAndWait()

  /** Get the number of operations in the Submission Queue.
    * @return The number of operations in the Submission Queue.
    */
  def count(): Long = submissionQueue.count()

  /** Release resources associated with the submission queue. */
  def release(): Unit = submissionQueue.release()

  /** Enqueues an IO operation to send a message to a Ring.
    * @param flags The flags for the IO operation.
    * @param fd The file descriptor associated with the IO operation.
    * @param length The length of the message to send.
    * @param data Extra data for the IO operation.
    * @return true if the operation is successfully enqueued, false otherwise.
    */
  def sendMsgRing(flags: Int, fd: Int, length: Int, data: Short): Boolean =
    submissionQueue.enqueueSqe(OP.IORING_OP_MSG_RING, flags, 0, fd, 0, length, 0, data)
}

private[this] object UringSubmissionQueue {

  /** Creates a new Submission Queue (SQ) instance associated with the specified RingBuffer.
    *
    * @param ring The RingBuffer associated with the Submission Queue.
    * @return A new Submission Queue instance.
    */
  def apply(ring: RingBuffer): UringSubmissionQueue = new UringSubmissionQueue(ring)
}

/** Represents a io_uring Completion Queue (CQ).
  *
  * It provides methods to interact with the Completion Queue, such as checking for completions, processing completions,
  * waiting for completions, and accessing the underlying RingBuffer.
  *
  * @param ring The RingBuffer associated with the Completion Queue.
  */
final class UringCompletionQueue(private[this] val ring: RingBuffer) {

  // The Completion Queue instance associated with the Ring.
  private[this] val completionQueue: IOUringCompletionQueue = ring.ioUringCompletionQueue()

  /** Checks if there are any completions in the Completion Queue.
    *
    * @return `true` if there are completions, `false` otherwise.
    */
  def hasCompletions(): Boolean = completionQueue.hasCompletions()

  /** Processes the Completion Queue entries (CQE) with the provided callback.
    *
    * @param cb Callback function to process each entry.
    * @return The number of entries processed.
    */
  def process(cb: IOUringCompletionQueueCallback): Int = completionQueue.process(cb)

  /** Waits for at least one completion entry in the Completion Queue.
    */
  def ioUringWaitCqe(): Unit = completionQueue.ioUringWaitCqe()

  /** Fetches the ring address of the associated ring.
    *
    * @return The address of the ring.
    */
  def ringAddress(): Long = completionQueue.ringAddress

  /** Fetches the file descriptor of the associated ring.
    *
    * @return The file descriptor of the ring.
    */
  def ringFd(): Int = completionQueue.ringFd

  /** Fetches the size of the associated ring.
    *
    * @return The size of the ring.
    */
  def ringSize(): Int = completionQueue.ringSize
}

private[this] object UringCompletionQueue {

  /** Creates a new Completion Queue (CQ) instance associated with the specified RingBuffer.
    *
    * @param ring The RingBuffer associated with the Completion Queue.
    * @return A new Completion Queue instance.
    */
  def apply(ring: RingBuffer): UringCompletionQueue = new UringCompletionQueue(ring)
}

/** The UringCompletionQueueCallback trait defines a callback interface for handling completion event from the io_uring Completion Queue.
  */
trait UringCompletionQueueCallback extends IOUringCompletionQueueCallback {
  def handle(fd: Int, res: Int, flags: Int, op: Byte, data: Short): Unit
}

/** Provides a bridge to the native io_uring functionalities provided by the Netty library.
  * It provides methods to create RingBuffers with varying sizes and thresholds, check IO support,
  * verify kernel version, manipulate event file descriptors, and interact directly with the underlying io_uring.
  */
object NativeAccess {

  /** Creates a RingBuffer with the default size and IO sequence async threshold.
    * @return A new RingBuffer instance.
    */
  def createRingBuffer(): RingBuffer =
    createRingBuffer(Native.DEFAULT_RING_SIZE, Native.DEFAULT_IOSEQ_ASYNC_THRESHOLD)

  /** Creates a RingBuffer with the specified size and default IO sequence async threshold.
    * @param size The desired size for the RingBuffer.
    * @return A new RingBuffer instance.
    */
  def createRingBuffer(size: Int): RingBuffer =
    createRingBuffer(size, Native.DEFAULT_IOSEQ_ASYNC_THRESHOLD)

  /** Creates a RingBuffer with the specified size and IO sequence async threshold.
    * @param size The desired size for the RingBuffer.
    * @param sqeAsyncThreshold The desired IO sequence async threshold for the RingBuffer.
    * @return A new RingBuffer instance.
    */
  def createRingBuffer(size: Int, sqeAsyncThreshold: Int): RingBuffer =
    Native.createRingBuffer(size, sqeAsyncThreshold)

  /** Checks if all IO operations are supported for the given ring file descriptor.
    * @param ringFd The file descriptor of the ring.
    */
  def checkAllIOSupported(ringFd: Int): Unit =
    Native.checkAllIOSupported(ringFd)

  /** Checks if the given kernel version is compatible with the io_uring.
    * @param kernelVersion The version of the kernel to check.
    */
  def checkKernelVersion(kernelVersion: String): Unit =
    Native.checkKernelVersion(kernelVersion)

  /** Submits requests to the kernel and waits for a minimum number of completions.
    * @param ringFd The file descriptor of the ring.
    * @param toSubmit The number of submissions to make.
    * @param minComplete The minimum number of completions to wait for.
    * @param flags The flags for the operation.
    * @return The number of IO events retrieved.
    */
  def ioUringEnter(ringFd: Int, toSubmit: Int, minComplete: Int, flags: Int): Int =
    Native.ioUringEnter(ringFd, toSubmit, minComplete, flags)

  /** Writes a value to the specified event file descriptor.
    * @param fd The file descriptor to write to.
    * @param value The value to write.
    */
  def eventFdWrite(fd: Int, value: Long): Unit =
    Native.eventFdWrite(fd, value)

  /** Creates a new blocking event file descriptor.
    * @return A new FileDescriptor instance.
    */
  def newBlockingEventFd: FileDescriptor =
    Native.newBlockingEventFd()

  /** Closes the Ring, releasing the memory associated with it.
    * @param submissionQueueArrayAddress The address of the Submission Queue array.
    * @param submissionQueueRingEntries The number of entries in the Submission Queue Ring.
    * @param submissionQueueRingAddress The address of the Submission Queue Ring.
    * @param submissionQueueRingSize The size of the Submission Queue Ring.
    * @param completionQueueRingAddress The address of the Completion Queue Ring.
    * @param completionQueueRingSize The size of the Completion Queue Ring.
    * @param ringFd The file descriptor of the Ring.
    */
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

/** Provides utility methods for encoding and decoding user data in IO operations.
  * It uses the same encoding scheme as the Netty API. This is used to store and retrieve information about the operations
  * being submitted to or completed by the IOUring interface.
  */
object Encoder {

  /** Encodes the file descriptor, operation type, and data into a Long value for use with the IOUring interface.
    * This encoding is performed in the same manner as in the Netty API.
    *
    * @param fd The file descriptor for the IO operation.
    * @param op The operation type.
    * @param data The data for the operation.
    * @return Encoded Long value that represents the given parameters.
    */
  def encode(fd: Int, op: Byte, data: Short): Long = UserData.encode(fd, op, data)

  /** Decodes the result, flags and user data from a completed IO operation, and passes this information to the provided callback.
    * This decoding is performed in the same manner as in the Netty API.
    *
    * @param res The result of the operation.
    * @param flags Any flags associated with the operation.
    * @param udata The user data for the operation.
    * @param callback The callback to be invoked with the decoded information.
    */
  def decode(res: Int, flags: Int, udata: Long, callback: IOUringCompletionQueueCallback) =
    UserData.decode(res, flags, udata, callback)
}

/** Defines constants for various operation types supported by the io_uring interface.
  */
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
