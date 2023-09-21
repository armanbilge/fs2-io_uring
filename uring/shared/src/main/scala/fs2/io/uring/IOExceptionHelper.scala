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

import java.io.IOException
import java.net.ConnectException
import java.net.BindException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.NoRouteToHostException

private[uring] object IOExceptionHelper {

  def apply(errno: Int): IOException = errno match {
    case 9 => // EBADF
      new IOException("Bad file descriptor")

    case 11 => // EAGAIN
      new IOException("Resource temporarily unavailable")

    case 13 => // EACCES
      new IOException("Permission denied")

    case 14 => // EFAULT
      new IOException("Bad address")

    case 22 => // EINVAL
      new IOException("Invalid argument")

    case 24 => // EMFILE
      new IOException("Too many open files")

    case 28 => // ENOSPC
      new IOException("No space left on device")

    case 32 => // EPIPE
      new IOException("Broken pipe")

    case 98 => // EADDRINUSE
      new BindException("Address already in use")

    case 99 => // EADDRNOTAVAIL
      new BindException("Cannot assign requested address")

    case 107 => // ECONNABORTED
      new SocketException("Connection aborted")

    case 110 => // ETIMEDOUT
      new SocketTimeoutException("Connection timed out")

    case 111 => // ECONNREFUSED
      new ConnectException("Connection refused")

    case 113 => // EHOSTUNREACH
      new NoRouteToHostException("No route to host")

    case 104 => // ECONNRESET
      new SocketException("Connection reset by peer")

    case _ => new IOException(errno.toString)
  }
}
