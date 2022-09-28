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

import scala.scalanative.posix.sys.socket._
import scala.scalanative.unsafe._

private[uring] object sysun {
  import Nat._
  type _108 = Digit3[_1, _0, _8]

  type sockaddr_un = CStruct2[
    sa_family_t,
    CArray[CChar, _108]
  ]

}

private[uring] object sysunOps {
  import sysun._

  implicit final class sockaddr_unOps(sockaddr_un: Ptr[sockaddr_un]) extends AnyVal {
    def sun_family: sa_family_t = sockaddr_un._1
    def sun_family_=(sun_family: sa_family_t): Unit = sockaddr_un._1 = sun_family
    def sun_path: CArray[CChar, _108] = sockaddr_un._2
    def sun_path_=(sun_path: CArray[CChar, _108]): Unit = sockaddr_un._2 = sun_path
  }

}
