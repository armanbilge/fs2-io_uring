package fs2.io.uring.unsafe

import com.sun.jna.{Library, Native, Pointer}
import com.sun.jna.Memory

class Pipe {
  private val libc: CLibrary = Native.load("c", classOf[CLibrary])
  private val pipefd = Array.ofDim[Int](2)

  if (libc.pipe(pipefd) != 0) {
    throw new RuntimeException("Pipe creation failed!")
  }

  def write(message: String): Int = {
    val buffer: Pointer = new Memory(message.length + 1)
    buffer.setString(0, message)
    val bytesWritten = libc.write(pipefd(1), buffer, message.length)
    libc.close(pipefd(1))
    bytesWritten
  }

  def read(bufferSize: Int = 100): String = {
    val buffer: Pointer = new Memory(bufferSize)
    libc.read(pipefd(0), buffer, bufferSize)
    val message = buffer.getString(0)
    libc.close(pipefd(0))
    message
  }

  trait CLibrary extends Library {
    def pipe(pipefd: Array[Int]): Int
    def read(fd: Int, buf: Pointer, count: Int): Int
    def write(fd: Int, buf: Pointer, count: Int): Int
    def close(fd: Int): Int
  }
}
