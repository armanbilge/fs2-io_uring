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
import cats.effect.LiftIO
import cats.effect.kernel.Resource
import cats.syntax.all._

abstract class Uring private[uring] {
  private[this] val noopMask: Int => Boolean = _ => false

  def call(
      op: Byte,
      flags: Int = 0,
      rwFlags: Int = 0,
      fd: Int = 0,
      bufferAddress: Long = 0,
      length: Int = 0,
      offset: Long = 0,
      mask: Int => Boolean = noopMask
  ): IO[Int]

  def bracket(
      op: Byte,
      flags: Int = 0,
      rwFlags: Int = 0,
      fd: Int = 0,
      bufferAddress: Long = 0,
      length: Int = 0,
      offset: Long = 0,
      mask: Int => Boolean = noopMask
  )(release: Int => IO[Unit]): Resource[IO, Int]
}

object Uring {

  def get[F[_]: LiftIO]: F[Uring] =
    IO.pollers.flatMap {
      _.collectFirst { case ring: Uring =>
        ring
      }.liftTo[IO](new RuntimeException("No UringSystem installed"))
    }.to

}
