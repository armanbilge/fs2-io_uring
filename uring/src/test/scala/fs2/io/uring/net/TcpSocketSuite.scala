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

}
