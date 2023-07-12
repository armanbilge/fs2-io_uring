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

import java.io.IOException

import scala.collection.mutable.Map
import scala.collection.mutable.BitSet

object UringSystem extends PollingSystem {

  private final val MaxEvents = 64

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

  override def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ()

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
    )(release: Int => IO[Unit]): IO[Int] = IO.cont {
      new Cont[IO, Int, Int] {
        def apply[F[_]](implicit
            F: MonadCancelThrow[F]
        ): (Either[Throwable, Int] => Unit, F[Int], IO ~> F) => F[Int] = { (resume, get, lift) =>
          F.uncancelable { poll =>
            println("[EXEC]: Entering exec")
            println("THREAD:" + Thread.currentThread().getName)
            val submit: IO[Short] = IO.async_[Short] { cb =>
              register { ring =>
                val id = ring.getSqe(resume)
                val sq: UringSubmissionQueue = ring.getSq()
                sq.enqueueSqe(op, flags, rwFlags, fd, bufferAddress, length, offset, id)
                cb(Right(id))
                println("[EXEC]: Leaving exec")
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

    private[this] def cancel(
        id: Short
    ): IO[Boolean] =
      IO.async_[Boolean] { cb =>
        register { ring =>
          val IORING_OP_ASYNC_CANCEL: Byte = 13

          val wasCancel: Boolean =
            !ring.getSq().enqueueSqe(IORING_OP_ASYNC_CANCEL, 0, 0, -1, 0, 0, 0, id)
          cb(Right(wasCancel))
        }
      }
  }

  final class Poller private[UringSystem] (ring: UringRing) {

    private[this] val sq: UringSubmissionQueue = ring.ioUringSubmissionQueue()
    private[this] val cq: UringCompletionQueue = ring.ioUringCompletionQueue()

    private[this] var pendingSubmissions: Boolean = false
    private[this] val callbacks: Map[Short, Either[Throwable, Int] => Unit] =
      Map.empty[Short, Either[Throwable, Int] => Unit]
    private[this] val ids = BitSet(Short.MaxValue + 1)
    private[this] var lastUsedId: Int = -1

    private[this] def getUniqueId(): Short = {
      val id = (lastUsedId + 1 until Short.MaxValue)
        .find(!ids.contains(_))
        .getOrElse(
          (0 until lastUsedId)
            .find(!ids.contains(_))
            .getOrElse(
              throw new RuntimeException("No available IDs")
            )
        )
      lastUsedId = id
      ids.add(id)
      id.toShort
    }

    private[this] def releaseId(id: Short): Boolean = ids.remove(id.toInt)

    private[UringSystem] def removeCallback(id: Short): Boolean = {
      val removed = callbacks.remove(id).isDefined
      if (removed) {
        println(s"REMOVED CB WITH ID: $id")
        println(s"CALLBACK MAP UPDATED AFTER REMOVING: $callbacks")
        releaseId(id)
      }
      removed
    }

    private[UringSystem] def getSqe(cb: Either[Throwable, Int] => Unit): Short = {
      println("GETTING SQE")
      pendingSubmissions = true
      val id: Short = getUniqueId()
      callbacks.put(id, cb)
      println(s"CALLBACK MAP UPDATED: $callbacks")

      id
    }

    private[UringSystem] def getSq(): UringSubmissionQueue = sq

    private[UringSystem] def close(): Unit = ring.close()

    private[UringSystem] def needsPoll(): Boolean = pendingSubmissions || !callbacks.isEmpty

    private[UringSystem] def poll(nanos: Long): Boolean = {

      def process(
          completionQueueCallback: UringCompletionQueueCallback
      ): Boolean =
        cq.process(completionQueueCallback) > 0

      if (pendingSubmissions) {
        ring.submit()
        pendingSubmissions = false
      }

      val completionQueueCallback = new UringCompletionQueueCallback {
        override def handle(fd: Int, res: Int, flags: Int, op: Byte, data: Short): Unit = {
          val removedCallback = callbacks.get(data)

          println(s"[HANDLE CQCB]: fd: $fd, res: $res, falgs: $flags, op: $op, data: $data")

          removedCallback.foreach { cb =>
            if (res < 0) cb(Left(new IOException("Error in completion queue entry")))
            else cb(Right(res))
            removeCallback(data)
          }
        }
      }

      if (cq.hasCompletions()) {
        process(completionQueueCallback)
      } else if (nanos > 0) {
        sq.addTimeout(
          nanos,
          getUniqueId()
        ) // TODO: Check why they do it in this way instead of Scala Native way
        ring.submit()
        cq.ioUringWaitCqe()
        process(completionQueueCallback)
      } else {
        false
      }
    }

  }

}
