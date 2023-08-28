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
package io.uring
package net
package unixsocket

import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import fs2.io.file.Files
import fs2.io.file.Path
import fs2.io.net.Socket
import fs2.io.net.unixsocket.UnixSocketAddress
import fs2.io.net.unixsocket.UnixSockets
import fs2.io.uring.unsafe.syssocket._
import fs2.io.uring.unsafe.sysun._
import fs2.io.uring.unsafe.sysunOps._
import fs2.io.uring.unsafe.uringOps._
import fs2.io.uring.unsafe.util._

import scala.scalanative.libc.errno._
import scala.scalanative.posix.sys.socket.{bind => _, _}
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private[net] final class UringUnixSockets[F[_]: Files: LiftIO](implicit F: Async[F])
    extends UnixSockets[F] {

  def client(address: UnixSocketAddress): Resource[F, Socket[F]] =
    Resource.eval(Uring.get[F]).flatMap { implicit ring =>
      openSocket(ring).flatMap { fd =>
        Resource.eval {
          toSockaddrUn(address.path).use { addr =>
            ring.call(io_uring_prep_connect(_, fd, addr, sizeof[sockaddr_un].toUInt)).to
          }
        } *> UringSocket(ring, fd, null)
      }
    }

  def server(
      address: UnixSocketAddress,
      deleteIfExists: Boolean,
      deleteOnClose: Boolean
  ): Stream[F, Socket[F]] =
    Stream.eval(Uring.get[F]).flatMap { ring =>
      for {

        _ <- Stream.bracket(Files[F].deleteIfExists(Path(address.path)).whenA(deleteIfExists)) {
          _ => Files[F].deleteIfExists(Path(address.path)).whenA(deleteOnClose)
        }

        fd <- Stream.resource(openSocket(ring))

        _ <- Stream.eval {
          val bindF = toSockaddrUn(address.path).use { addr =>
            F.delay[F[Unit]] {
              if (bind(fd, addr, sizeof[sockaddr_un].toUInt) == 0)
                F.unit
              else
                F.raiseError(IOExceptionHelper(errno))
            }.flatten
          }

          val listenF = F.delay {
            if (listen(fd, 65535) == 0)
              F.unit
            else
              F.raiseError(IOExceptionHelper(errno))
          }.flatten

          bindF *> listenF
        }

        socket <- Stream
          .resource {
            val accept = ring
              .bracket(io_uring_prep_accept(_, fd, null, null, 0))(closeSocket(ring, _))
              .mapK(LiftIO.liftK)
            accept
              .flatMap(UringSocket(ring, _, null))
              .attempt
              .map(_.toOption)
          }
          .repeat
          .unNone

      } yield socket
    }

  private def toSockaddrUn(path: String): Resource[F, Ptr[sockaddr]] =
    Resource
      .make(F.delay(new Array[Byte](sizeof[sockaddr_un].toInt)))(_.pure.void)
      .evalMap[Ptr[sockaddr]] { alloc =>
        val pathBytes = path.getBytes
        if (pathBytes.length > 107)
          F.raiseError(new IllegalArgumentException(s"Path too long: $path"))
        else
          F.delay {
            val addr = alloc.at(0).asInstanceOf[Ptr[sockaddr_un]]
            addr.sun_family = AF_UNIX.toUShort
            toPtr(pathBytes, addr.sun_path.at(0))
            addr.asInstanceOf[Ptr[sockaddr]]
          }
      }

  private def openSocket(ring: Uring): Resource[F, Int] =
    ring
      .bracket(io_uring_prep_socket(_, AF_UNIX, SOCK_STREAM, 0, 0.toUInt))(closeSocket(ring, _))
      .mapK(LiftIO.liftK)

  private def closeSocket(ring: Uring, fd: Int): IO[Unit] =
    ring.call(io_uring_prep_close(_, fd)).void

}

object UringUnixSockets {

  def apply[F[_]: Async: Files: LiftIO]: UringUnixSockets[F] = new UringUnixSockets

}
