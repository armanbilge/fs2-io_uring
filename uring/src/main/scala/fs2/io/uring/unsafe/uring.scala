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

import scala.annotation.nowarn
import scala.scalanative.libc.stddef._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import scala.scalanative.runtime.Intrinsics._

@link("uring")
@extern
@nowarn
private[unsafe] object uring {
  type __u8 = CUnsignedChar
  type __u16 = CUnsignedShort
  type __s32 = CInt
  type __u32 = CUnsignedInt
  type __u64 = CUnsignedLongLong

  type __kernel_time64_t = CLongLong
  type __kernel_timespec = CStruct2[__kernel_time64_t, CLongLong]

  type io_uring = CStruct9[
    io_uring_sq,
    io_uring_cq,
    CUnsignedInt,
    CInt,
    CUnsignedInt,
    CInt,
    __u8,
    CArray[__u8, Nat._3],
    CUnsignedInt
  ]

  type io_uring_cq = CStruct12[Ptr[CUnsignedInt], Ptr[CUnsignedInt], Ptr[CUnsignedInt], Ptr[
    CUnsignedInt
  ], Ptr[CUnsignedInt], Ptr[CUnsignedInt], Ptr[io_uring_cqe], size_t, Ptr[
    Byte
  ], CUnsignedInt, CUnsignedInt, CArray[CUnsignedInt, Nat._2]]

  type io_uring_cqe = CStruct3[__u64, __s32, __u32]

  type io_uring_sq = CStruct15[Ptr[CUnsignedInt], Ptr[CUnsignedInt], Ptr[CUnsignedInt], Ptr[
    CUnsignedInt
  ], Ptr[CUnsignedInt], Ptr[CUnsignedInt], Ptr[CUnsignedInt], Ptr[
    io_uring_sqe
  ], CUnsignedInt, CUnsignedInt, size_t, Ptr[Byte], CUnsignedInt, CUnsignedInt, CArray[
    CUnsignedInt,
    Nat._2
  ]]

  type io_uring_sqe = CStruct7[__u8, __u8, __u16, __s32, __u32, __u64, __u16]

  def io_uring_queue_init(entries: CUnsignedInt, ring: Ptr[io_uring], flags: CUnsignedInt): CInt =
    extern

  def io_uring_queue_exit(ring: Ptr[io_uring]): Unit = extern

  def io_uring_submit(ring: Ptr[io_uring]): CInt = extern

  def io_uring_wait_cqe_timeout(
      ring: Ptr[io_uring],
      cqe_ptr: Ptr[Ptr[io_uring_cqe]],
      ts: Ptr[__kernel_timespec]
  ): CInt = extern

  def io_uring_peek_batch_cqe(
      ring: Ptr[io_uring],
      cqes: Ptr[Ptr[io_uring_cqe]],
      count: CUnsignedInt
  ): CUnsignedInt = extern

  @name("fs2_io_uring_cq_advance")
  def io_uring_cq_advance(ring: Ptr[io_uring], nr: CUnsignedInt): Unit = extern

}

private[unsafe] object uringOps {

  import uring._

  def io_uring_cqe_set_data[A <: AnyRef](cqe: Ptr[io_uring_cqe], data: A): Unit =
    cqe.user_data = castRawPtrToLong(castObjectToRawPtr(data)).toULong

  def io_uring_cqe_get_data[A <: AnyRef](cqe: Ptr[io_uring_cqe]): A =
    castRawPtrToObject(castLongToRawPtr(cqe.user_data.toLong)).asInstanceOf[A]

  implicit final class io_uring_cqeOps(val io_uring_cqe: Ptr[io_uring_cqe]) extends AnyVal {
    def user_data: __u64 = io_uring_cqe._1
    def user_data_=(user_data: __u64): Unit = !io_uring_cqe.at1 = user_data
    def res: __s32 = io_uring_cqe._2
    def res_=(res: __s32): Unit = !io_uring_cqe.at2 = res
    def flags: __u32 = io_uring_cqe._3
    def flags_=(flags: __u32): Unit = !io_uring_cqe.at3 = flags
  }

  implicit final class __kernel_timespecOps(val __kernel_timespec: Ptr[__kernel_timespec])
      extends AnyVal {
    def tv_sec: __kernel_time64_t = __kernel_timespec._1
    def tv_sec_=(tv_sec: __kernel_time64_t): Unit = !__kernel_timespec.at1 = tv_sec
    def tv_nsec: CLongLong = __kernel_timespec._2
    def tv_nsec_=(tv_nsec: CLongLong): Unit = !__kernel_timespec.at2 = tv_nsec
  }

}
