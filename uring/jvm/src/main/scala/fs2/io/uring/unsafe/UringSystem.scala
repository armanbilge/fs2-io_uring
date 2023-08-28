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
package unsafe

import cats.~>
import cats.syntax.all._

import cats.effect.IO

import cats.effect.kernel.Resource
import cats.effect.kernel.MonadCancelThrow
import cats.effect.kernel.Cont

import cats.effect.unsafe.PollingSystem

import io.netty.incubator.channel.uring.UringRing
import io.netty.incubator.channel.uring.UringSubmissionQueue
import io.netty.incubator.channel.uring.UringCompletionQueue
import io.netty.incubator.channel.uring.UringCompletionQueueCallback
import io.netty.incubator.channel.uring.NativeAccess
import io.netty.incubator.channel.uring.Encoder

import fs2.io.uring.unsafe.util.OP._

import scala.collection.mutable.Map

import java.nio.ByteBuffer

import io.netty.channel.unix.FileDescriptor

import java.util.BitSet
import java.util.concurrent.ConcurrentLinkedDeque
import java.nio.channels.spi.AbstractSelector
import java.{util => ju}
import java.nio.channels.Selector
import java.nio.channels.SelectionKey
import java.nio.channels.spi.AbstractSelectableChannel

object UringSystem extends PollingSystem {

  private final val MaxEvents = 64

  private val debug = false
  private val debugPoll = debug && false
  private val debugCancel = debug && false
  private val debugInterrupt = debug && false
  private val debugSubmissionQueue = debug && false
  private val debugHandleCompletionQueue = debug && true
  type Api = Uring

  override def makeApi(register: (Poller => Unit) => Unit): Api = new ApiImpl(register)

  override def makePoller(): Poller =
    new Poller(UringRing())

  override def closePoller(poller: Poller): Unit = poller.close()

  override def poll(
      poller: Poller,
      nanos: Long,
      reportFailure: Throwable => Unit
  ): Boolean =
    poller.poll(nanos)

  override def needsPoll(poller: Poller): Boolean = poller.needsPoll()

  override def interrupt(targetThread: Thread, targetPoller: Poller): Unit = {
    if (debugInterrupt)
      println(
        s"[INTERRUPT ${Thread.currentThread().getName()}] waking up poller: ${targetPoller.getFd()} in thread: $targetThread"
      )
    targetPoller.wakeup()
    ()
  }

  private final class ApiImpl(register: (Poller => Unit) => Unit) extends Uring {
    private[this] val noopRelease: Int => IO[Unit] = _ => IO.unit

    def call(
        op: Byte,
        flags: Int,
        rwFlags: Int,
        fd: Int,
        bufferAddress: Long,
        length: Int,
        offset: Long,
        mask: Int => Boolean
    ): IO[Int] =
      exec(op, flags, rwFlags, fd, bufferAddress, length, offset, mask)(noopRelease)

    def bracket(
        op: Byte,
        flags: Int,
        rwFlags: Int,
        fd: Int,
        bufferAddress: Long,
        length: Int,
        offset: Long,
        mask: Int => Boolean
    )(release: Int => IO[Unit]): Resource[IO, Int] =
      Resource.makeFull[IO, Int](poll =>
        poll(exec(op, flags, rwFlags, fd, bufferAddress, length, offset, mask)(release(_)))
      )(release)

