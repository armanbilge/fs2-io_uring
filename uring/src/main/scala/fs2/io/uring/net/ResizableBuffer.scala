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

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all._

import scala.scalanative.libc.errno._
import scala.scalanative.libc.stdlib._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private[net] final class ResizableBuffer[F[_]] private (
    private var ptr: Ptr[Byte],
    private[this] var size: Int
)(implicit F: Sync[F]) {

  def get(size: Int): F[Ptr[Byte]] = F.delay {
    if (size <= this.size)
      F.pure(ptr)
    else {
      ptr = realloc(ptr, size.toUInt)
      this.size = size
      if (ptr == null)
        F.raiseError[Ptr[Byte]](new RuntimeException(s"realloc: ${errno}"))
      else F.pure(ptr)
    }
  }.flatten

}

private[net] object ResizableBuffer {

  def apply[F[_]](size: Int)(implicit F: Sync[F]): Resource[F, ResizableBuffer[F]] =
    Resource.make {
      F.delay {
        val ptr = malloc(size.toUInt)
        if (ptr == null)
          throw new RuntimeException(s"malloc: ${errno}")
        else new ResizableBuffer(ptr, size)
      }
    }(buf => F.delay(free(buf.ptr)))

}
