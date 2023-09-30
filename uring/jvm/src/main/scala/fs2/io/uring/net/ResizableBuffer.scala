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