    private def exec(
        op: Byte,
        flags: Int,
        rwFlags: Int,
        fd: Int,
        bufferAddress: Long,
        length: Int,
        offset: Long,
        mask: Int => Boolean
    )(release: Int => IO[Unit]): IO[Int] = {

      def cancel(
          id: Short,
          correctRing: Poller
      ): IO[Boolean] =
        IO.uncancelable { _ =>
          IO.async_[Int] { cb =>
            register { ring =>
              val operationAddress = Encoder.encode(fd, op, id)
              if (debugCancel)
                println(
                  s"[CANCEL ring:${ring.getFd()}] cancel an operation: $op with id: $id and address: $operationAddress"
                )
              if (correctRing == ring) {
                val cancelId = ring.getId(cb)
                if (debugCancel)
                  println(
                    s"[CANCEL ring:${ring.getFd()}] Cancelling from the same ring!"
                  )
                ring.enqueueSqe(IORING_OP_ASYNC_CANCEL, 0, 0, -1, operationAddress, 0, 0, cancelId)
              } else {
                if (debugCancel)
                  println(
                    s"[CANCEL ring:${ring.getFd()}] Cancelling from another ring: cancelled operation is in: ${correctRing.getFd()}"
                  )
                correctRing.enqueueCancelOperation(operationAddress, cb)
              }

              ()
            }
          }
        }.map(_ == 0)

      IO.cont {
        new Cont[IO, Int, Int] {
          def apply[F[_]](implicit
              F: MonadCancelThrow[F]
          ): (Either[Throwable, Int] => Unit, F[Int], IO ~> F) => F[Int] = { (resume, get, lift) =>
            F.uncancelable { poll =>
              val submit: IO[(Short, Poller)] = IO.async_[(Short, Poller)] { cb =>
                register { ring =>
                  val id = ring.getId(resume)
                  ring.enqueueSqe(op, flags, rwFlags, fd, bufferAddress, length, offset, id)
                  cb(Right((id, ring)))
                }
              }

              lift(submit)
                .flatMap { case (id, ring) =>
                  F.onCancel(
                    poll(get),
                    lift(cancel(id, ring)).ifM(
                      F.unit,
                      // if cannot cancel, fallback to get
                      get.flatMap { rtn =>
                        if (rtn < 0 && !mask(rtn)) F.raiseError(IOExceptionHelper(-rtn))
                        else lift(release(rtn))
                      }
                    )
                  )
                }
                .flatTap(e => F.raiseWhen(e < 0 && !mask(e))(IOExceptionHelper(-e)))
            }
          }
        }
      }
    }
  }

  final class Poller private[UringSystem] (ring: UringRing) extends AbstractSelector(null) {

    private[this] val interruptFd = FileDescriptor.pipe()
    private[this] val readEnd = interruptFd(0)
    private[this] val writeEnd = interruptFd(1)
    private[this] var listenFd: Boolean = false

    private[this] val cancelOperations
        : ConcurrentLinkedDeque[(Long, Either[Throwable, Int] => Unit)] =
      new ConcurrentLinkedDeque()

    private[this] val sq: UringSubmissionQueue = ring.ioUringSubmissionQueue()
    private[this] val cq: UringCompletionQueue = ring.ioUringCompletionQueue()

    private[this] var pendingSubmissions: Boolean = false
    private[this] val callbacks: Map[Short, Either[Throwable, Int] => Unit] =
      Map.empty[Short, Either[Throwable, Int] => Unit]
    private[this] val ids = new BitSet(Short.MaxValue)

    // API

    private[UringSystem] def getId(
        cb: Either[Throwable, Int] => Unit
    ): Short = {
      val id: Short = getUniqueId()
      pendingSubmissions = true
      callbacks.put(id, cb)
      id
    }

    private[UringSystem] def getFd(): Int = ring.fd()

    private[UringSystem] def needsPoll(): Boolean = pendingSubmissions || !callbacks.isEmpty

    private[UringSystem] def enqueueSqe(
        op: Byte,
        flags: Int,
        rwFlags: Int,
        fd: Int,
        bufferAddress: Long,
        length: Int,
        offset: Long,
        data: Short
    ): Boolean = {
      if (debugSubmissionQueue && data > 9)
        println(
          s"[SQ ${ring.fd()}] Enqueuing a new Sqe with: OP: $op, flags: $flags, rwFlags: $rwFlags, fd: $fd, bufferAddress: $bufferAddress, length: $length, offset: $offset, extraData: $data"
        )

      sq.enqueueSqe(op, flags, rwFlags, fd, bufferAddress, length, offset, data)
    }

    private[UringSystem] def enqueueCancelOperation(
        operationAddress: Long,
        cb: Either[Throwable, Int] => Unit
    ): Boolean = {
      cancelOperations.add((operationAddress, cb))
      writeFd() >= 0
    }

