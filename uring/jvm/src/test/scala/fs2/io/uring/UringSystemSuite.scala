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

import fs2.io.uring.UringSuite

class UringSystemSuit extends UringSuite {

  test("submission") {
    Uring
      .get[IO]
      .flatMap { ring =>
        ring.call { sqe =>
          sqe.submit()
          ()
        }
      }
      .assertEquals(0.toLong)
  }

  test("successful submission") {}

  test("failed submission") {}

  test("polling without completions and no timeout") {}

  test("polling with timeout and completions") {}

}
