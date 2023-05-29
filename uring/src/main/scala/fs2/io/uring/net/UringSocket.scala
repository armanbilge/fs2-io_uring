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

package fs2
package io
package uring
package net

import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.effect.std.Semaphore
import cats.syntax.all._
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.SocketAddress
import fs2.Pipe
import fs2.io.net.Socket
import fs2.io.uring.unsafe.uring._
import fs2.io.uring.unsafe.util._

import java.io.IOException
import scala.scalanative.libc.errno._
import scala.scalanative.posix.sys.socket._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private[net] final class UringSocket[F[_]: LiftIO](
    ring: Uring,
    fd: Int,
    _remoteAddress: SocketAddress[IpAddress],
    buffer: ResizableBuffer[F],
    defaultReadSize: Int,
    readSemaphore: Semaphore[F],
    writeSemaphore: Semaphore[F]
)(implicit F: Async[F])
    extends Socket[F] {

  private[this] def recv(bytes: Ptr[Byte], maxBytes: Int, flags: Int): F[Int] =
    ring.call(io_uring_prep_recv(_, fd, bytes, maxBytes.toULong, flags)).to

  def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
    readSemaphore.permit.surround {
      for {
        buf <- buffer.get(maxBytes)
        readed <- recv(buf, maxBytes, 0)
      } yield Option.when(readed > 0)(Chunk.array(toArray(buf, readed)))
    }

  def readN(numBytes: Int): F[Chunk[Byte]] =
    readSemaphore.permit.surround {
      for {
        buf <- buffer.get(numBytes)
        readed <- recv(buf, numBytes, MSG_WAITALL)
      } yield if (readed > 0) Chunk.array(toArray(buf, readed)) else Chunk.empty
    }

  def reads: Stream[F, Byte] = Stream.repeatEval(read(defaultReadSize)).unNoneTerminate.unchunks

  def endOfInput: F[Unit] = ring.call(io_uring_prep_shutdown(_, fd, 0)).void.to

  def endOfOutput: F[Unit] = ring.call(io_uring_prep_shutdown(_, fd, 1)).void.to

  def isOpen: F[Boolean] = F.pure(true)

  def remoteAddress: F[SocketAddress[IpAddress]] = F.pure(_remoteAddress)

  def localAddress: F[SocketAddress[IpAddress]] = UringSocket.getLocalAddress(fd)

  def write(bytes: Chunk[Byte]): F[Unit] =
    writeSemaphore.permit
      .surround {
        val slice = bytes.toArraySlice
        val ptr = slice.values.at(0) + slice.offset.toLong
        ring
          .call(io_uring_prep_send(_, fd, ptr, slice.length.toULong, MSG_NOSIGNAL))
          .as(slice) // to keep in scope of gc
          .void
          .to
      }
      .unlessA(bytes.isEmpty)

  def writes: Pipe[F, Byte, Nothing] = _.chunks.foreach(write)

}

private[net] object UringSocket {

  def apply[F[_]: LiftIO](ring: Uring, fd: Int, remote: SocketAddress[IpAddress])(implicit
      F: Async[F]
  ): Resource[F, UringSocket[F]] =
    ResizableBuffer(8192).evalMap { buf =>
      (Semaphore(1), Semaphore(1)).mapN(new UringSocket(ring, fd, remote, buf, 8192, _, _))
    }

  def getLocalAddress[F[_]](fd: Int)(implicit F: Sync[F]): F[SocketAddress[IpAddress]] =
    F.delay {
      SocketAddressHelpers.toSocketAddress { (addr, len) =>
        if (getsockname(fd, addr, len) == -1)
          Left(new IOException(s"getsockname: ${errno}"))
        else
          Either.unit
      }
    }.flatMap(_.liftTo)

}
