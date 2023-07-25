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

import cats.effect.kernel.Sync
import cats.effect.kernel.Resource
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.buffer.ByteBuf

private[uring] object util {

  /** TODO: We need to choose between heap or direct buffer and pooled or unpooled buffer: (I feel that Direct/Unpooled is the right combination)
    *
    *    - Heap Buffer: Buffer is backed by a byte array located in the JVM's heap. Convenient if we work with API's that requires byte arrays.
    *                    However, reading/writing from I/O channels requires copying data between the JVM heap and the Native heap which is slow.
    *
    *    - Direct Buffer: Buffer is allocated on the Native heap. Read and writes from I/O channels can occur without copying any data which is faster.
    *                    However, interacting with other Java APIs will require additional data copy. (REMEMBER: They are not subject to the JVM garbage collector, we have to free the memory)
    *
    *    - Pooled Buffer: pre-allocated in memory and reused as needed. It is faster but consumes a lot of memory (we need to conserve a pool of buffers).
    *
    *    - Unpooled Buffer: Allocated when we need them and deallocated when we are done. It may be slower but consume only the memory of the buffer that we are using.
    */
  def createBuffer[F[_]: Sync](size: Int): Resource[F, ByteBuf] =
    Resource.make(
      Sync[F].delay(UnpooledByteBufAllocator.DEFAULT.directBuffer(size))
    )(buf => Sync[F].delay(if (buf.refCnt() > 0) { val _ = buf.release() }))

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

}
