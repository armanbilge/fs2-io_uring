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

import io.netty.buffer.ByteBuf
import io.netty.incubator.channel.uring.UringSockaddrIn
import io.netty.incubator.channel.uring.UringLinuxSocket
import io.netty.incubator.channel.uring.NativeAccess.SIZEOF_SOCKADDR_IN
import io.netty.incubator.channel.uring.NativeAccess.SIZEOF_SOCKADDR_IN6
import io.netty.incubator.channel.uring.NativeAccess.SOCK_NONBLOCK

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

              println(s"[CLIENT] LinuxSocket fd: ${linuxSocket.fd()}")

              ring
                .call(
                  op = IORING_OP_CONNECT,
                  fd = linuxSocket.fd(),
                  bufferAddress = buf.memoryAddress(),
                  offset = length.toLong
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

  // TODO: Replace 4 with SIZEOF_SOCKADDR_IN and 16 with SIZEOF_SOCKADDR_IN6
  private[this] def readIpv(memory: Long, isIpv6: Boolean): InetSocketAddress =
    if (isIpv6) UringSockaddrIn.readIPv6(memory, new Array[Byte](16), new Array[Byte](4))
    else UringSockaddrIn.readIPv4(memory, new Array[Byte](4))

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
          .flatMap { buf => // Buffer that will contain the remote address
            Stream
              .resource(createBuffer(4)) // TODO: Replace 4 with INT_SIZE ?
              .flatMap {
                bufLength => // ACCEPT_OP needs a pointer to a buffer containing the size of the first buffer
                  Stream.resource {

                    bufLength.writeInt(buf.capacity())

                    // We accept a connection, we write the remote address on the buf and we get the clientFd
                    val accept =
                      Resource.eval(F.delay(println("[SERVER] accepting connection..."))) *>
                        ring
                          .bracket(
                            op = IORING_OP_ACCEPT,
                            rwFlags = SOCK_NONBLOCK,
                            fd = linuxSocket.fd(),
                            bufferAddress = buf.memoryAddress(),
                            offset = bufLength.memoryAddress()
                          )(closeSocket(ring, _))
                          .mapK {
                            new cats.~>[IO, IO] {
                              def apply[A](ioa: IO[A]) = ioa.debug()
                            }
                          }
                          .mapK(LiftIO.liftK)

                    // We read the address from the buf and we convert it to SocketAddress
                    val convert: F[SocketAddress[IpAddress]] =
                      F.delay(
                        println(
                          "[SERVER] getting the address in memory and converting it to SocketAddress..."
                        )
                      ) *>
                        /* TODO: Merge the next two steps in one:  F.delay (SocketAddress.fromInetSocketAddress(readIpv(buf.memoryAddress(), isIpv6))) */
                        F.delay {
                          val netRemoteAddress: InetSocketAddress =
                            readIpv(buf.memoryAddress(), isIpv6)
                          println(
                            s"[SERVER] Read IP address from buffer: ${netRemoteAddress.getHostString()}"
                          )
                          netRemoteAddress
                        }.flatMap { netRemoteAddress =>
                          F.delay {
                            println(
                              s"[SERVER] converted and found inetSocketAddress: $netRemoteAddress"
                            )
                            SocketAddress.fromInetSocketAddress(netRemoteAddress)
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

  private[this] def openSocket(
      ring: Uring,
      ipv6: Boolean
  ): Resource[F, UringLinuxSocket] =
    Resource.make[F, UringLinuxSocket](F.delay(UringLinuxSocket.newSocketStream(ipv6)))(
      linuxSocket => closeSocket(ring, linuxSocket.fd()).to
    )

  private[this] def closeSocket(ring: Uring, fd: Int): IO[Unit] =
    ring.call(op = IORING_OP_CLOSE, fd = fd).void

}

object UringSocketGroup {
  def apply[F[_]: Async: Dns: LiftIO]: SocketGroup[F] = new UringSocketGroup
}
