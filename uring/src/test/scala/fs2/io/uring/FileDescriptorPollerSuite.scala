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

import cats.effect.FileDescriptorPoller
import cats.effect.FileDescriptorPollHandle
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.CountDownLatch
import cats.syntax.all._

import scala.concurrent.duration._
import scala.scalanative.libc.errno._
import scala.scalanative.posix.errno._
import scala.scalanative.posix.fcntl._
import scala.scalanative.posix.string._
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import java.io.IOException

class FileDescriptorPollerSuite extends UringSuite {

  def getFdPoller: IO[FileDescriptorPoller] =
    IO.pollers.map(_.collectFirst { case poller: FileDescriptorPoller => poller }).map(_.get)

  final class Pipe(
      val readFd: Int,
      val writeFd: Int,
      val readHandle: FileDescriptorPollHandle,
      val writeHandle: FileDescriptorPollHandle
  ) {
    def read(buf: Array[Byte], offset: Int, length: Int): IO[Unit] =
      readHandle
        .pollReadRec(())(_ => IO(guard(unistd.read(readFd, buf.at(offset), length.toULong))))
        .void

    def write(buf: Array[Byte], offset: Int, length: Int): IO[Unit] =
      writeHandle
        .pollWriteRec(()) { _ =>
          IO(guard(unistd.write(writeFd, buf.at(offset), length.toULong)))
        }
        .void

    private def guard(thunk: => CInt): Either[Unit, CInt] = {
      val rtn = thunk
      if (rtn < 0) {
        val en = errno
        if (en == EAGAIN || en == EWOULDBLOCK)
          Left(())
        else
          throw new IOException(fromCString(strerror(errno)))
      } else
        Right(rtn)
    }
  }

  def mkPipe: Resource[IO, Pipe] =
    Resource
      .make {
        IO {
          val fd = stackalloc[CInt](2)
          if (unistd.pipe(fd) != 0)
            throw new IOException(fromCString(strerror(errno)))
          (fd(0), fd(1))
        }
      } { case (readFd, writeFd) =>
        IO {
          unistd.close(readFd)
          unistd.close(writeFd)
          ()
        }
      }
      .evalTap { case (readFd, writeFd) =>
        IO {
          if (fcntl(readFd, F_SETFL, O_NONBLOCK) != 0)
            throw new IOException(fromCString(strerror(errno)))
          if (fcntl(writeFd, F_SETFL, O_NONBLOCK) != 0)
            throw new IOException(fromCString(strerror(errno)))
        }
      }
      .flatMap { case (readFd, writeFd) =>
        Resource.eval(getFdPoller).flatMap { poller =>
          (
            poller.registerFileDescriptor(readFd, true, false),
            poller.registerFileDescriptor(writeFd, false, true)
          ).mapN(new Pipe(readFd, writeFd, _, _))
        }
      }

  test("notify read-ready events") {
    mkPipe.use { pipe =>
      for {
        buf <- IO(new Array[Byte](4))
        _ <- pipe.write(Array[Byte](1, 2, 3), 0, 3).background.surround(pipe.read(buf, 0, 3))
        _ <- pipe.write(Array[Byte](42), 0, 1).background.surround(pipe.read(buf, 3, 1))
        _ <- IO(assertEquals(buf.toList, List[Byte](1, 2, 3, 42)))
      } yield ()
    }
  }

  test("handle lots of simultaneous events") {
    mkPipe.replicateA(1000).use { pipes =>
      CountDownLatch[IO](1000).flatMap { latch =>
        pipes
          .traverse_ { pipe =>
            (pipe.read(new Array[Byte](1), 0, 1) *> latch.release).background
          }
          .surround {
            IO { // trigger all the pipes at once
              pipes.foreach { pipe =>
                unistd.write(pipe.writeFd, Array[Byte](42).at(0), 1.toULong)
              }
            }.background.surround(latch.await)
          }
      }
    }
  }

  test("hang if never ready") {
    mkPipe.use { pipe =>
      pipe.read(new Array[Byte](1), 0, 1).as(false).timeoutTo(1.second, IO.pure(true))
    }
  }

}
