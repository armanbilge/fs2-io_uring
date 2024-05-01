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

import io.netty.buffer.ByteBuf
import io.netty.buffer.UnpooledByteBufAllocator

private[net] final class ResizableBuffer[F[_]] private (
    private var buffer: ByteBuf
)(implicit F: Sync[F]) {

  def get(minCapacity: Int): F[ByteBuf] = F.delay {
    if (buffer.capacity() >= minCapacity) {
      buffer
    } else {
      val newBuffer = UnpooledByteBufAllocator.DEFAULT.directBuffer(minCapacity)
      newBuffer.writeBytes(buffer)
      buffer.release(buffer.refCnt())
      buffer = newBuffer
      buffer
    }
  }

  def currentBuffer: ByteBuf = buffer

}

private[net] object ResizableBuffer {

  def apply[F[_]](size: Int)(implicit F: Sync[F]): Resource[F, ResizableBuffer[F]] =
    Resource.make {
      F.delay {
        val initialBuffer = UnpooledByteBufAllocator.DEFAULT.directBuffer(size)
        new ResizableBuffer(initialBuffer)
      }
    }(buf => F.delay { val _ = buf.currentBuffer.release(buf.currentBuffer.refCnt()) })
}
