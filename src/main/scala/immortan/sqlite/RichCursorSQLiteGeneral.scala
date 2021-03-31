package immortan.sqlite

import immortan.crypto.Tools.Bytes
import java.sql.ResultSet
import scala.util.Try


case class RichCursorSQLiteGeneral(rs: ResultSet) extends RichCursor { me =>
  def iterable[T](transform: RichCursor => T): Iterable[T] = try map(transform) finally rs.close

  def set[T](transform: RichCursor => T): Set[T] = try map(transform).toSet finally rs.close

  def headTry[T](fun: RichCursor => T): Try[T] = try Try(fun apply head) finally rs.close

  def bytes(key: String): Bytes = rs.getBytes(key)

  def string(key: String): String = rs.getString(key)

  def long(key: String): Long = rs.getLong(key)

  def long(pos: Int): Long = rs.getLong(pos)

  def int(key: String): Int = rs.getInt(key)

  // Important: this can only be iterated over ONCE
  def iterator: Iterator[RichCursor] = new Iterator[RichCursor] {
    def hasNext: Boolean = rs.next
    def next: RichCursor = me
  }
}