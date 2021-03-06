package fr.acinq.eclair.wire

import fr.acinq.eclair.UInt64
import scodec.bits.ByteVector

trait Tlv

/** Generic tlv type we fallback to if we don't understand the incoming tlv.
  *
  * @param tag
  *   tlv tag.
  * @param value
  *   tlv value (length is implicit, and encoded as a varint).
  */
case class GenericTlv(tag: UInt64, value: ByteVector) extends Tlv

/** A tlv stream is a collection of tlv records. A tlv stream is constrained to
  * a specific tlv namespace that dictates how to parse the tlv records. That
  * namespace is provided by a trait extending the top-level tlv trait.
  *
  * @param records
  *   known tlv records.
  * @param unknown
  *   unknown tlv records.
  * @tparam T
  *   the stream namespace is a trait extending the top-level tlv trait.
  */
case class TlvStream[T <: Tlv](
    records: Iterable[T],
    unknown: Iterable[GenericTlv] = Nil
)

object TlvStream {
  type GenericTlvStream = TlvStream[Tlv]

  def empty[T <: Tlv]: TlvStream[T] = TlvStream[T](Nil, Nil)

  def apply[T <: Tlv](records: T*): TlvStream[T] = TlvStream(records, Nil)

  // Payment type

  final val paymentTag = UInt64(4127926135L)
}
