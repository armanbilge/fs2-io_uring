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

import com.comcast.ip4s.Dns
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import com.comcast.ip4s.IpAddress

import fs2.Stream
import fs2.io.net.Network
import fs2.io.net.SocketOption
import fs2.io.net.tls.TLSContext
import fs2.io.net.SocketGroup
import fs2.io.net.Socket
import fs2.io.net.DatagramSocket
import fs2.io.net.DatagramSocketGroup
import fs2.io.net.DatagramSocketOption

import java.net.ProtocolFamily

import java.util.concurrent.ThreadFactory
import org.typelevel.log4cats.Logger

private[net] final class UringNetwork[F[_]](
    sg: UringSocketGroup[F],
    dsg: UringDatagramSocketGroup[F],
    val tlsContext: TLSContext.Builder[F]
) extends Network.UnsealedNetwork[F] {

  def socketGroup(threadCount: Int, threadFactory: ThreadFactory): Resource[F, SocketGroup[F]] =
    Resource.pure[F, SocketGroup[F]](sg)

  def datagramSocketGroup(threadFactory: ThreadFactory): Resource[F, DatagramSocketGroup[F]] =
    Resource.pure[F, DatagramSocketGroup[F]](dsg)

  def client(to: SocketAddress[Host], options: List[SocketOption]): Resource[F, Socket[F]] =
    sg.client(to, options)

  def server(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Stream[F, Socket[F]] = sg.server(address, port, options)

  def serverResource(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
    sg.serverResource(address, port, options)

  def openDatagramSocket(
      address: Option[Host],
      port: Option[Port],
      options: List[DatagramSocketOption],
      protocolFamily: Option[ProtocolFamily]
  ): Resource[F, DatagramSocket[F]] =
    dsg.openDatagramSocket(address, port, options, protocolFamily)
}

object UringNetwork {
  def apply[F[_]: Async: Dns: LiftIO: Logger]: Network[F] =
    new UringNetwork(
      new UringSocketGroup[F],
      new UringDatagramSocketGroup[F],
      TLSContext.Builder.forAsync[F]
    )
}
