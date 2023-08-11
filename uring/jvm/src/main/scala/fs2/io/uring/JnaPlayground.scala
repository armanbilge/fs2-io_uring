package fs2.io.uring

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Memory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

trait CLibrary extends Library {
  def printf(format: String): Int
}

trait CPipe extends Library {
  def pipe(pipefd: Array[Int]): Int
  def read(fd: Int, buf: Pointer, count: Int): Int
  def write(fd: Int, buf: Pointer, count: Int): Int
  def close(fd: Int): Int
}

object JnaPlayground extends App {
  val printer: CLibrary = Native.load("c", classOf[CLibrary])
  printer.printf("Hello, JNA from Scala!")

  val libc: CPipe = Native.load("c", classOf[CPipe])
  val BUFFER_SIZE = 100
  val pipefd = new Array[Int](2)

  if (libc.pipe(pipefd) != 0) {
    throw new RuntimeException("Pipe creation failed!")
  }

  val reader = Future {
    val buffer: Pointer = new Memory(BUFFER_SIZE)
    libc.read(pipefd(0), buffer, BUFFER_SIZE)
    val message = buffer.getString(0)
    libc.close(pipefd(0))
    message
  }

  val writer = Future {
    val message = "Hello from writer thread!"
    val buffer: Pointer = new Memory(message.length + 1)
    buffer.setString(0, message)
    println("waiting 2 sec before writing...")
    Thread.sleep(2000)
    libc.write(pipefd(1), buffer, message.length)
    libc.close(pipefd(1))
  }

  Await.ready(writer, 10.seconds)
  println(s"Reader received: '${Await.result(reader, 10.seconds)}'")

}
