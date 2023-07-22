package fs2.io.uring.net

import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.effect.std.Mutex
import cats.syntax.all._

import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.SocketAddress

import fs2.Chunk
import fs2.Pipe
import fs2.Stream
import fs2.io.net.Socket

import fs2.io.uring.Uring
import fs2.io.uring.unsafe.util.OP._

import io.netty.buffer.ByteBuf
import io.netty.buffer.UnpooledByteBufAllocator

private[net] final class UringSocket[F[_]: LiftIO](
    ring: Uring,
    sockfd: Int,
    remoteAddress: SocketAddress[IpAddress],
    buffer: ByteBuf,
    defaultReadSize: Int,
    readMutex: Mutex[F],
    writeMutex: Mutex[F]
)(implicit F: Async[F])
    extends Socket[F] {

  private[this] def recv(bufferAddress: Long, pos: Int, maxBytes: Int, flags: Int): F[Int] =
    ring.call(IORING_OP_RECV, flags, 0, sockfd, bufferAddress + pos, maxBytes - pos, 0).to

  def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
    readMutex.lock.surround {
      for {
        _ <- F.delay(buffer.clear())
        readed <- recv(buffer.memoryAddress(), 0, maxBytes, 0)
        bytes <- F.delay {
          val arr = new Array[Byte](readed)
          buffer.getBytes(0, arr)
          arr
        }
      } yield Option.when(readed > 0)(Chunk.array(bytes))
    }

  def readN(numBytes: Int): F[Chunk[Byte]] =
    readMutex.lock.surround {
      for {
        _ <- F.delay(buffer.clear())
        readed <- recv(
          buffer.memoryAddress(),
          0,
          numBytes,
          -1
        ) // TODO: Replace -1 with MSG_WAITALL
        bytes <- F.delay {
          val arr = new Array[Byte](readed)
          buffer.getBytes(0, arr)
          arr
        }
      } yield if (readed > 0) Chunk.array(bytes) else Chunk.empty
    }

  def reads: Stream[F, Byte] = Stream.repeatEval(read(defaultReadSize)).unNoneTerminate.unchunks

  def endOfInput: F[Unit] = ring.call(op = IORING_OP_SHUTDOWN, fd = sockfd, length = 0).void.to

  def endOfOutput: F[Unit] = ring.call(op = IORING_OP_SHUTDOWN, fd = sockfd, length = 1).void.to

  def isOpen: F[Boolean] = F.pure(true)

  def remoteAddress: F[SocketAddress[IpAddress]] = F.pure(remoteAddress)

  def localAddress: F[SocketAddress[IpAddress]] = UringSocket.getLocalAddress(sockfd)

  private[this] def send(bufferAddress: Long, pos: Int, maxBytes: Int, flags: Int): F[Int] =
    ring.call(IORING_OP_SEND, flags, 0, sockfd, bufferAddress + pos, maxBytes - pos, 0).to
  def write(bytes: Chunk[Byte]): F[Unit] =
    writeMutex.lock
      .surround {
        for {
          _ <- F.delay {
            buffer.clear()
            buffer.writeBytes(bytes.toArray)
          }
          _ <- send(
            buffer.memoryAddress(),
            0,
            bytes.size,
            -1
          ) // TODO: Replace -1 with MSG_NOSIGNAL
        } yield ()
      }
      .unlessA(bytes.isEmpty)

  def writes: Pipe[F, Byte, Nothing] = _.chunks.foreach(write)
}

private[net] object UringSocket {

  def apply[F[_]: LiftIO](ring: Uring, fd: Int, remote: SocketAddress[IpAddress])(implicit
      F: Async[F]
  ): Resource[F, UringSocket[F]] =
    for {
      buffer <- createBuffer()
      readMutex <- Resource.eval(Mutex[F])
      writeMutex <- Resource.eval(Mutex[F])
      socket = new UringSocket(ring, fd, remote, buffer, 8192, readMutex, writeMutex)
    } yield socket

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
  def createBuffer[F[_]: Sync](defaultReadSize: Int = 8192): Resource[F, ByteBuf] =
    Resource.make(
      Sync[F].delay(UnpooledByteBufAllocator.DEFAULT.directBuffer(defaultReadSize))
    )(buf => Sync[F].delay(if (buf.refCnt() > 0) { val _ = buf.release() }))

  def getLocalAddress[F[_]](fd: Int)(implicit F: Sync[F]): F[SocketAddress[IpAddress]] = 
    /* TODO: Work on SocketAddressHelper before implementing this method */
    ???
}
