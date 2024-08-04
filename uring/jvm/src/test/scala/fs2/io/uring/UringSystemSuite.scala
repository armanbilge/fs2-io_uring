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

package fs2.io.uring

import cats.effect.IO
import cats.syntax.parallel._

import fs2.io.uring.unsafe.util.OP._

class UringSystemSuite extends UringSuite {

  val debug = false

  test("Create a ring") {
    val test = for {
      _ <- Uring.get[IO]
      _ <- IO.whenA(debug)(IO.println("[TEST] We got the ring!"))
    } yield ()

    test.assertEquals(())
  }

  test("submission") {
    val test = for {
      ring <- Uring.get[IO]
      _ <- IO.whenA(debug)(IO.println("[TEST] We got the ring!"))
      res <- {
        val op: Byte = IORING_OP_NOP
        val flags: Int = 0
        val rwFlags: Int = 0
        val fd: Int = -1
        val bufferAddress: Long = 0
        val length: Int = 0
        val offset: Long = 0

        ring.call(op, flags, rwFlags, fd, bufferAddress, length, offset)
      }
    } yield res

    test.assertEquals(0)
  }

  test("Parallel submission") {

    val op: Byte = IORING_OP_NOP
    val flags: Int = 0
    val rwFlags: Int = 0
    val fd: Int = -1
    val bufferAddress: Long = 0
    val length: Int = 0
    val offset: Long = 0

    val calls: List[IO[Int]] = List.fill(100)(
      Uring
        .get[IO]
        .flatMap { ring =>
          ring.call(op, flags, rwFlags, fd, bufferAddress, length, offset)
        }
    )

    val test: IO[List[Int]] = calls.parSequence

    val list = for {
      results <- test
      _ <- IO.whenA(debug)(IO.println(results))
      _ <- IO.whenA(debug)(IO.println(results.size))
    } yield results

    list.map(results => assert(results.forall(_ >= 0)))
  }

  test("Multiple parallel submission") {
    val op: Byte = IORING_OP_NOP
    val flags: Int = 0
    val rwFlags: Int = 0
    val fd: Int = -1
    val bufferAddress: Long = 0
    val length: Int = 0
    val offset: Long = 0

    val calls: List[IO[List[Int]]] = List.fill(100)(
      Uring.get[IO].flatMap { ring =>
        ring.call(op, flags, rwFlags, fd, bufferAddress, length, offset).replicateA(50)
      }
    )

    val test: IO[List[List[Int]]] = calls.parSequence

    val list = for {
      listOfList <- test
      listFlatten <- IO(listOfList.flatten)
      _ <- IO.whenA(debug)(IO.println(listFlatten))
      _ <- IO.whenA(debug)(IO.println(listFlatten.size))
    } yield listFlatten

    list.map(results => assert(results.forall(_ >= 0)))
  }
}
