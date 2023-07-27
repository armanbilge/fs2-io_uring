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

import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._

import com.comcast.ip4s._

import fs2.Stream
import fs2.io.net.Socket
import fs2.io.net.SocketGroup
import fs2.io.net.SocketOption

import fs2.io.uring.Uring
import fs2.io.uring.unsafe.util.createBuffer
import fs2.io.uring.unsafe.util.OP._

import io.netty.incubator.channel.uring.UringSockaddrIn
import io.netty.incubator.channel.uring.UringLinuxSocket
import io.netty.incubator.channel.uring.NativeAccess.SIZEOF_SOCKADDR_IN
import io.netty.incubator.channel.uring.NativeAccess.SIZEOF_SOCKADDR_IN6

import io.netty.buffer.ByteBuf
import java.net.InetSocketAddress

private final class UringSocketGroup[F[_]: LiftIO](implicit F: Async[F], dns: Dns[F])
    extends SocketGroup[F] {

  private[this] def createBufferAux(isIpv6: Boolean): Resource[F, ByteBuf] =
    if (isIpv6) createBuffer(SIZEOF_SOCKADDR_IN6) else createBuffer(SIZEOF_SOCKADDR_IN)
  def client(to: SocketAddress[Host], options: List[SocketOption]): Resource[F, Socket[F]] =
    Resource.eval(Uring.get[F]).flatMap { ring =>
      Resource.eval(to.resolve).flatMap { address =>
        val isIpv6: Boolean = address.host.isInstanceOf[Ipv6Address]
        openSocket(ring, isIpv6).flatMap { linuxSocket =>
          Resource.eval {
            createBufferAux(isIpv6).use { buf =>
              val length: Int = UringSockaddrIn.write(
                isIpv6,
                buf.memoryAddress(),
                address.toInetSocketAddress
              )

              println(
                s"[CLIENT] address: ${address.toString()}, buffer: ${buf.toString()}, length: $length"
              )
              ring
                .call(
                  IORING_OP_CONNECT,
                  0,
                  0,
                  linuxSocket.fd(),
                  buf.memoryAddress(),
                  0,
                  length.toLong
                )
                .to
            }
          } *> UringSocket(
            ring,
            linuxSocket,
            linuxSocket.fd(),
            address
          )
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

        isIpv6 = resolvedAddress.isInstanceOf[Ipv6Address]

        linuxSocket <- openSocket(ring, isIpv6)

        localAddress <- Resource.eval {
          val bindF = F.delay {
            val socketAddress =
              new InetSocketAddress(resolvedAddress.toString, port.getOrElse(port"0").value)
            linuxSocket.bind(socketAddress)
          }

          val listenF = F.delay(linuxSocket.listen(65535))

          bindF *> listenF *> F
            .delay(SocketAddress.fromInetSocketAddress(linuxSocket.getLocalAddress()))
        }

        sockets = Stream
          .resource(createBufferAux(isIpv6))
          .flatMap { buf =>
            Stream.resource {
              val accept =
                ring
                  .bracket(
                    IORING_OP_ACCEPT,
                    0,
                    0,
                    linuxSocket.fd(),
                    buf.memoryAddress(),
                    0,
                    buf.capacity().toLong
                  )(closeSocket(ring, _))
                  .mapK(LiftIO.liftK)

              val hostAndPort = buf.toString().split(":")
              val host = hostAndPort(0)
              val port = hostAndPort(1).toInt
              val socketAddress = new InetSocketAddress(host, port)

              val convert: F[SocketAddress[IpAddress]] = F
                .delay(
                  SocketAddress.fromInetSocketAddress(socketAddress)
                )

              accept
                .flatMap { clientFd =>
                  Resource.eval(convert).flatMap { remoteAddress =>
                    UringSocket(ring, UringLinuxSocket(clientFd), clientFd, remoteAddress)
                  }
                }
                .attempt
                .map(_.toOption)
            }.repeat
          }

      } yield (localAddress, sockets.unNone)
    }

  private def openSocket(
      ring: Uring,
      ipv6: Boolean
  ): Resource[F, UringLinuxSocket] =
    Resource.make[F, UringLinuxSocket](F.delay(UringLinuxSocket.newSocketStream(ipv6)))(
      linuxSocket => closeSocket(ring, linuxSocket.fd()).to
    )

  private def closeSocket(ring: Uring, fd: Int): IO[Unit] =
    ring.call(op = IORING_OP_CLOSE, fd = fd).void

}

object UringSocketGroup {
  def apply[F[_]: Async: Dns: LiftIO]: SocketGroup[F] = new UringSocketGroup
}
