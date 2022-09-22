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

import cats.effect.kernel.Async
import fs2.io.uring.unsafe.UringExecutorScheduler
import fs2.io.uring.unsafe.uring._
import fs2.io.uring.unsafe.uringOps._

import scala.scalanative.unsafe.Ptr

private[uring] final class Uring[F[_]](ring: UringExecutorScheduler)(implicit F: Async[F]) {

  def apply(prep: Ptr[io_uring_sqe] => Unit): F[Int] = F.async[Int] { cb =>
    F.delay {
      val sqe = ring.getSqe()
      prep(sqe)
      io_uring_sqe_set_data(sqe, cb)
      ???
    }
  }

}
