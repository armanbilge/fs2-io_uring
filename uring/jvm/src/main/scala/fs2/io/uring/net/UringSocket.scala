// package fs2.io.uring.net

// import cats.effect.LiftIO
// import cats.effect.std.Mutex
// import cats.effect.kernel.Async

// import fs2.io.uring.Uring

// import com.comcast.ip4s.SocketAddress
// import com.comcast.ip4s.IpAddress
// import fs2.io.net.Socket

// private[net] final class UringSocket[F[_]: LiftIO](
//     ring: Uring,
//     fd: Int,
//     remoteAddress: SocketAddress[IpAddress],
//     buffer: Array[F[_]],
//     readMutex: Mutex[F],
//     writeMutex: Mutex[F]
// )(implicit F: Async[F])
//     extends Socket[F] {}
