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
