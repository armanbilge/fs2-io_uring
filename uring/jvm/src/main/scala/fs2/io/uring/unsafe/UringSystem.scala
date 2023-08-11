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
import io.netty.incubator.channel.uring.Encoder

import fs2.io.uring.unsafe.util.OP._

import java.io.IOException

import scala.collection.mutable.Map
import java.util.BitSet

object UringSystem extends PollingSystem {

  private val extraRing: UringRing = UringRing()

  private val extraPoller: Poller = makePoller()

  private final val MaxEvents = 64

  private val debug = true // True to printout operations
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
    println(s"[INTERRUPT] waking up poller: $targetPoller in thread: $targetThread")
    println(s"[INTERRUPT] current thread: ${Thread.currentThread().getName()}]")
    if (targetThread == Thread.currentThread()) {
      println("WE ARE IN THE SAME THREAD AS THE POLLER!!!!!!!!!!!")
      targetPoller.sendMsgRing(0, targetPoller.getFd())
      ()
    } else {
      extraRing.sendMsgRing(0, targetPoller.getFd())
      ()
    }
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
        offset: Long
    ): IO[Int] =
      exec(op, flags, rwFlags, fd, bufferAddress, length, offset)(noopRelease)

    def bracket(
        op: Byte,
        flags: Int,
        rwFlags: Int,
        fd: Int,
        bufferAddress: Long,
        length: Int,
        offset: Long
    )(release: Int => IO[Unit]): Resource[IO, Int] =
      Resource.makeFull[IO, Int](poll =>
        poll(exec(op, flags, rwFlags, fd, bufferAddress, length, offset)(release(_)))
      )(release)

    private def exec(
        op: Byte,
        flags: Int,
        rwFlags: Int,
        fd: Int,
        bufferAddress: Long,
        length: Int,
        offset: Long
    )(release: Int => IO[Unit]): IO[Int] = {

      def cancel(id: Short): IO[Boolean] =
        IO.uncancelable { _ =>
          IO.async_[Int] { cb =>
            register { ring =>
              val cancelId = ring.getId(cb)
              val opToCancel = Encoder.encode(fd, op, id - 1)
              println(s"[CANCEL] cancel id: $cancelId and op to cancel: $opToCancel")
              ring.cancel(opToCancel, cancelId)
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
              val submit: IO[Short] = IO.async_[Short] { cb =>
                register { ring =>
                  val id = ring.getId(resume)
                  ring.enqueueSqe(op, flags, rwFlags, fd, bufferAddress, length, offset, id)
                  cb(Right(id))
                }
              }

              lift(submit)
                .flatMap { id =>
                  F.onCancel(
                    poll(get),
                    lift(cancel(id)).ifM(
                      F.unit,
                      // if cannot cancel, fallback to get
                      get.flatMap { rtn =>
                        if (rtn < 0) F.raiseError(IOExceptionHelper(-rtn))
                        else lift(release(rtn))
                      }
                    )
                  )
                }
                .flatTap(e => F.raiseWhen(e < 0)(IOExceptionHelper(-e)))
            }

          }
        }
      }

    }

  }

  final class Poller private[UringSystem] (ring: UringRing) {

    private[this] val sq: UringSubmissionQueue = ring.ioUringSubmissionQueue()
    private[this] val cq: UringCompletionQueue = ring.ioUringCompletionQueue()

    private[this] var pendingSubmissions: Boolean = false
    private[this] val callbacks: Map[Short, Either[Throwable, Int] => Unit] =
      Map.empty[Short, Either[Throwable, Int] => Unit]
    private[this] val ids = new BitSet(Short.MaxValue)

    private[this] def getUniqueId(): Short = {
      val newId = ids.nextClearBit(1)
      ids.set(newId)
      newId.toShort
    }

    private[this] def releaseId(id: Short): Unit = ids.clear(id.toInt)

    private[this] def removeCallback(id: Short): Boolean =
      callbacks
        .remove(id)
        .map { _ =>
          if (debug) {
            println(s"REMOVED CB WITH ID: $id")
            println(s"CALLBACK MAP UPDATED AFTER REMOVING: $callbacks")
          }
          releaseId(id)
        }
        .isDefined

    private[UringSystem] def getId(cb: Either[Throwable, Int] => Unit): Short = {
      val id: Short = getUniqueId()

      pendingSubmissions = true
      callbacks.put(id, cb)
      if (debug) {
        println("GETTING ID")
        println(s"CALLBACK MAP UPDATED: $callbacks")
      }
      id
    }

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
      if (debug)
        println(
          s"[SQ] Enqueuing a new Sqe in ringFd: ${ring
              .fd()} with: OP: $op, flags: $flags, rwFlags: $rwFlags, fd: $fd, bufferAddress: $bufferAddress, length: $length, offset: $offset, extraData: $data"
        )
      sq.enqueueSqe(op, flags, rwFlags, fd, bufferAddress, length, offset, data)
    }

    private[UringSystem] def cancel(opToCancel: Long, id: Short): Boolean =
      enqueueSqe(IORING_OP_ASYNC_CANCEL, 0, 0, -1, opToCancel, 0, 0, id)

    private[UringSystem] def sendMsgRing(flags: Int, fd: Int): Boolean = {
      println(s"[SENDMESSAGE] current thread: ${Thread.currentThread().getName()}]")
      enqueueSqe(IORING_OP_MSG_RING, flags, 0, fd, 0, 0, 0, getUniqueId())
      sq.submit() > 0
    }

    private[UringSystem] def getFd(): Int = ring.fd()

    private[UringSystem] def close(): Unit =
      ring.close()

    private[UringSystem] def needsPoll(): Boolean = pendingSubmissions || !callbacks.isEmpty

    private[UringSystem] def poll(nanos: Long): Boolean = {

      def process(
          completionQueueCallback: UringCompletionQueueCallback
      ): Boolean =
        cq.process(completionQueueCallback) > 0

      val completionQueueCallback = new UringCompletionQueueCallback {
        override def handle(fd: Int, res: Int, flags: Int, op: Byte, data: Short): Unit = {
          def handleCallback(res: Int, cb: Either[Throwable, Int] => Unit): Unit =
            if (res < 0)
              cb(
                Left(
                  new IOException(
                    s"Error in completion queue entry of the ring with fd: ${ring
                        .fd()} with fd: $fd op: $op res: $res and data: $data"
                  )
                )
              )
            else cb(Right(res))

          if (debug) // To prevent the constant printouts of timeout operation when NANOS == -1
            println(
              s"[HANDLE CQCB]: ringfd: ${ring.fd()} fd: $fd, res: $res, flags: $flags, op: $op, data: $data"
            )

          callbacks.get(data).foreach { cb =>
            handleCallback(res, cb)
            removeCallback(data)
          }

        }
      }

      // 1. Submit pending operations if any
      if (pendingSubmissions) {
        sq.submit()
        pendingSubmissions = false
      }

      // 2. Check for events based on nanos value
      nanos match {
        case -1 =>
          println(s"[POLL] we are polling with nanos = -1, therefore we wait for a cqe")
          cq.ioUringWaitCqe()
        case 0 =>
        // do nothing, just check without waiting
        case _ =>
          println(s"[POLL] we are polling with nanos = $nanos")
          sq.addTimeout(nanos, getUniqueId())
          sq.submitAndWait()
      }

      // 3. Process the events
      process(completionQueueCallback)
    }

  }

}