    private[UringSystem] def poll(
        nanos: Long
    ): Boolean =
      try {
        begin()

        if (debugPoll)
          println(s"[POLL ${Thread.currentThread().getName()}] Polling with nanos = $nanos")

        startListening() // Check if it is listening to the FD. If not, start listening

        checkCancelOperations() // Check for cancel operations

        nanos match {
          case -1 =>
            if (pendingSubmissions) {
              sq.submitAndWait()
            } else {
              cq.ioUringWaitCqe()
            }

          case 0 =>
            if (pendingSubmissions) {
              sq.submit()
            }

          case _ =>
            if (pendingSubmissions) {
              sq.addTimeout(nanos, 0)
              sq.submitAndWait()
            } else {
              sq.addTimeout(nanos, 0)
              sq.submit()
              cq.ioUringWaitCqe()
            }
        }

        val invokedCbs = process(completionQueueCallback)

        pendingSubmissions = false
        invokedCbs
      } finally
        end()

    // private

    // CALLBACKS
    private[this] def getUniqueId(): Short = {
      val newId = ids.nextClearBit(10) // 0-9 are reserved for certain operations
      ids.set(newId)
      newId.toShort
    }

    private[this] def releaseId(id: Short): Unit = ids.clear(id.toInt)

    private[this] def removeCallback(id: Short): Boolean =
      callbacks
        .remove(id)
        .map(_ => releaseId(id))
        .isDefined

    // INTERRUPT
    private[this] def writeFd(): Int = {
      val buf = ByteBuffer.allocateDirect(1)
      buf.put(0.toByte)
      buf.flip()
      writeEnd.write(buf, 0, 1)
    }

    // POLL

    private[this] def startListening(): Unit =
      if (!listenFd) {
        if (debugPoll)
          println(s"[POLL ${Thread.currentThread().getName()}] We are not listening to the FD!")

        enqueueSqe(
          IORING_OP_POLL_ADD,
          0,
          NativeAccess.POLLIN,
          readEnd.intValue(),
          0,
          0,
          0,
          NativeAccess.POLLIN.toShort
        )
        pendingSubmissions = true
        listenFd = true // Set the flag indicating it is now listening
      }

    private[this] def checkCancelOperations(): Unit =
      if (!cancelOperations.isEmpty()) {
        if (debugPoll)
          println(
            s"[POLL ${Thread.currentThread().getName()}] The Cancel Queue is not empty, it has: ${cancelOperations.size()} elements"
          )
        cancelOperations.forEach { case (operationAddress, cb) =>
          val id = getId(cb)
          enqueueSqe(IORING_OP_ASYNC_CANCEL, 0, 0, -1, operationAddress, 0, 0, id)
          ()
        }
        cancelOperations.clear()
      }

    private[this] def process(
        completionQueueCallback: UringCompletionQueueCallback
    ): Boolean =
      cq.process(completionQueueCallback) > 0

    private[this] val completionQueueCallback = new UringCompletionQueueCallback {
      override def handle(fd: Int, res: Int, flags: Int, op: Byte, data: Short): Unit = {
        def handleCallback(res: Int, cb: Either[Throwable, Int] => Unit): Unit = cb(Right(res))

        if (debugHandleCompletionQueue && data > 9 && res < 0)
          println(
            s"[HANDLE CQCB ${ring.fd()}]: fd: $fd, res: $res, flags: $flags, op: $op, data: $data"
          )

        /*
         Instead of using a callback for interrupt handling, we manage the interrupt directly within this block.
         Checks for an interrupt by determining if the FileDescriptor (fd) has been written to.
         */
        if (fd == readEnd.intValue() && op == IORING_OP_POLL_ADD) {
          val buf = ByteBuffer.allocateDirect(1)
          readEnd.read(buf, 0, 1)
          listenFd = false
        } else {
          // Handle the callback
          callbacks.get(data).foreach { cb =>
            handleCallback(res, cb)
            removeCallback(data)
          }
        }
      }
    }

    // ABSTRACT SELECTOR
    override def keys(): ju.Set[SelectionKey] = throw new UnsupportedOperationException

    override def selectedKeys(): ju.Set[SelectionKey] = throw new UnsupportedOperationException

    override def selectNow(): Int = throw new UnsupportedOperationException

    override def select(x$1: Long): Int = throw new UnsupportedOperationException

    override def select(): Int = throw new UnsupportedOperationException

    override def wakeup(): Selector = {
      writeFd()
      this
    }

    override protected def implCloseSelector(): Unit = {
      readEnd.close()
      writeEnd.close()
      ring.close()
    }

    override protected def register(
        x$1: AbstractSelectableChannel,
        x$2: Int,
        x$3: Object
    ): SelectionKey = throw new UnsupportedOperationException

  }
}
