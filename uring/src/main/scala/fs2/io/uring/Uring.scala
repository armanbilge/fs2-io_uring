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
import cats.effect.kernel.Cont
import cats.effect.kernel.MonadCancel
import cats.syntax.all._
import cats.~>
import fs2.io.uring.unsafe.UringExecutorScheduler
import fs2.io.uring.unsafe.uring._
import fs2.io.uring.unsafe.uringOps._

import java.io.IOException
import scala.scalanative.unsafe.Ptr

private[uring] final class Uring[F[_]](ring: UringExecutorScheduler)(implicit F: Async[F]) {

  def apply(prep: Ptr[io_uring_sqe] => Unit): F[Int] =
    F.cont {
      new Cont[F, Int, Int] {
        def apply[G[_]](implicit
            G: MonadCancel[G, Throwable]
        ): (Either[Throwable, Int] => Unit, G[Int], F ~> G) => G[Int] = { (resume, get, lift) =>
          G.uncancelable { poll =>
            val submit = F.delay {
              val sqe = ring.getSqe(resume)
              prep(sqe)
              sqe.user_data
            }

            lift(submit)
              .flatMap { addr =>
                // if cannot cancel, fallback to get
                G.onCancel(poll(get), lift(cancel(addr)).ifM(G.unit, get.void))
              }
              .flatTap(e => G.raiseWhen(e < 0)(new IOException(e.toString)))
          }
        }
      }
    }

  private[this] def cancel(addr: __u64): F[Boolean] =
    F.async_[Int] { cb =>
      val sqe = ring.getSqe(cb)
      io_uring_prep_cancel64(sqe, addr, 0)
    }.map(_ == 0) // true if we actually canceled

}

private[uring] object Uring {
  def apply[F[_]](implicit F: Async[F]): F[Uring[F]] = F.executionContext.flatMap {
    case ec: UringExecutorScheduler => F.pure(new Uring(ec))
    case _ => F.raiseError(new RuntimeException("executionContext is not a UringExecutorScheduler"))
  }
}
