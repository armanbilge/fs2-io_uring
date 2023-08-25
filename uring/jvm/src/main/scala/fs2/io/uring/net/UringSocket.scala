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
import fs2.io.uring.unsafe.util.errno._

private[net] final class UringSocket[F[_]: LiftIO](
    ring: Uring,
    linuxSocket: UringLinuxSocket,
    sockfd: Int,
    _remoteAddress: SocketAddress[IpAddress],
    buffer: ByteBuf,
    defaultReadSize: Int,
    readMutex: Mutex[F],
    writeMutex: Mutex[F]
)(implicit F: Async[F])
    extends Socket[F] {

  private val debug = false
  private val debugRead = debug && true
  private val debugWrite = debug && true

  private[this] def recv(bufferAddress: Long, maxBytes: Int, flags: Int): F[Int] =
    ring.call(IORING_OP_RECV, flags, 0, sockfd, bufferAddress, maxBytes, 0).to

  def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
    readMutex.lock.surround {
      for {
        _ <- F.delay(buffer.clear()) // Clear the buffer before writing

        _ <- F.whenA(debugRead)(
          F.delay(println(s"[SOCKET][READ] writing the received message in the buffer..."))
        )

        readed <- recv(buffer.memoryAddress(), maxBytes, 0)

        _ <- F.whenA(debugRead)(
          F.delay(
            println(s"[SOCKET][READ] transfering the message from the buffer to a new array...")
          )
        )

        bytes <- F.delay {
          val arr = new Array[Byte](readed)
          buffer.getBytes(0, arr)
          arr
        }
        _ <- F.whenA(debugRead)(F.delay(println(s"[SOCKET][READ] Done reading!")))

      } yield Option.when(readed > 0)(Chunk.array(bytes))
    }

  def readN(numBytes: Int): F[Chunk[Byte]] =
    readMutex.lock.surround {
      for {
        _ <- F.delay(buffer.clear())

        readed <- recv(
          buffer.memoryAddress(),
          numBytes,
          0
        )

        bytes <- F.delay {
          val arr = new Array[Byte](readed)
          buffer.getBytes(0, arr)
          arr
        }
      } yield if (readed > 0) Chunk.array(bytes) else Chunk.empty
    }

  def reads: Stream[F, Byte] = Stream.repeatEval(read(defaultReadSize)).unNoneTerminate.unchunks

  def endOfInput: F[Unit] =
    ring.call(op = IORING_OP_SHUTDOWN, fd = sockfd, length = 0, mask = _ == ENOTCONN).void.to

  def endOfOutput: F[Unit] =
    ring.call(op = IORING_OP_SHUTDOWN, fd = sockfd, length = 1, mask = _ == ENOTCONN).void.to

  def isOpen: F[Boolean] = F.pure(true)

  def remoteAddress: F[SocketAddress[IpAddress]] = F.pure(_remoteAddress)

  def localAddress: F[SocketAddress[IpAddress]] =
    F.delay(SocketAddress.fromInetSocketAddress(linuxSocket.getLocalAddress()))

  private[this] def send(bufferAddress: Long, maxBytes: Int, flags: Int): F[Int] =
    ring.call(IORING_OP_SEND, flags, 0, sockfd, bufferAddress, maxBytes, 0).to

  def write(bytes: Chunk[Byte]): F[Unit] =
    writeMutex.lock
      .surround {
        for {
          _ <- F.whenA(debugWrite)(
            F.delay(println(s"[SOCKET][WRITE] transfering to the buffer the bytes..."))
          )

          _ <- F.delay {
            buffer.clear()
            buffer.writeBytes(bytes.toArray)
          }

          _ <- F.whenA(debugWrite)(
            F.delay(println(s"[SOCKET][WRITE] sending the bytes in the buffer..."))
          )

          _ <- send(
            buffer.memoryAddress(),
            bytes.size,
            0
          )

          _ <- F.whenA(debugWrite)(F.delay(println(s"[SOCKET][WRITE] message sent!")))

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
