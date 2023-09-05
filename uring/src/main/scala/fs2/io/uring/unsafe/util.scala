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

package fs2.io.uring.unsafe

import scala.scalanative.libc.string._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

private[uring] object util {

  def toPtr(bytes: Array[Byte], ptr: Ptr[Byte]): Unit = {
    memcpy(ptr, bytes.atUnsafe(0), bytes.length.toUInt)
    ()
  }

  def toArray(ptr: Ptr[Byte], length: Int): Array[Byte] = {
    val bytes = new Array[Byte](length)
    memcpy(bytes.atUnsafe(0), ptr, length.toUInt)
    bytes
  }

}
