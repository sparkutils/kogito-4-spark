package com.sparkutils.dmn.kogito

import frameless.TypedEncoder
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.shim.StaticInvoke4
import org.apache.spark.sql.types.{DataType, DateType, ObjectType, TimestampNTZType, TimestampType}

import java.time.{LocalDate, LocalDateTime}

trait EncodingTestUtils {

  implicit val sqlDate: TypedEncoder[LocalDate] = new TypedEncoder[LocalDate] {
    def nullable: Boolean = false

    def jvmRepr: DataType = ObjectType(classOf[LocalDate])
    def catalystRepr: DataType = DateType

    def toCatalyst(path: Expression): Expression =
      StaticInvoke4(
        DateTimeUtils.getClass,
        DateType,
        "localDateToDays",
        path :: Nil,
        returnNullable = false)

    def fromCatalyst(path: Expression): Expression =
      StaticInvoke4(
        DateTimeUtils.getClass,
        ObjectType(classOf[java.time.LocalDate]),
        "daysToLocalDate",
        path :: Nil,
        returnNullable = false)
  }

  implicit val timestampEncoder: TypedEncoder[LocalDateTime] =
    new TypedEncoder[LocalDateTime] {
      def nullable: Boolean = false

      def jvmRepr: DataType = ObjectType(classOf[LocalDateTime])
      def catalystRepr: DataType = TimestampType

      def toCatalyst(path: Expression): Expression =
        StaticInvoke4(
          DateTimeUtils.getClass,
          TimestampNTZType,
          "localDateTimeToMicros",
          path :: Nil,
          returnNullable = false)

      def fromCatalyst(path: Expression): Expression =
        StaticInvoke4(
          DateTimeUtils.getClass,
          ObjectType(classOf[java.time.LocalDateTime]),
          "microsToLocalDateTime",
          path :: Nil,
          returnNullable = false)

      override def toString: String = "timestampEncoder"
    }
}
