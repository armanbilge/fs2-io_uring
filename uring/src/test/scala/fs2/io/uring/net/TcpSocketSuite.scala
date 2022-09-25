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

import cats.effect.IO
import com.comcast.ip4s._
import fs2.text._

class TcpSocketSuite extends UringSuite {

  val sg = UringSocketGroup[IO]

  test("postman echo") {
    sg.client(SocketAddress(host"postman-echo.com", port"80")).use { socket =>
      val msg =
        """|GET /get HTTP/1.1
           |Host: postman-echo.com
           |
           |""".stripMargin

      val writeRead = Stream(msg)
        .through(utf8.encode[IO])
        .through(socket.writes) ++
        socket.reads
          .through(utf8.decode[IO])
          .through(lines)
          .head

      writeRead.compile.lastOrError
        .assertEquals("HTTP/1.1 200 OK")
    }
  }

  val setup = for {
    serverSetup <- sg.serverResource(address = Some(ip"127.0.0.1"))
    (bindAddress, server) = serverSetup
    clients = Stream.resource(sg.client(bindAddress)).repeat
  } yield server -> clients

  test("echo requests - each concurrent client gets back what it sent") {
    val message = Chunk.array("fs2.rocks".getBytes)
    val clientCount = 20L

    Stream
      .resource(setup)
      .flatMap { case (server, clients) =>
        val echoServer = server.map { socket =>
          socket.reads
            .through(socket.writes)
            .onFinalize(socket.endOfOutput)
        }.parJoinUnbounded

        val msgClients = clients
          .take(clientCount)
          .map { socket =>
            Stream
              .chunk(message)
              .through(socket.writes)
              .onFinalize(socket.endOfOutput) ++
              socket.reads.chunks
                .map(bytes => new String(bytes.toArray))
          }
          .parJoin(10)
          .take(clientCount)

        msgClients.concurrently(echoServer)
      }
      .compile
      .toVector
      .map { it =>
        assertEquals(it.size.toLong, clientCount)
        assert(it.forall(_ == "fs2.rocks"))
      }
  }

  test("readN yields chunks of the requested size") {
    val message = Chunk.array("123456789012345678901234567890".getBytes)
    val sizes = Vector(1, 2, 3, 4, 3, 2, 1)

    Stream
      .resource(setup)
      .flatMap { case (server, clients) =>
        val junkServer = server.map { socket =>
          Stream
            .chunk(message)
            .through(socket.writes)
            .onFinalize(socket.endOfOutput)
        }.parJoinUnbounded

        val client =
          clients
            .take(1)
            .flatMap { socket =>
              Stream
                .emits(sizes)
                .evalMap(socket.readN(_))
                .map(_.size)
            }
            .take(sizes.length.toLong)

        client.concurrently(junkServer)
      }
      .compile
      .toVector
      .assertEquals(sizes)
  }

  test("write - concurrent calls do not cause a WritePendingException") {
    val message = Chunk.array(("123456789012345678901234567890" * 10000).getBytes)

    Stream
      .resource(setup)
      .flatMap { case (server, clients) =>
        val readOnlyServer = server.map(_.reads).parJoinUnbounded
        val client =
          clients.take(1).flatMap { socket =>
            // concurrent writes
            Stream {
              Stream.eval(socket.write(message)).repeatN(10L)
            }.repeatN(2L).parJoinUnbounded
          }

        client.concurrently(readOnlyServer)
      }
      .compile
      .drain
  }

}
