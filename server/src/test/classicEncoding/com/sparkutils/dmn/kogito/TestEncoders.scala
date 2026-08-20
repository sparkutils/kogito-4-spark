package com.sparkutils.dmn.kogito

import frameless.TypedEncoder
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.expressions.objects.StaticInvoke
import org.apache.spark.sql.catalyst.util.{DateTimeUtils, IntervalUtils}
import org.apache.spark.sql.shim.StaticInvoke4
import org.apache.spark.sql.types.{CalendarIntervalType, DataType, DateType, ObjectType, TimestampNTZType, TimestampType, YearMonthIntervalType}
import org.drools.modelcompiler.dsl.pattern.D

import java.time.{LocalDate, LocalDateTime}

trait TestEncoders {


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

  implicit val yearMonthIntervalTypeEncoder: TypedEncoder[java.time.Period] =
    new TypedEncoder[java.time.Period]() {
      override def nullable: Boolean = false

      override def jvmRepr: DataType = ObjectType(classOf[java.time.Period])

      override def catalystRepr: DataType = YearMonthIntervalType()

      override def fromCatalyst(path: Expression): Expression =
        StaticInvoke4(
          IntervalUtils.getClass,
          YearMonthIntervalType(),
          "periodToMonths",
          path :: Nil,
          returnNullable = false)

      override def toCatalyst(path: Expression): Expression =
        StaticInvoke4(
          IntervalUtils.getClass,
          ObjectType(classOf[java.time.Period]),
          "monthsToPeriod",
          path :: Nil,
          returnNullable = false)
    }
}
