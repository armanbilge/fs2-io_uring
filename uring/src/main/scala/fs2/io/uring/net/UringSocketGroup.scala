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

import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import com.comcast.ip4s._
import fs2.io.net.Socket
import fs2.io.net.SocketGroup
import fs2.io.net.SocketOption
import fs2.io.uring.unsafe.netinetin._
import fs2.io.uring.unsafe.uringOps._

import scala.scalanative.libc.errno._
import scala.scalanative.posix.sys.socket._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private final class UringSocketGroup[F[_]: LiftIO](implicit F: Async[F], dns: Dns[F])
    extends SocketGroup[F] {

  def client(to: SocketAddress[Host], options: List[SocketOption]): Resource[F, Socket[F]] =
    Resource.eval(Uring.get[F]).flatMap { ring =>
      Resource.eval(to.resolve).flatMap { address =>
        openSocket(ring, address.host.isInstanceOf[Ipv4Address]).flatMap { fd =>
          Resource.eval {
            SocketAddressHelpers.allocateSockaddr.use { case (addr, len) =>
              F.delay(SocketAddressHelpers.toSockaddr(address, addr, len)) *>
                ring.call(io_uring_prep_connect(_, fd, addr, !len)).to
            }
          } *> UringSocket(ring, fd, address)
        }
      }
    }

  def server(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Stream[F, Socket[F]] = Stream.resource(serverResource(address, port, options)).flatMap(_._2)

  def serverResource(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
    Resource.eval(Uring.get[F]).flatMap { ring =>
      for {

        resolvedAddress <- Resource.eval(address.fold(IpAddress.loopback)(_.resolve))

        fd <- openSocket(ring, resolvedAddress.isInstanceOf[Ipv4Address])

        localAddress <- Resource.eval {
          val bindF = F.delay {
            val socketAddress = SocketAddress(resolvedAddress, port.getOrElse(port"0"))

            if (SocketAddressHelpers.toSockaddr(socketAddress)(bind(fd, _, _)) == 0)
              F.unit
            else
              F.raiseError(IOExceptionHelper(errno))
          }.flatten

          val listenF = F.delay {
            if (listen(fd, 65535) == 0)
              F.unit
            else
              F.raiseError(IOExceptionHelper(errno))
          }.flatten

          bindF *> listenF *> UringSocket.getLocalAddress(fd)
        }

        sockets = Stream
          .resource(SocketAddressHelpers.allocateSockaddr)
          .flatMap { case (addr, len) =>
            Stream.resource {
              val accept = Resource.eval(F.delay(!len = sizeof[sockaddr_in6].toUInt)) *>
                ring
                  .bracket(io_uring_prep_accept(_, fd, addr, len, 0))(closeSocket(ring, _))
                  .mapK(LiftIO.liftK)

              val convert =
                F.delay(SocketAddressHelpers.toSocketAddress(addr))
                  .flatMap(_.liftTo)

              accept
                .flatMap { clientFd =>
                  Resource.eval(convert).flatMap { remoteAddress =>
                    UringSocket(ring, clientFd, remoteAddress)
                  }
                }
                .attempt
                .map(_.toOption)
            }.repeat
          }

      } yield (localAddress, sockets.unNone)
    }

  private def openSocket(ring: Uring, ipv4: Boolean): Resource[F, Int] =
    ring
      .bracket { sqe =>
        val domain = if (ipv4) AF_INET else AF_INET6
        io_uring_prep_socket(sqe, domain, SOCK_STREAM, 0, 0.toUInt)
      }(closeSocket(ring, _))
      .mapK(LiftIO.liftK)

  private def closeSocket(ring: Uring, fd: Int): IO[Unit] =
    ring.call(io_uring_prep_close(_, fd)).void

}

object UringSocketGroup {

  def apply[F[_]: Async: Dns: LiftIO]: SocketGroup[F] = new UringSocketGroup

}
