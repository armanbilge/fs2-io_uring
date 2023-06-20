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

package io.netty.incubator.channel.uring

/** The UringCompletionQueueCallback trait defines a callback interface for handling completion events
  * from the io_uring completion queue. It extends the IOUringCompletionQueueCallback trait and provides
  * a method handle to process the completion event.
  */
trait UringCompletionQueueCallback extends IOUringCompletionQueueCallback {
  def handle(fd: Int, res: Int, flags: Int, op: Byte, data: Short): Unit
}
