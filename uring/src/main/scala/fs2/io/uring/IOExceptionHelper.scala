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
import scala.scalanative.posix.string._
import scala.scalanative.unsafe._

private[uring] object IOExceptionHelper {

  def apply(errno: Int): IOException = errno match {
    case 98 => // EADDRINUSE
      new BindException("Address already in use")
    case 99 => // EADDRNOTAVAIL
      new BindException("Cannot assign requested address")
    case 111 => // ECONNREFUSED
      new ConnectException("Connection refused")
    case _ => new IOException(fromCString(strerror(errno)))
  }

}
