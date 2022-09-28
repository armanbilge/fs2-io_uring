package fs2
package io.uring
package net
package unixsocket

import cats.effect._
import fs2.io.net.unixsocket.UnixSocketAddress
import scala.concurrent.duration._

class UnixSocketsSuite extends UringSuite {

  test("echoes") {
    val address = UnixSocketAddress("fs2-unix-sockets-test.sock")

    val server = UringUnixSockets[IO]
      .server(address)
      .map { client =>
        client.reads.through(client.writes)
      }
      .parJoinUnbounded

    def client(msg: Chunk[Byte]) = UringUnixSockets[IO].client(address).use { server =>
      server.write(msg) *> server.endOfOutput *> server.reads.compile
        .to(Chunk)
        .map(read => assertEquals(read, msg))
    }

    val clients = (0 until 100).map(b => client(Chunk.singleton(b.toByte)))

    (Stream.sleep_[IO](1.second) ++ Stream.emits(clients).evalMap(identity))
      .concurrently(server)
      .compile
      .drain
  }

}
