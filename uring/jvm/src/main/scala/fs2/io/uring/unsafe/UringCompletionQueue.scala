package io.netty.incubator.channel.uring

class UringCompletionQueue(ring: RingBuffer) {

  private val completionQueue: IOUringCompletionQueue = ring.ioUringCompletionQueue()

  def hashCompletitions(): Boolean = completionQueue.hasCompletions()

  def processs(cb: IOUringCompletionQueueCallback): Int = completionQueue.process(cb)

  def ioUringWaitCqe(): Unit = completionQueue.ioUringWaitCqe()

  def ringAddress(): Long = completionQueue.ringAddress

  def ringFd(): Int = completionQueue.ringFd

  def ringSize(): Int = completionQueue.ringSize
}
