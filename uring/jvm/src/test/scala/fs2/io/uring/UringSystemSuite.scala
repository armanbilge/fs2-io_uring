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
import fs2.io.uring.unsafe.uring._

import io.netty.incubator.channel.uring.UringSubmissionQueue

class UringSystemSuite extends UringSuite {

  test("successful submission") {
    val buffer = ByteBuffer.allocate(256)
    buffer.put("Hello, Uring!".getBytes)
    
    var submissionSuccessful = false

    // Perform the operation
    val result: IO[Int] = Uring.get[IO].flatMap { ring =>
      ring.call { submissionQueue =>
        val fd = 0 //
        val bufferAddress = buffer.array()
        val pos = 0
        val limit = buffer.remaining()
        val extraData: Short = 0
        
        if (submissionQueue.addWrite(fd, bufferAddress, pos, limit, extraData)) {
          submissionQueue.submit()
          submissionSuccessful = true
        }
      }
    }
    result.unsafeRunSync()
    assert(submissionSuccessful)
  }

  test("failed submission") {}

  test("polling without completions and no timeout") {}

  test("polling with timeout and completions") {}

}
