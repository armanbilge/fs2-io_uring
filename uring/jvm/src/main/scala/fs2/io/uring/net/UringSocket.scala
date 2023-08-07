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

package fs2.io.uring.net

import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Mutex
import cats.syntax.all._

import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.SocketAddress

import fs2.Chunk
import fs2.Pipe
import fs2.Stream
import fs2.io.net.Socket

import fs2.io.uring.Uring
import fs2.io.uring.unsafe.util.createBuffer
import fs2.io.uring.unsafe.util.OP._

import io.netty.buffer.ByteBuf
import io.netty.incubator.channel.uring.UringLinuxSocket

private[net] final class UringSocket[F[_]: LiftIO](
    ring: Uring,
    linuxSocket: UringLinuxSocket,
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

        _ <- F.delay(println(s"[SOCKET] reading the array: ${bytes}"))

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
          0 // TODO: Replace with MSG_WAITALL
        )

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

  def localAddress: F[SocketAddress[IpAddress]] =
    F.delay(SocketAddress.fromInetSocketAddress(linuxSocket.getLocalAddress()))

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

          _ <- F.delay(println(s"[SOCKET] writing in array: $bytes"))

          _ <- send(
            buffer.memoryAddress(),
            0,
            bytes.size,
            0 // TODO Replace with MSG_NOSIGNAL
          )

          _ <- F.delay(println(s"[SOCKET] Message sent!"))

        } yield ()
      }
      .unlessA(bytes.isEmpty)

  def writes: Pipe[F, Byte, Nothing] = _.chunks.foreach(write)
}

private[net] object UringSocket {
  private[this] val defaultReadSize = 8192

  def apply[F[_]: LiftIO](
      ring: Uring,
      linuxSocket: UringLinuxSocket,
      fd: Int,
      remote: SocketAddress[IpAddress]
  )(implicit
      F: Async[F]
  ): Resource[F, UringSocket[F]] =
    for {
      buffer <- createBuffer(defaultReadSize)
      readMutex <- Resource.eval(Mutex[F])
      writeMutex <- Resource.eval(Mutex[F])
      socket = new UringSocket(
        ring,
        linuxSocket,
        fd,
        remote,
        buffer,
        defaultReadSize,
        readMutex,
        writeMutex
      )
    } yield socket

}
