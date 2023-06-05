package io.netty.incubator.channel.uring

import io.netty.incubator.channel.uring.UringCompletionQueue
import io.netty.incubator.channel.uring.UringSubmissionQueue

class UringRing {
  private val ringBuffer: RingBuffer = NativeAccess.createRingBuffer()

  def ioUringCompletionQueue(): UringCompletionQueue =
    new UringCompletionQueue(ringBuffer)

  def ioUringSubmissionQueue(): UringSubmissionQueue =
    new UringSubmissionQueue(ringBuffer)

  def fd(): Int = ringBuffer.fd()

  def close(): Unit = ringBuffer.close()
}
