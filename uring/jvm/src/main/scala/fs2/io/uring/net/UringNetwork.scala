// package fs2.io.uring.net

// import fs2.io.net.Network
// import fs2.io.net.tls.TLSContext
// import cats.effect.kernel.Resource
// import com.comcast.ip4s.{Host, Port}
// import fs2.io.net.{DatagramSocket, DatagramSocketGroup}
// import fs2.io.net.DatagramSocketOption
// import com.comcast.ip4s.{Host, Port}
// import fs2.io.net.{Socket, SocketOption}
// import cats.effect.kernel.Resource
// import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
// import fs2.io.net.{Socket, SocketOption}
// import cats.effect.kernel.Resource
// import fs2.io.net.SocketGroup
// import java.util.concurrent.ThreadFactory
// import cats.effect.kernel.Resource
// import fs2.io.net.DatagramSocketGroup
// import java.util.concurrent.ThreadFactory
// import cats.effect.kernel.Resource
// import com.comcast.ip4s.{Host, SocketAddress}
// import fs2.io.net.{Socket, SocketOption}

// private[net] final class UringNetwork[F[_]]() extends Network.UnsealedNetwork[F] {

//   override def socketGroup(
//       threadCount: Int,
//       threadFactory: ThreadFactory
//   ): Resource[F, SocketGroup[F]] = ???

//   override def datagramSocketGroup(
//       threadFactory: ThreadFactory
//   ): Resource[F, DatagramSocketGroup[F]] = ???

//   override def client(
//       to: SocketAddress[Host],
//       options: List[SocketOption]
//   ): Resource[F, Socket[F]] = ???

//   override def server(
//       address: Option[Host],
//       port: Option[Port],
//       options: List[SocketOption]
//   ): fs2.Stream[F, Socket[F]] = ???

//   override def serverResource(
//       address: Option[Host],
//       port: Option[Port],
//       options: List[SocketOption]
//   ): Resource[F, (SocketAddress[IpAddress], fs2.Stream[F, Socket[F]])] = ???

//   override def openDatagramSocket(
//       address: Option[Host],
//       port: Option[Port],
//       options: List[DatagramSocketOption],
//       protocolFamily: Option[DatagramSocketGroup.ProtocolFamily]
//   ): Resource[F, DatagramSocket[F]] = ???

//   override def tlsContext: TLSContext.Builder[F] = ???

// }
