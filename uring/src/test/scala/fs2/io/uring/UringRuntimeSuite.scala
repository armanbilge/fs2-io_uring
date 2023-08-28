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
import fs2.io.uring.unsafe.UringRuntime
import fs2.io.uring.unsafe.UringExecutorScheduler
import fs2.io.uring.unsafe.uringOps._

import scala.concurrent.duration._

class UringRuntimeSuite extends UringSuite {

  test("installs globally") {
    assert(UringRuntime.global.compute.isInstanceOf[UringExecutorScheduler])
  }

  test("ceding") {
    val result = IO.ref[List[String]](Nil).flatMap { ref =>
      def go(s: String) = (ref.getAndUpdate(s :: _) *> IO.cede).replicateA_(3)
      IO.both(go("ping"), go("pong")) *> ref.get
    }

    result.assertEquals(List("pong", "ping", "pong", "ping", "pong", "ping"))
  }

  test("scheduling") {
    val result = IO.ref[List[FiniteDuration]](Nil).flatMap { ref =>
      def go(d: FiniteDuration) = IO.sleep(d) *> ref.getAndUpdate(d :: _) *> IO.cede
      IO.both(go(500.millis), IO.both(go(1.second), go(100.millis))) *> ref.get
    }

    result.assertEquals(List(1.second, 500.millis, 100.millis))
  }

  test("submission") {
    Uring[IO]
      .flatMap { ring =>
        ring.call(io_uring_prep_nop(_))
      }
      .assertEquals(0)
  }

}
