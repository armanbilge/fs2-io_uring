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
import io.netty.incubator.channel.uring.UringSockaddrIn._
import io.netty.incubator.channel.uring.UringLinuxSocket
import io.netty.incubator.channel.uring.NativeAccess._

import java.net.InetSocketAddress

private final class UringSocketGroup[F[_]: LiftIO](implicit F: Async[F], dns: Dns[F])
    extends SocketGroup[F] {

  private[this] def createBufferAux(isIpv6: Boolean): Resource[F, ByteBuf] =
    if (isIpv6) createBuffer(SIZEOF_SOCKADDR_IN6) else createBuffer(SIZEOF_SOCKADDR_IN)

  def client(to: SocketAddress[Host], options: List[SocketOption]): Resource[F, Socket[F]] =
    for {
      ring <- Resource.eval(Uring.get[F])
      address <- Resource.eval(to.resolve)
      isIpv6 = address.host.isInstanceOf[Ipv6Address]
      linuxSocket <- openSocket(ring, isIpv6)
      _ <- Resource.eval(
        createBufferAux(isIpv6).use { buf => // Write address in the buffer and call connect
          for {
            length <- F.delay(write(isIpv6, buf.memoryAddress(), address.toInetSocketAddress))
            _ <- F.delay(
              println(
                s"[CLIENT] address: ${address.toString()}, buffer: ${buf.toString()}, length: $length"
              )
            )
            _ <- F.delay(println(s"[CLIENT] LinuxSocket fd: ${linuxSocket.fd()}"))
            _ <- ring
              .call(
                op = IORING_OP_CONNECT,
                fd = linuxSocket.fd(),
                bufferAddress = buf.memoryAddress(),
                offset = length.toLong
              )
              .to
          } yield ()
        }
      )
      _ <- Resource.eval(F.delay(println("[CLIENT] connecting...")))
      socket <- UringSocket(ring, linuxSocket, linuxSocket.fd(), address)
    } yield socket

  def server(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Stream[F, Socket[F]] = Stream.resource(serverResource(address, port, options)).flatMap(_._2)

  private[this] def readIpv(memory: Long, isIpv6: Boolean): InetSocketAddress =
    if (isIpv6)
      readIPv6(memory, new Array[Byte](IPV6_ADDRESS_LENGTH), new Array[Byte](IPV4_ADDRESS_LENGTH))
    else readIPv4(memory, new Array[Byte](IPV4_ADDRESS_LENGTH))

  def serverResource(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] = for {
    ring <- Resource.eval(Uring.get[F])
    resolvedAddress <- Resource.eval(address.fold(IpAddress.loopback)(_.resolve))
    _ <- Resource.eval(F.delay(println(s"[SERVER] Resolved Address: $resolvedAddress")))
    isIpv6 = resolvedAddress.isInstanceOf[Ipv6Address]
    linuxSocket <- openSocket(ring, isIpv6)
    _ <- Resource.eval(F.delay(println(s"[SERVER] LinusSocketFD: ${linuxSocket.fd()}")))
    _ <- Resource.eval(
      F.delay(
        linuxSocket.bind(
          new InetSocketAddress(resolvedAddress.toString, port.getOrElse(port"0").value)
        )
      )
    )
    _ <- Resource.eval(F.delay(linuxSocket.listen(65535)))
    localAddress <- Resource.eval(
      F.delay(SocketAddress.fromInetSocketAddress(linuxSocket.getLocalAddress()))
    )
    _ <- Resource.eval(F.delay(println(s"[SERVER] Local Address: $localAddress")))
    sockets = for {
      buf <- Stream.resource(createBufferAux(isIpv6))
      bufLength <- Stream.resource(createBuffer(4))
      res <- Stream.resource {
        bufLength.writeInt(buf.capacity())

        // Accept a connection, write the remote address on the buf and get the clientFd
        val accept: Resource[F, Int] =
          Resource.eval(F.delay(println("[SERVER] accepting connection..."))) *>
            ring
              .bracket(
                op = IORING_OP_ACCEPT,
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

        // Read the address from the buf and convert it to SocketAddress
        val convert: F[SocketAddress[IpAddress]] =
          F.delay(
            println(
              "[SERVER] getting the address in memory and converting it to SocketAddress..."
            )
          ) *>
            F.delay(
              SocketAddress.fromInetSocketAddress(readIpv(buf.memoryAddress(), isIpv6))
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
    } yield res

  } yield (localAddress, sockets.unNone)


  private[this] def openSocket(
      ring: Uring,
      ipv6: Boolean
  ): Resource[F, UringLinuxSocket] =
    Resource.make[F, UringLinuxSocket](F.delay(UringLinuxSocket.newSocketStream(ipv6)))(
      linuxSocket => closeSocket(ring, linuxSocket.fd()).to
    )

  private[this] def uringOpenSocket(ring: Uring, ipv6: Boolean): Resource[F, Int] = {
    val domain = if (ipv6) AF_INET6 else AF_INET
    ring
      .bracket(op = IORING_OP_SOCKET, fd = domain, length = 0, offset = SOCK_STREAM)(
        closeSocket(ring, _)
      )
      .mapK(LiftIO.liftK)
  }

  private[this] def closeSocket(ring: Uring, fd: Int): IO[Unit] =
    ring.call(op = IORING_OP_CLOSE, fd = fd).void

}

object UringSocketGroup {
  def apply[F[_]: Async: Dns: LiftIO]: SocketGroup[F] = new UringSocketGroup
}
