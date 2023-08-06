package fs2.io.uring.net
import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.effect.std.Mutex
import cats.syntax.all._

import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.SocketAddress
import com.comcast.ip4s.MulticastJoin

import fs2.Chunk
import fs2.Pipe
import fs2.Stream
import fs2.io.net.Datagram
import fs2.io.net.DatagramSocket

import fs2.io.uring.Uring
import fs2.io.uring.unsafe.util.createBuffer
import fs2.io.uring.unsafe.util.OP._

import io.netty.buffer.ByteBuf
import io.netty.incubator.channel.uring.UringLinuxSocket

private[net] final class UringDatagramSocket[F[_]: LiftIO](
    ring: Uring,
    linuxSocket: UringLinuxSocket,
    sockfd: Int,
    buffer: ByteBuf,
    defaultReadSize: Int,
    readMutex: Mutex[F],
    writeMutex: Mutex[F]
)(implicit F: Async[F])
    extends DatagramSocket[F] {

  private[this] def recvfrom(
      bufferAddress: Long,
      len: Int
  ): F[(SocketAddress[IpAddress], Int)] = // TODO: Work around this
    // ring.call(IORING_OP_RECVFROM, fd = sockfd, bufferAddress = bufferAddress, length = len).to
    ???

  private[this] def recvMsg(msgHdr: Long) = ???

  def read: F[Datagram] =
    readMutex.lock.surround {
      for {
        _ <- F.delay(buffer.clear())
        (srcAddress, len) <- recvfrom(buffer.memoryAddress(), defaultReadSize)
        bytes <- F.delay {
          val arr = new Array[Byte](len)
          buffer.getBytes(0, arr)
          arr
        }
      } yield Datagram(srcAddress, Chunk.array(bytes))
    }

  def reads: Stream[F, Datagram] = Stream.repeatEval(read)

  private[this] def sendto(
      bufferAddress: Long,
      len: Int,
      address: SocketAddress[IpAddress]
  ): F[Int] =
    // ring
    //   .call(IORING_OP_SEND, fd = sockfd, bufferAddress = bufferAddress, length = len)
    //   .to
    ???

  private[this] def sendMsg(msgHdr: Long, flags: Int) = ???

  def write(datagram: Datagram): F[Unit] =
    writeMutex.lock
      .surround {
        for {
          _ <- F.delay {
            buffer.clear()
            buffer.writeBytes(datagram.bytes.toArray)
          }
          _ <- sendto(buffer.memoryAddress(), datagram.bytes.size, datagram.remote)
        } yield ()
      }
      .unlessA(datagram.bytes.isEmpty)

  def writes: Pipe[F, Datagram, Nothing] = _.evalMap(datagram => write(datagram)).drain

  def localAddress: F[SocketAddress[IpAddress]] =
    F.delay(SocketAddress.fromInetSocketAddress(linuxSocket.getLocalAddress()))

  def join(
      join: MulticastJoin[IpAddress],
      interface: DatagramSocket.NetworkInterface
  ): F[GroupMembership] =
    F.raiseError(new UnsupportedOperationException("Not supported in DatagramSocket"))
}

object UringDatagramSocket {
  private[this] val defaultReadSize = 65535

  def apply[F[_]: LiftIO](ring: Uring, linuxSocket: UringLinuxSocket, fd: Int)(implicit
      F: Async[F]
  ): Resource[F, UringDatagramSocket[F]] =
    for {
      buffer <- createBuffer(defaultReadSize)
      readMutex <- Resource.eval(Mutex[F])
      writeMutex <- Resource.eval(Mutex[F])
      socket = new UringDatagramSocket(
        ring,
        linuxSocket,
        fd,
        buffer,
        defaultReadSize,
        readMutex,
        writeMutex
      )
    } yield socket
}
