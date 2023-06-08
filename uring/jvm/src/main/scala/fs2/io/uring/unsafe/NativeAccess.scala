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

package io.netty.incubator.channel.uring

import cats.effect.{Resource, Sync}
import io.netty.channel.unix.FileDescriptor

object NativeAccessEffect {

  val DEFAULT_RING_SIZE = Native.DEFAULT_RING_SIZE
  val DEFAULT_IOSEQ_ASYNC_THRESHOLD = Native.DEFAULT_IOSEQ_ASYNC_THRESHOLD

  def createRingBuffer[F[_]: Sync](): Resource[F, RingBuffer] =
    createRingBuffer(DEFAULT_RING_SIZE, DEFAULT_IOSEQ_ASYNC_THRESHOLD)

  def createRingBuffer[F[_]: Sync](size: Int): Resource[F, RingBuffer] =
    createRingBuffer(size, DEFAULT_IOSEQ_ASYNC_THRESHOLD)

  def createRingBuffer[F[_]: Sync](size: Int, sqeAsyncThreshold: Int): Resource[F, RingBuffer] =
    Resource.make {
      Sync[F].delay(Native.createRingBuffer(size, sqeAsyncThreshold))
    } { ringBuffer =>
      Sync[F].delay(ringBuffer.close())
    }

  def checkAllIOSupported[F[_]: Sync](ringFd: Int): F[Unit] =
    Sync[F].delay(Native.checkAllIOSupported(ringFd))

  def checkKernelVersion[F[_]: Sync](kernelVersion: String): F[Unit] =
    Sync[F].delay(Native.checkKernelVersion(kernelVersion))

  def ioUringEnter[F[_]: Sync](ringFd: Int, toSubmit: Int, minComplete: Int, flags: Int): F[Int] =
    Sync[F].delay(Native.ioUringEnter(ringFd, toSubmit, minComplete, flags))

  def eventFdWrite[F[_]: Sync](fd: Int, value: Long): F[Unit] =
    Sync[F].delay(Native.eventFdWrite(fd, value))

  def newBlockingEventFd[F[_]: Sync]: F[FileDescriptor] =
    Sync[F].delay(Native.newBlockingEventFd())

  def ioUringExit[F[_]: Sync](
      submissionQueueArrayAddress: Long,
      submissionQueueRingEntries: Int,
      submissionQueueRingAddress: Long,
      submissionQueueRingSize: Int,
      completionQueueRingAddress: Long,
      completionQueueRingSize: Int,
      ringFd: Int
  ): F[Unit] =
    Sync[F].delay(
      Native.ioUringExit(
        submissionQueueArrayAddress,
        submissionQueueRingEntries,
        submissionQueueRingAddress,
        submissionQueueRingSize,
        completionQueueRingAddress,
        completionQueueRingSize,
        ringFd
      )
    )

  def createFile[F[_]: Sync](name: String): F[Int] =
    Sync[F].delay(Native.createFile(name))

  def cmsghdrData[F[_]: Sync](hdrAddr: Long): F[Long] =
    Sync[F].delay(Native.cmsghdrData(hdrAddr))

  def kernelVersion[F[_]: Sync]: F[String] =
    Sync[F].delay(Native.kernelVersion())

}

object NativeAccess {
  val DEFAULT_RING_SIZE = Native.DEFAULT_RING_SIZE
  val DEFAULT_IOSEQ_ASYNC_THRESHOLD = Native.DEFAULT_IOSEQ_ASYNC_THRESHOLD
  
  val POLLIN = Native.POLLIN
  val POLLOUT = Native.POLLOUT

  def createRingBuffer(): RingBuffer =
    createRingBuffer(DEFAULT_RING_SIZE, DEFAULT_IOSEQ_ASYNC_THRESHOLD)

  def createRingBuffer(size: Int): RingBuffer =
    createRingBuffer(size, DEFAULT_IOSEQ_ASYNC_THRESHOLD)

  def createRingBuffer(size: Int, sqeAsyncThreshold: Int): RingBuffer =
    Native.createRingBuffer(size, sqeAsyncThreshold)

  def checkAllIOSupported(ringFd: Int): Unit =
    Native.checkAllIOSupported(ringFd)

  def checkKernelVersion(kernelVersion: String): Unit =
    Native.checkKernelVersion(kernelVersion)

  def ioUringEnter(ringFd: Int, toSubmit: Int, minComplete: Int, flags: Int): Int =
    Native.ioUringEnter(ringFd, toSubmit, minComplete, flags)

  def eventFdWrite(fd: Int, value: Long): Unit =
    Native.eventFdWrite(fd, value)

  def newBlockingEventFd: FileDescriptor =
    Native.newBlockingEventFd()

  def ioUringExit(
      submissionQueueArrayAddress: Long,
      submissionQueueRingEntries: Int,
      submissionQueueRingAddress: Long,
      submissionQueueRingSize: Int,
      completionQueueRingAddress: Long,
      completionQueueRingSize: Int,
      ringFd: Int
  ): Unit =
    Native.ioUringExit(
      submissionQueueArrayAddress,
      submissionQueueRingEntries,
      submissionQueueRingAddress,
      submissionQueueRingSize,
      completionQueueRingAddress,
      completionQueueRingSize,
      ringFd
    )
}
