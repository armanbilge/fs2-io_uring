package fs2.io.uring

import fs2.io.uring.unsafe.util.OP._

trait Operation {
  val op: Byte
  val flags: Int = 0
  val rwFlags: Int = 0
  val fd: Int = 0
  val bufferAddress: Long = 0
  val length: Int = 0
  val offset: Long = 0
}

case class noOperation() extends Operation {
  override val op: Byte = IORING_OP_NOP
}

case class send(
    override val flags: Int,
    override val fd: Int,
    override val bufferAddress: Long,
    override val length: Int
) extends Operation {
  override val op: Byte = IORING_OP_SEND
}

case class recv(
    override val flags: Int,
    override val fd: Int,
    override val bufferAddress: Long,
    override val length: Int
) extends Operation {
  override val op: Byte = IORING_OP_RECV
}

case class shutdown(
    override val fd: Int,
    override val length: Int
) extends Operation {
  override val op: Byte = IORING_OP_SHUTDOWN
}

case class accept(override val fd: Int, override val bufferAddress: Long, override val offset: Long)
    extends Operation {
  override val op: Byte = IORING_OP_ACCEPT
}

case class connect(
    override val fd: Int,
    override val bufferAddress: Long,
    override val offset: Long
) extends Operation {

  override val op: Byte = IORING_OP_CONNECT
}

case class open(override val fd: Int, override val length: Int, override val offset: Long)
    extends Operation {
  override val op: Byte = IORING_OP_SOCKET

}
case class close(override val fd: Int) extends Operation {
  override val op: Byte = IORING_OP_CLOSE
}


