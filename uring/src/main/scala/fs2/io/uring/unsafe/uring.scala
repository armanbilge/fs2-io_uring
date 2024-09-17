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

import scala.scalanative.libc.stddef._
import scala.scalanative.posix.signal.sigset_t
import scala.scalanative.posix.sys.socket._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import scala.scalanative.runtime.Intrinsics._

@extern
private[uring] object uring {
  final val IORING_SETUP_SUBMIT_ALL = 1 << 7
  final val IORING_SETUP_COOP_TASKRUN = 1 << 8
  final val IORING_SETUP_TASKRUN_FLAG = 1 << 9
  final val IORING_SETUP_SINGLE_ISSUER = 1 << 12
  final val IORING_SETUP_DEFER_TASKRUN = 1 << 13

  final val IORING_OP_NOP = 0
  final val IORING_OP_POLL_ADD = 6
  final val IORING_OP_ACCEPT = 13
  final val IORING_OP_ASYNC_CANCEL = 14
  final val IORING_OP_CONNECT = 16
  final val IORING_OP_CLOSE = 19
  final val IORING_OP_SEND = 26
  final val IORING_OP_RECV = 27
  final val IORING_OP_SHUTDOWN = 34
  final val IORING_OP_SOCKET = 45

  type __u8 = CUnsignedChar
  type __u16 = CUnsignedShort
  type __s32 = CInt
  type __u32 = CUnsignedInt
  type __u64 = CUnsignedLongLong

  type __kernel_time64_t = CLongLong
  type __kernel_timespec = CStruct2[__kernel_time64_t, CLongLong]

  type __kernel_rwf_t = CUnsignedInt

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

  type io_uring_cq = CStruct12[
    Ptr[CUnsignedInt],
    Ptr[CUnsignedInt],
    Ptr[CUnsignedInt],
    Ptr[
      CUnsignedInt
    ],
    Ptr[CUnsignedInt],
    Ptr[CUnsignedInt],
    Ptr[io_uring_cqe],
    size_t,
    Ptr[
      Byte
    ],
    CUnsignedInt,
    CUnsignedInt,
    CArray[CUnsignedInt, Nat._2]
  ]

  type io_uring_cqe = CStruct3[__u64, __s32, __u32]

  type io_uring_sq = CStruct15[
    Ptr[CUnsignedInt],
    Ptr[CUnsignedInt],
    Ptr[CUnsignedInt],
    Ptr[
      CUnsignedInt
    ],
    Ptr[CUnsignedInt],
    Ptr[CUnsignedInt],
    Ptr[CUnsignedInt],
    Ptr[
      io_uring_sqe
    ],
    CUnsignedInt,
    CUnsignedInt,
    size_t,
    Ptr[Byte],
    CUnsignedInt,
    CUnsignedInt,
    CArray[
      CUnsignedInt,
      Nat._2
    ]
  ]

  type io_uring_sqe =
    CStruct10[__u8, __u8, __u16, __s32, __u64, __u64, __u32, __u32, __u64, CArray[__u64, Nat._3]]

  def io_uring_queue_init(entries: CUnsignedInt, ring: Ptr[io_uring], flags: CUnsignedInt): CInt =
    extern

  def io_uring_queue_exit(ring: Ptr[io_uring]): Unit = extern

  @name("fs2_io_uring_get_sqe")
  def io_uring_get_sqe(ring: Ptr[io_uring]): Ptr[io_uring_sqe] = extern

  def io_uring_submit(ring: Ptr[io_uring]): CInt = extern

  def io_uring_submit_and_wait_timeout(
      ring: Ptr[io_uring],
      cqe_ptr: Ptr[Ptr[io_uring_cqe]],
      wait_nr: CUnsignedInt,
      ts: Ptr[__kernel_timespec],
      sigmask: Ptr[sigset_t]
  ): CInt = extern

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

private[uring] object uringOps {

  import uring._

  def io_uring_prep_rw(
      op: Int,
      sqe: Ptr[io_uring_sqe],
      fd: Int,
      addr: Ptr[_],
      len: CUnsignedInt,
      offset: __u64
  ): Unit = {
    sqe.opcode = op.toUByte
    sqe.flags = 0.toUByte
    sqe.ioprio = 0.toUShort
    sqe.fd = fd
    sqe.off = offset
    sqe.addr = if (addr eq null) 0.toULong else addr.toLong.toULong
    sqe.len = len
    sqe.rw_flags = 0.toUInt
    sqe.__pad2(0) = 0.toULong
    sqe.__pad2(1) = 0.toULong
    sqe.__pad2(2) = 0.toULong
  }

  def io_uring_prep_nop(sqe: Ptr[io_uring_sqe]): Unit =
    io_uring_prep_rw(IORING_OP_NOP, sqe, -1, null, 0.toUInt, 0.toULong)

  def io_uring_prep_accept(
      sqe: Ptr[io_uring_sqe],
      fd: CInt,
      addr: Ptr[sockaddr],
      addrlen: Ptr[socklen_t],
      flags: CInt
  ): Unit = {
    io_uring_prep_rw(
      IORING_OP_ACCEPT,
      sqe,
      fd,
      addr,
      0.toUInt,
      if (addrlen eq null) 0.toULong else addrlen.toLong.toULong
    )
    sqe.accept_flags = flags.toUInt
  }

  def io_uring_prep_cancel64(sqe: Ptr[io_uring_sqe], user_data: __u64, flags: CInt): Unit = {
    io_uring_prep_rw(IORING_OP_ASYNC_CANCEL, sqe, -1, null, 0.toUInt, 0.toULong);
    sqe.addr = user_data;
    sqe.cancel_flags = flags.toUInt
  }

  def io_uring_prep_close(sqe: Ptr[io_uring_sqe], fd: CInt): Unit =
    io_uring_prep_rw(IORING_OP_CLOSE, sqe, fd, null, 0.toUInt, 0.toULong)

  def io_uring_prep_connect(
      sqe: Ptr[io_uring_sqe],
      fd: CInt,
      addr: Ptr[sockaddr],
      addrlen: socklen_t
  ): Unit = io_uring_prep_rw(IORING_OP_CONNECT, sqe, fd, addr, 0.toUInt, addrlen)

  def io_uring_prep_poll_add(
      sqe: Ptr[io_uring_sqe],
      fd: CInt,
      poll_mask: CUnsignedInt
  ): Unit = {
    io_uring_prep_rw(IORING_OP_POLL_ADD, sqe, fd, null, 0.toUInt, 0.toULong)
    sqe.poll32_events = poll_mask // TODO handle endianness
  }

  def io_uring_prep_recv(
      sqe: Ptr[io_uring_sqe],
      sockfd: CInt,
      buf: Ptr[Byte],
      len: size_t,
      flags: CInt
  ): Unit = {
    io_uring_prep_rw(IORING_OP_RECV, sqe, sockfd, buf, len.toUInt, 0.toULong)
    sqe.msg_flags = flags.toUInt
  }

  def io_uring_prep_send(
      sqe: Ptr[io_uring_sqe],
      sockfd: CInt,
      buf: Ptr[Byte],
      len: size_t,
      flags: CInt
  ): Unit = {
    io_uring_prep_rw(IORING_OP_SEND, sqe, sockfd, buf, len.toUInt, 0.toULong)
    sqe.msg_flags = flags.toUInt
  }

  def io_uring_prep_shutdown(sqe: Ptr[io_uring_sqe], fd: CInt, how: CInt): Unit =
    io_uring_prep_rw(IORING_OP_SHUTDOWN, sqe, fd, null, how.toUInt, 0.toULong)

  def io_uring_prep_socket(
      sqe: Ptr[io_uring_sqe],
      domain: CInt,
      `type`: CInt,
      protocol: CInt,
      flags: CUnsignedInt
  ): Unit = {
    io_uring_prep_rw(IORING_OP_SOCKET, sqe, domain, null, protocol.toUInt, `type`.toULong)
    sqe.rw_flags = flags.toUInt
  }

  def io_uring_sqe_set_data[A <: AnyRef](sqe: Ptr[io_uring_sqe], data: A): Unit =
    sqe.user_data = castRawPtrToLong(castObjectToRawPtr(data)).toULong

  def io_uring_cqe_get_data[A <: AnyRef](cqe: Ptr[io_uring_cqe]): A =
    castRawPtrToObject(castLongToRawPtr(cqe.user_data.toLong)).asInstanceOf[A]

  implicit final class io_uring_sqeOps(val io_uring_sqe: Ptr[io_uring_sqe]) extends AnyVal {
    def opcode: __u8 = io_uring_sqe._1
    def opcode_=(opcode: __u8): Unit = !io_uring_sqe.at1 = opcode

    def flags: __u8 = io_uring_sqe._2
    def flags_=(flags: __u8): Unit = !io_uring_sqe.at2 = flags

    def ioprio: __u16 = io_uring_sqe._3
    def ioprio_=(ioprio: __u16): Unit = !io_uring_sqe.at3 = ioprio

    def fd: __s32 = io_uring_sqe._4
    def fd_=(fd: __s32): Unit = !io_uring_sqe.at4 = fd

    def off: __u64 = io_uring_sqe._5
    def off_=(off: __u64): Unit = !io_uring_sqe.at5 = off

    def addr: __u64 = io_uring_sqe._6
    def addr_=(addr: __u64): Unit = !io_uring_sqe.at6 = addr

    def len: __u32 = io_uring_sqe._7
    def len_=(len: __u32): Unit = !io_uring_sqe.at7 = len

    def rw_flags: __kernel_rwf_t = io_uring_sqe._8
    def rw_flags_=(rw_flags: __kernel_rwf_t): Unit = !io_uring_sqe.at8 = rw_flags
    def poll32_events: __u32 = io_uring_sqe._8
    def poll32_events_=(poll32_events: __u32): Unit = !io_uring_sqe.at8 = poll32_events
    def msg_flags: __u32 = io_uring_sqe._8
    def msg_flags_=(msg_flags: __u32): Unit = !io_uring_sqe.at8 = msg_flags
    def accept_flags: __u32 = io_uring_sqe._8
    def accept_flags_=(accept_flags: __u32): Unit = !io_uring_sqe.at8 = accept_flags
    def cancel_flags: __u32 = io_uring_sqe._8
    def cancel_flags_=(cancel_flags: __u32): Unit = !io_uring_sqe.at8 = cancel_flags

    def user_data: __u64 = io_uring_sqe._9
    def user_data_=(user_data: __u64): Unit = !io_uring_sqe.at9 = user_data

    def __pad2: CArray[__u64, Nat._3] = io_uring_sqe._10
  }

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
