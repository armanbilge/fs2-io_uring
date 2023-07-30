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
import io.netty.incubator.channel.uring.NativeAccess

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

              println(s"[CLIENT] LinuxSocket fd: ${linuxSocket.fd()}")

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
          } *>
            Resource.eval(F.delay(println("[CLIENT] connecting..."))).flatMap { _ =>
              UringSocket(
                ring,
                linuxSocket,
                linuxSocket.fd(),
                address
              )
            }
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

        _ <- Resource.eval(F.delay(println(s"[SERVER] Resolved Address: $resolvedAddress")))

        isIpv6 = resolvedAddress.isInstanceOf[Ipv6Address]

        linuxSocket <- openSocket(ring, isIpv6)

        _ <- Resource.eval(F.delay(println(s"[SERVER] LinusSocketFD: ${linuxSocket.fd()}")))

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

        _ <- Resource.eval(F.delay(println(s"[SERVER] Local Address: $localAddress")))

        sockets = Stream
          .resource(createBufferAux(isIpv6))
          .flatMap { buf =>
            Stream
              .resource(createBuffer(4))
              .flatMap { buf2 =>
                Stream.resource {

                  buf2.writeInt(buf.capacity())

                  val accept =
                    Resource.eval(F.delay(println("[SERVER] accepting connection..."))) *>
                      ring
                        .bracket(
                          IORING_OP_ACCEPT,
                          0,
                          NativeAccess.SOCK_NONBLOCK,
                          linuxSocket.fd(),
                          buf.memoryAddress(),
                          0,
                          buf2.memoryAddress()
                        )(closeSocket(ring, _))
                        .mapK {
                          new cats.~>[IO, IO] {
                            def apply[A](ioa: IO[A]) = ioa.debug()
                          }
                        }
                        .mapK(LiftIO.liftK)

                  val convert: F[SocketAddress[IpAddress]] =
                    F.delay(
                      println(
                        "[SERVER] getting the address in memory and converting it to SocketAddress..."
                      )
                    ) *>
                      F.delay {
                        val inetAddress = if (isIpv6) {
                          UringSockaddrIn
                            .readIPv6(buf.memoryAddress(), new Array[Byte](16), new Array[Byte](4))
                        } else {
                          UringSockaddrIn.readIPv4(buf.memoryAddress(), new Array[Byte](4))
                        }
                        println(
                          s"[SERVER] Read IP address from buffer: ${inetAddress.getHostString()}"
                        )
                        new InetSocketAddress(inetAddress.getHostString(), inetAddress.getPort())
                      }.flatMap { inetSocketAddress =>
                        F.delay {
                          println(
                            s"[SERVER] converted and found inetSocketAddress: $inetSocketAddress"
                          )
                          SocketAddress.fromInetSocketAddress(inetSocketAddress)
                        }
                      }

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
