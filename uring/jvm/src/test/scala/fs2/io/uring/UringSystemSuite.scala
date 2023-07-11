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
        val IORING_OP_NOP: Byte = 0 

        val op: Byte = IORING_OP_NOP
        val flags: Int = 0
        val rwFlags: Int = 0
        val fd: Int = -1
        val bufferAddress: Long = 0
        val length: Int = 0
        val offset: Long = 0

        ring.call(op, flags, rwFlags, fd, bufferAddress, length, offset)
      }
      .assertEquals(0)

  }

//   IOURINGINLINE void io_uring_prep_nop(struct io_uring_sqe *sqe)
// {
// 	io_uring_prep_rw(IORING_OP_NOP, sqe, -1, NULL, 0, 0);
// }

// IOURINGINLINE void io_uring_prep_rw(int op, struct io_uring_sqe *sqe, int fd,
// 				    const void *addr, unsigned len,
// 				    __u64 offset)
// {
// 	sqe->opcode = (__u8) op;
// 	sqe->flags = 0;
// 	sqe->ioprio = 0;
// 	sqe->fd = fd;
// 	sqe->off = offset;
// 	sqe->addr = (unsigned long) addr;
// 	sqe->len = len;
// 	sqe->rw_flags = 0;
// 	sqe->buf_index = 0;
// 	sqe->personality = 0;
// 	sqe->file_index = 0;
// 	sqe->addr3 = 0;
// 	sqe->__pad2[0] = 0;
// }

  test("successful submission") {}

  test("failed submission") {}

  test("polling without completions and no timeout") {}

  test("polling with timeout and completions") {}

}
