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

import com.comcast.ip4s._

import fs2.Stream
import fs2.Chunk
import fs2.text._

import fs2.io.uring.UringSuite
import cats.effect.kernel.Resource

import scala.concurrent.duration._
import java.io.IOException
import fs2.io.net.Socket
import java.util.concurrent.TimeoutException

class TcpSocketSuite extends UringSuite {
  val debug = false
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

      val http11 = "HTTP/1.1"
      writeRead.compile.lastOrError.map(_.take(http11.length)).assertEquals(http11)
    }
  }

  test("jsonplaceholder get") {
    sg.client(SocketAddress(host"jsonplaceholder.typicode.com", port"80")).use { socket =>
      val msg =
        """|GET /todos/1 HTTP/1.1
          |Host: jsonplaceholder.typicode.com
          |
          |""".stripMargin

      val writeRead =
        Stream(msg)
          .through(utf8.encode[IO])
          .through(socket.writes) ++
          socket.reads
            .through(utf8.decode[IO])
            .through(lines)
            .take(1)

      writeRead.compile.lastOrError
        .assertEquals("HTTP/1.1 200 OK")
    }
  }

  test("jsonplaceholder post") {
    sg.client(SocketAddress(host"jsonplaceholder.typicode.com", port"80")).use { socket =>
      val msg =
        """|POST /posts HTTP/1.1
         |Host: jsonplaceholder.typicode.com
         |Content-Type: application/json
         |Content-Length: 44
         |
         |{"title": "foo", "body": "bar", "userId": 1}
         |""".stripMargin

      val writeRead =
        Stream(msg)
          .through(utf8.encode[IO])
          .through(socket.writes) ++
          socket.reads
            .through(utf8.decode[IO])
            .through(lines)
            .take(1)

      writeRead.compile.lastOrError
        .assertEquals("HTTP/1.1 201 Created")
    }
  }

  test("invalid address") {
    sg.client(SocketAddress(host"invalid-address", port"80"))
      .use(_ => IO.unit)
      .intercept[IOException]
  }

  test("write after close") {
    sg.client(SocketAddress(host"postman-echo.com", port"80")).use { socket =>
      val msg = "GET /get HTTP/1.1\nHost: postman-echo.com\n\n"

      socket.endOfOutput >>
        Stream(msg)
          .through(utf8.encode[IO])
          .through(socket.writes)
          .compile
          .drain
          .intercept[IOException]
    }
  }

  val serverResource: Resource[IO, (SocketAddress[IpAddress], Stream[IO, Socket[IO]])] =
    sg.serverResource(
      Some(Host.fromString("localhost").get),
      Some(Port.fromInt(0).get),
      Nil
    )

  test("Start server and connect external client") {
    serverResource.use { case (localAddress, _) =>
      for {
        _ <- IO.whenA(debug)(IO.println(s"[TEST] Server started at $localAddress"))
        _ <- sg.client(localAddress).use { socket =>
          for {
            remoteAddress <- socket.remoteAddress
            _ <- IO
              .whenA(debug)(IO.println(s"[TEST] Socket created and connected to $remoteAddress!"))
            _ <- socket.remoteAddress.map(assertEquals(_, localAddress))
          } yield ()
        }
      } yield ()
    }
  }

  test("Create server connect external client and write") {
    serverResource.use { case (localAddress, serverStream) =>
      sg.client(localAddress).use { socket =>
        val msg = "Hello, echo server!\n"

        val write =
          Stream(msg)
            .through(utf8.encode[IO])
            .through(socket.writes)

        val echoServer = serverStream.compile.drain

        IO.whenA(debug)(IO.println("socket created and connection established!")) *>
          echoServer.background.use(_ =>
            write.compile.drain
              *> IO.whenA(debug)(IO.println("message written!"))
          )
      }
    }
  }

  test("local echo server with read and write") {
    serverResource.use { case (localAddress, serverStream) =>
      sg.client(localAddress).use { socket =>
        val msg = "Hello, echo server!\n"

        val writeRead =
          Stream(msg)
            .through(utf8.encode[IO])
            .through(socket.writes) ++
            socket.reads
              .through(utf8.decode[IO])
              .through(lines)
              .head

        val echoServer = serverStream
          .flatMap { socket =>
            socket.reads
              .through(utf8.decode[IO])
              .through(lines)
              .map(line => s"$line\n")
              .through(utf8.encode[IO])
              .through(socket.writes)
          }
          .compile
          .drain

        IO.whenA(debug)(IO.println("socket created and connection established!")) *>
          echoServer.background.use(_ =>
            IO.sleep(
              1.second
            ) *>
              writeRead.compile.lastOrError
                .assertEquals("Hello, echo server!")
          )
      }
    }
  }

  val setup = for {
    serverSetup <- sg.serverResource(address = Some(ip"127.0.0.1"))
    (bindAddress, server) = serverSetup
    _ <- Resource.eval(IO.whenA(debug)(IO.delay(println(s"[TEST] Bind address: $bindAddress"))))
    clients = Stream.resource(sg.client(bindAddress)).repeat
  } yield server -> clients

  val repetitions: Int = 1

  test("echo requests - each concurrent client gets back what it sent") {
    val message = Chunk.array("fs2.rocks".getBytes)
    val clientCount = 20L

    val test: IO[Unit] = Stream
      .resource(setup)
      .flatMap { case (server, clients) =>
        val echoServer = server.map { socket =>
          socket.reads
            .through(socket.writes)
            .onFinalize(socket.endOfOutput)
        }.parJoinUnbounded

        val msgClients = Stream.sleep_[IO](1.second) ++ clients
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

    test.replicateA(repetitions).void
  }

  test("readN yields chunks of the requested size") {
    val message = Chunk.array("123456789012345678901234567890".getBytes)
    val sizes = Vector(1, 2, 3, 4, 3, 2, 1)

    val test: IO[Unit] = Stream
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

    test.replicateA(repetitions).void
  }

  test("readN yields chunks of the requested size with remote disconnection") {
    val message = Chunk.array("123456789012345678901234567890".getBytes)
    val sizes = Vector(1, 2, 3, 4, 3, 2, 1)
    val subsetSize: Long = 15

    val test: IO[Unit] = Stream
      .resource(setup)
      .flatMap { case (server, clients) =>
        val junkServer = server.map { socket =>
          Stream
            .chunk(message)
            .through(socket.writes)
            .take(subsetSize)
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
            .take(subsetSize)

        client.concurrently(junkServer)
      }
      .compile
      .toVector
      .assertEquals(
        sizes.takeWhile(_ <= subsetSize)
      )

    test.replicateA(repetitions).void
  }

  test("write - concurrent calls do not cause a WritePendingException") {
    val message = Chunk.array(("123456789012345678901234567890" * 10000).getBytes)

    val test: IO[Unit] = Stream
      .resource(setup)
      .flatMap { case (server, clients) =>
        val readOnlyServer = server.map(_.reads).parJoinUnbounded
        val client =
          clients.take(1).flatMap { socket =>
            // concurrent writes
            Stream {
              Stream.eval(socket.write(message)).repeatN(10L)
            }.repeatN(1L).parJoinUnbounded
          }

        client.concurrently(readOnlyServer)
      }
      .compile
      .drain

    test.replicateA(repetitions).void
  }

  test("addresses - should match across client and server sockets") {
    val test: IO[Unit] = Stream
      .resource(setup)
      .flatMap { case (server, clients) =>
        val serverSocketAddresses = server.evalMap { socket =>
          socket.endOfOutput *> socket.localAddress.product(socket.remoteAddress)
        }

        val clientSocketAddresses =
          clients
            .take(1)
            .evalMap { socket =>
              socket.endOfOutput *> socket.localAddress.product(socket.remoteAddress)
            }

        serverSocketAddresses.parZip(clientSocketAddresses).map {
          case ((serverLocal, serverRemote), (clientLocal, clientRemote)) =>
            assertEquals(clientRemote, serverLocal)
            assertEquals(clientLocal, serverRemote)
        }

      }
      .compile
      .drain

    test.replicateA(repetitions).void
  }

  // TODO options test

  // TODO decide about "read after timed out read not allowed"

  test("empty write") {
    setup.use { case (_, clients) =>
      clients.take(1).foreach(_.write(Chunk.empty)).compile.drain
    }
  }

  test("accept is cancelable") {
    sg.serverResource().use { case (_, clients) =>
      clients.compile.drain.timeoutTo(100.millis, IO.unit)
    }
  }

  test("endOfOutput / endOfInput ignores ENOTCONN") {
    sg.serverResource().use { case (bindAddress, clients) =>
      sg.client(bindAddress).surround(IO.sleep(100.millis)).background.surround {
        clients
          .take(1)
          .foreach { socket =>
            socket.write(Chunk.array("fs2.rocks".getBytes)) *>
              IO.sleep(1.second) *>
              socket.endOfOutput *> socket.endOfInput
          }
          .compile
          .drain
      }
    }
  }

  test("can shutdown a socket that's pending a read") {
    val warmup = sg.client(SocketAddress(host"postman-echo.com", port"80")).use { socket =>
      val msg =
        """|GET /get HTTP/1.1
            |Host: postman-echo.com
            |
            |""".stripMargin

      val writeRead =
        Stream(msg)
          .through(utf8.encode[IO])
          .through(socket.writes) ++
          socket.reads
            .through(utf8.decode[IO])
            .through(lines)
            .head

      writeRead.compile.lastOrError
        .assertEquals("HTTP/1.1 200 OK")
    }

    val timeout = 2.seconds
    val test = sg.serverResource().use { case (bindAddress, clients) =>
      sg.client(bindAddress).use { _ =>
        clients.head.flatMap(_.reads).compile.drain.timeout(2.seconds).recover {
          case _: TimeoutException => ()
        }
      }
    }

    // also test that timeouts are working correctly
    warmup *> test.timed.flatMap { case (duration, _) =>
      IO(assert(clue(duration) < (timeout + 100.millis)))
    }
  }
}
