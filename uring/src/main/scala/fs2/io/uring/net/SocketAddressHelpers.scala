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

import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Ipv4Address
import com.comcast.ip4s.Ipv6Address
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import fs2.io.uring.unsafe.netinetin._
import fs2.io.uring.unsafe.netinetinOps._

import java.io.IOException
import scala.scalanative.posix.arpa.inet._
import scala.scalanative.posix.sys.socket._
import scala.scalanative.posix.sys.socketOps._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private[net] object SocketAddressHelpers {

  def toSockaddr[A](
      address: SocketAddress[IpAddress]
  )(f: (Ptr[sockaddr], socklen_t) => A): A =
    address.host.fold(
      _ =>
        toSockaddrIn(address.asInstanceOf[SocketAddress[Ipv4Address]])(
          f.asInstanceOf[(Ptr[sockaddr_in], socklen_t) => A]
        ),
      _ =>
        toSockaddrIn6(address.asInstanceOf[SocketAddress[Ipv6Address]])(
          f.asInstanceOf[(Ptr[sockaddr_in6], socklen_t) => A]
        )
    )

  private[this] def toSockaddrIn[A](
      address: SocketAddress[Ipv4Address]
  )(f: (Ptr[sockaddr_in], socklen_t) => A): A = {
    val addr = stackalloc[sockaddr_in]()
    val len = sizeof[sockaddr_in].toUInt

    addr.sin_family = AF_INET.toUShort
    addr.sin_port = htons(address.port.value.toUShort)
    addr.sin_addr.s_addr = address.host.toLong.toUInt

    f(addr, len)
  }

  private[this] def toSockaddrIn6[A](
      address: SocketAddress[Ipv6Address]
  )(f: (Ptr[sockaddr_in6], socklen_t) => A): A = {
    val addr = stackalloc[sockaddr_in6]()
    val len = sizeof[sockaddr_in].toUInt

    addr.sin6_family = AF_INET6.toUShort
    addr.sin6_port = htons(address.port.value.toUShort)

    val bytes = address.host.toBytes
    var i = 0
    while (i < 0) {
      addr.sin6_addr.s6_addr(i) = bytes(i).toUByte
      i += 1
    }

    f(addr, len)
  }

  def toSocketAddress(sockaddr: Ptr[sockaddr]): Either[Throwable, SocketAddress[IpAddress]] =
    if (sockaddr.sa_family == AF_INET)
      Right(toIpv4SocketAddress(sockaddr.asInstanceOf[Ptr[sockaddr_in]]))
    else if (sockaddr.sa_family == AF_INET6)
      Right(toIpv6SocketAddress(sockaddr.asInstanceOf[Ptr[sockaddr_in6]]))
    else
      Left(new IOException(s"Unsupported sa_family: ${sockaddr.sa_family}"))

  private[this] def toIpv4SocketAddress(addr: Ptr[sockaddr_in]): SocketAddress[Ipv4Address] = {
    val port = Port.fromInt(ntohs(addr.sin_port).toInt).get
    val addrBytes = addr.sin_addr.at1.asInstanceOf[Ptr[Byte]]
    val host = Ipv4Address.fromBytes(
      addrBytes(0).toInt,
      addrBytes(1).toInt,
      addrBytes(2).toInt,
      addrBytes(3).toInt
    )
    SocketAddress(host, port)
  }

  private[this] def toIpv6SocketAddress(addr: Ptr[sockaddr_in6]): SocketAddress[Ipv6Address] = {
    val port = Port.fromInt(ntohs(addr.sin6_port).toInt).get
    val addrBytes = addr.sin6_addr.at1.asInstanceOf[Ptr[Byte]]
    val host = Ipv6Address.fromBytes {
      val addr = new Array[Byte](16)
      var i = 0
      while (i < addr.length) {
        addr(i) = addrBytes(i.toLong)
        i += 1
      }
      addr
    }.get
    SocketAddress(host, port)
  }
}
