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
import com.comcast.ip4s.Dns
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import fs2.io.net.Network
import fs2.io.net.SocketOption
import fs2.io.net.tls.TLSContext

private[net] final class UringNetwork[F[_]](
    sg: UringSocketGroup[F],
    val tlsContext: TLSContext.Builder[F]
) extends Network.UnsealedNetwork[F] {

  def client(to: SocketAddress[Host], options: List[SocketOption]) =
    sg.client(to, options)

  def server(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ) = sg.server(address, port, options)

  def serverResource(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ) = sg.serverResource(address, port, options)

}

object UringNetwork {
  def apply[F[_]: Async: Dns: LiftIO]: Network[F] =
    new UringNetwork(new UringSocketGroup[F], TLSContext.Builder.forAsync[F])
}
