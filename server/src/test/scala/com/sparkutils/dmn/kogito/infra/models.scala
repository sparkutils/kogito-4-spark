package com.sparkutils.dmn.kogito.infra // separate package to make sure we don't include kogito server impl in connect tests

import com.sparkutils.dmn.DMNInputField
import com.sparkutils.dmn.kogito.types.{Utils => U}
import com.sparkutils.dmn.kogito.{KogitoMessage, KogitoResult}

import java.time.temporal.ChronoUnit
import java.time.{LocalDate, LocalDateTime, ZoneOffset}

case class Pair(a: Boolean, b: Boolean) extends Serializable
case class Deep[A,B](a: String, b: Option[java.math.BigDecimal], d: Pair, c: Option[Map[A,B]]) extends Serializable {
  override def equals(obj: Any): Boolean = obj match {
    // precision isn't correct in frameless encoding
    case o: Deep[A,B] => a == o.a /* && b == o.b */ && d == o.d && c == o.c
    case _ => false
  }
}
case class Top[A,B](top1: String, strings: Seq[String], structs: Seq[Deep[A, B]]) extends Serializable

case class Wrapper[A,B](top: Top[A,B]) extends Serializable

case class Result[A,B](eval: Top[A,B]) extends Serializable

case class Quality[A,B](quality: Result[A,B]) extends Serializable

case class DebugResult[A,B](eval: Top[A,B], dmnDebugMode: Seq[KogitoResult], messages: Seq[KogitoMessage]) extends Serializable

case class DebugQuality[A,B](quality: DebugResult[A,B]) extends Serializable

case class Others(s: Option[String], l: Option[Long], b: Option[Boolean], d: Option[Double],
                  f: Option[Float], by: Option[Byte], bytes: Option[Array[Byte]],
                  sh: Option[Short], date: Option[LocalDate], dateTime: Option[LocalDateTime],
                  m: Option[Map[Int, Int]], ar: Option[Seq[Int]], bd: Option[java.math.BigDecimal]
                 ) extends Serializable {
  override def equals(obj: Any): Boolean = obj match {
    // precision isn't correct in frameless encoding
    case o: Others =>
      s == o.s && l == o.l && b == o.b && d == o.d && f == o.f && by == o.by &&
        sh == o.sh && date == o.date &&
        U.optEqual(dateTime, o.dateTime)(_.truncatedTo(ChronoUnit.MICROS) == _.truncatedTo(ChronoUnit.MICROS)) &&
        U.optEqual(bytes, o.bytes)(_ sameElements _) && m == o.m && ar == o.ar
    case _ => false
  }
}

object Others {
  val ddl = s"struct<s: String, l: Long, b: Boolean, d: Double, f: Float, " +
    s"by: Byte, bytes: Binary, sh: Short, date: Date, dateTime: timestamp, " +
    s"m: Map<int,int>, ar: array<int>, bd: decimal(10,1)>"

  val fields = scala.collection.immutable.Seq(
    DMNInputField("s", "", "inputData.s"),
    DMNInputField("l", "", "inputData.l"),
    DMNInputField("b", "", "inputData.b"),
    DMNInputField("d", "", "inputData.d"),
    DMNInputField("f", "", "inputData.f"),
    DMNInputField("by", "", "inputData.by"),
    DMNInputField("bytes", "", "inputData.bytes"),
    DMNInputField("sh", "", "inputData.sh"),
    DMNInputField("date", "", "inputData.date"),
    DMNInputField("dateTime", "", "inputData.dateTime"),
    DMNInputField("m", "", "inputData.m"),
    DMNInputField("ar", "", "inputData.ar"),
    DMNInputField("bd", "", "inputData.bd"),
  )

  val struct = scala.collection.immutable.Seq(
    DMNInputField("struct(*)", "", "inputData"),
  )

  val date = LocalDate.now()
  val dateTime = LocalDateTime.now(ZoneOffset.UTC)

  val bd = Some(java.math.BigDecimal.valueOf(1.0))

  val nulls = Others(None,None,None,None,None,None,None,None,None,None,None,None,None)
  val vals = Others(Some(""),Some(1L),Some(true),Some(0.2),Some(0.2f),Some(0),
    Some(Array(0: Byte)),Some(1),Some(date), Some(dateTime), Some(Map(1 -> 1)), Some(Array(1,2)),
    bd)
}