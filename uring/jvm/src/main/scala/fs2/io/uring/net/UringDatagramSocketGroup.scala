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
import fs2.io.net.DatagramSocketGroup
import fs2.io.net.DatagramSocket
import fs2.io.net._

import fs2.io.uring.Uring
import fs2.io.uring.unsafe.util.OP._

import java.net.ProtocolFamily

private final class UringDatagramSocketGroup[F[_]: LiftIO](implicit F: Async[F], dns: Dns[F])
    extends DatagramSocketGroup[F] {

  override def openDatagramSocket(
      address: Option[Host],
      port: Option[Port],
      options: List[DatagramSocketOption],
      protocolFamily: Option[ProtocolFamily]
  ): Resource[F, DatagramSocket[F]] = ???

}

object UringDatagramSocketGroup {
  def apply[F[_]: Async: Dns: LiftIO]: DatagramSocketGroup[F] = new UringDatagramSocketGroup
}
