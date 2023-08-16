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
import io.netty.channel.unix.FileDescriptor
import java.nio.ByteBuffer
import io.netty.incubator.channel.uring.NativeAccess

object UringSystem extends PollingSystem {

  private val extraRing: UringRing = UringRing()

  private[this] val rings: scala.collection.concurrent.Map[Short, Poller] =
    scala.collection.concurrent.TrieMap.empty

  private final val MaxEvents = 64

  private val debug = false // True to printout operations
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
    poller.poll(nanos, rings)

  override def needsPoll(poller: Poller): Boolean = poller.needsPoll()

  override def interrupt(targetThread: Thread, targetPoller: Poller): Unit = {
    if (debug)
      println(
        s"[INTERRUPT ${Thread.currentThread().getName()}] waking up poller: $targetPoller in thread: $targetThread"
      )
    // Interrupt using an extra ring
    // extraRing.sendMsgRing(0, targetPoller.getFd())

    // Interrupt using a pipe
    targetPoller.writeFd()

    /*
     Interrupt for single-thread
     It consistently calls the interrupt for a single-threaded context.
     A suitable implementation might be required to manage this. (?)
     */
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
              val cancelId = ring.getId(rings, cb)
              val opAddressToCancel = Encoder.encode(fd, op, id)
              println(
                s"[CANCEL] from fd: $fd cancel id: $cancelId and op to cancel is in address: $opAddressToCancel"
              )
              val correctRing = rings.getOrElse(id, ring)
              println(s"The right ring is $correctRing")
              correctRing.cancel(opAddressToCancel, cancelId)
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
                  val id = ring.getId(rings, resume)
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

    val interruptFd = FileDescriptor.pipe()
    val readEnd = interruptFd(0)
    val writeEnd = interruptFd(1)
    var listenFd: Boolean = false

    private[this] val sq: UringSubmissionQueue = ring.ioUringSubmissionQueue()
    private[this] val cq: UringCompletionQueue = ring.ioUringCompletionQueue()

    private[this] var pendingSubmissions: Boolean = false
    private[this] val callbacks: Map[Short, Either[Throwable, Int] => Unit] =
      Map.empty[Short, Either[Throwable, Int] => Unit]
    private[this] val ids = new BitSet(Short.MaxValue)

    private[this] def getUniqueId(): Short = {
      val newId = ids.nextClearBit(10)
      ids.set(newId)
      newId.toShort
    }

    private[this] def releaseId(id: Short): Unit = ids.clear(id.toInt)

    private[this] def removeCallback(id: Short, rings: scala.collection.concurrent.Map[Short, Poller]): Boolean =
      callbacks
        .remove(id)
        .map { _ =>
          // if (debug) {
          //   println(s"REMOVED CB WITH ID: $id")
          //   println(s"CALLBACK MAP UPDATED AFTER REMOVING: $callbacks")
          // }
          releaseId(id)
          rings.remove(id)
        }
        .isDefined

    private[UringSystem] def getId(
        rings: scala.collection.concurrent.Map[Short, Poller],
        cb: Either[Throwable, Int] => Unit
    ): Short = {
      val id: Short = getUniqueId()

      pendingSubmissions = true
      callbacks.put(id, cb)
      rings.put(id, this)
      // if (debug) {
      //   println("GETTING ID")
      //   println(s"CALLBACK MAP UPDATED: $callbacks")
      // }
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

    private[UringSystem] def cancel(opAddressToCancel: Long, id: Short): Boolean =
      enqueueSqe(IORING_OP_ASYNC_CANCEL, 0, 0, -1, opAddressToCancel, 0, 0, id)

    private[UringSystem] def getFd(): Int = ring.fd()

    private[UringSystem] def close(): Unit = {
      readEnd.close()
      writeEnd.close()
      ring.close()
    }

    private[UringSystem] def needsPoll(): Boolean = pendingSubmissions || !callbacks.isEmpty

    private[UringSystem] def writeFd(): Int = {
      val buf = ByteBuffer.allocateDirect(1)
      buf.put(0.toByte)
      buf.flip()
      writeEnd.write(buf, 0, 1)
    }
    private[UringSystem] def poll(
        nanos: Long,
        rings: scala.collection.concurrent.Map[Short, Poller]
    ): Boolean = {

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

          if (op == 14 || op == 19) {
            println(
              s"[HANDLE CQCB]: ringfd: ${ring.fd()} fd: $fd, res: $res, flags: $flags, op: $op, data: $data"
            )
          }

          /*
         Instead of using a callback for interrupt handling, we manage the interrupt directly within this block.
         Checks for an interrupt by determining if the FileDescriptor (fd) has been written to.
           */
          if (fd == readEnd.intValue()) {
            val buf = ByteBuffer.allocateDirect(1)
            val readed = readEnd.read(buf, 0, 1) // Consume the listener
            if (readed > 0) {
              buf.clear()
              listenFd = false // Sets the flag to stop listening to the FileDescriptor
            }
          }

          // Handle the callback
          callbacks.get(data).foreach { cb =>
            handleCallback(res, cb)
            removeCallback(data, rings)
          }

        }
      }

      def process(
          completionQueueCallback: UringCompletionQueueCallback
      ): Boolean =
        cq.process(completionQueueCallback) > 0

      // Check if we are listening to the FD. If not, start listening
      if (!listenFd) {
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
        listenFd = true // Set the flag indicating we're now listening
      }

      if (debug) println(s"[POLL ${Thread.currentThread().getName()}]Polling with nanos = $nanos")

      var rtn = -1
      nanos match {
        case -1 =>
          if (pendingSubmissions) {
            rtn = sq.submitAndWait()
          } else {
            cq.ioUringWaitCqe()
          }

        case 0 =>
          if (pendingSubmissions) {
            rtn = sq.submit()
          }

        case _ =>
          if (pendingSubmissions) {
            sq.addTimeout(nanos, 0)
            rtn = sq.submitAndWait()
          } else {
            sq.addTimeout(nanos, 0)
            sq.submit()
            cq.ioUringWaitCqe()
          }
      }

      val invokedCbs = process(completionQueueCallback)

      // If pending submissions exist and we encountered an error, retry submission.
      while (pendingSubmissions && rtn <= 0)
        rtn = sq.submit()

      pendingSubmissions = false

      invokedCbs

      // if (!wakingUp) {
      //   enqueueSqe(
      //     IORING_OP_POLL_ADD,
      //     0,
      //     NativeAccess.POLLIN,
      //     readEnd.intValue(),
      //     0,
      //     0,
      //     0,
      //     NativeAccess.POLLIN.toShort
      //   )
      //   sq.submit()
      //   wakingUp = true
      // }

      // // 1. Submit pending operations if any
      // val submitted = submit()

      // // 2. Check for events based on nanos value
      // nanos match {
      //   case -1 =>
      //     if (debug) println(s"[POLL] we are polling with nanos = -1, therefore we wait for a cqe")
      //     if (submitted && !cq.hasCompletions()) {
      //       // if (debug) println("[POLL] We are going to wait cqe (BLOCKING)")
      //       cq.ioUringWaitCqe()
      //     } else {
      //       // sq.addTimeout(0, 0)// replace 1 sec with 0
      //       // enqueueSqe(IORING_OP_POLL_ADD, NativeAccess.POLLIN, 0, readEnd.intValue(), 0, 0, 0, 0)
      //       // submit()
      //       cq.ioUringWaitCqe()

      //       // val buf = ByteBuffer.allocateDirect(1)
      //       // readEnd.read(buf, 0, 1)
      //     }
      //   case 0 =>
      //   // do nothing, just check without waiting
      //   case _ =>
      //     if (debug) println(s"[POLL] we are polling with nanos = $nanos")

      //     if (submitted) {
      //       // if (debug) println("[POLL] We are going to wait cqe (BLOCKING)")
      //       cq.ioUringWaitCqe()
      //       // if (sq.count() > 0) sq.submit()
      //       // if (cq.hasCompletions()) {
      //       //   process(completionQueueCallback)
      //       // }
      //     } else {
      //       // sq.addTimeout(0, 0)// replace 1 sec with 0
      //       // enqueueSqe(IORING_OP_POLL_ADD, NativeAccess.POLLIN, 0, readEnd.intValue(), 0, 0, 0, 0)
      //       // submit()

      //       cq.ioUringWaitCqe()

      //       // val buf = ByteBuffer.allocateDirect(1)
      //       // readEnd.read(buf, 0, 1)
      //       // sq.addTimeout(nanos, 0) //
      //     }
      // }

      // // 3. Process the events
      // val proc = process(completionQueueCallback)
      // // if (debug) println(s"[POLL] We processed cqe ? : $proc")

      // proc

      // if (!wakingUp) {
      //   enqueueSqe(
      //     IORING_OP_POLL_ADD,
      //     0,
      //     NativeAccess.POLLIN,
      //     readEnd.intValue(),
      //     0,
      //     0,
      //     0,
      //     NativeAccess.POLLIN.toShort
      //   )
      //   sq.submit() // we start listening, it will be completed only when we call interrupt, therefore we don't want to submit and wait
      //   wakingUp = true // Now we are listening
      // }

      // def handlePendingSubmissions(submitAndWait: Boolean): Boolean = {
      //   // if (submitAndWait) println("[HANDLE PENDING SUMBISSION] Submiting and waiting...")
      //   // else println("[HANDLE PENDING SUMBISSION] Submiting...")
      //   val submitted = if (submitAndWait) sq.submitAndWait() > 0 else sq.submit() > 0
      //   if (submitted) pendingSubmissions = false
      //   // println(
      //   //   s"[HANDLE PENDING SUBMISSION] submitted a positive number of operations: $submitted"
      //   // )
      //   submitted
      // }

      // def handleTimeoutAndQueue(nanos: Long, submitAndWait: Boolean): Boolean = {
      //   // println(s"[HANDLE TIMEOUT AND QUEUE] adding timeout: $nanos")
      //   sq.addTimeout(nanos, 0)
      //   val submitted = handlePendingSubmissions(submitAndWait)
      //   // println(s"[HANDLE TIMEOUT AND QUEUE] waiting CQE")
      //   cq.ioUringWaitCqe()
      //   // println(s"[HANDLE TIMEOUT AND QUEUE] processing CQ")
      //   process(completionQueueCallback)
      //   // println(s"[HANDLE TIMEOUT AND QUEUE] submitted a positive number of operations: $submitted")
      //   submitted
      // }

      // nanos match {
      //   case -1 =>
      //     if (pendingSubmissions) handlePendingSubmissions(true)
      //     else handleTimeoutAndQueue(-1, true)
      //   case 0 => if (pendingSubmissions) handlePendingSubmissions(false) else false
      //   case _ =>
      //     if (pendingSubmissions) handlePendingSubmissions(true)
      //     else handleTimeoutAndQueue(nanos, false)
      // }

    }

  }
}
