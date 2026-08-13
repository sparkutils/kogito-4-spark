package com.sparkutils.dmn.kogito

import frameless.{Injection, TypedEncoder}
import org.apache.spark.sql.catalyst.encoders.{AgnosticEncoder, Codec}
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.{LENIENT_LOCAL_DATE_ENCODER, LocalDateTimeEncoder, STRICT_TIMESTAMP_ENCODER, YearMonthIntervalEncoder}
import org.apache.spark.sql.types.YearMonthIntervalType

import java.time.{LocalDate, LocalDateTime}
import java.{sql => jsql}

trait EncodingTestUtils {

  implicit val sqlDate: TypedEncoder[LocalDate] = new TypedEncoder[LocalDate] {

    override def agnosticEncoder: AgnosticEncoder[LocalDate] = LENIENT_LOCAL_DATE_ENCODER
  }

  implicit val timestampEncoder: TypedEncoder[jsql.Timestamp] = new TypedEncoder[jsql.Timestamp] {
    override def agnosticEncoder: AgnosticEncoder[jsql.Timestamp] = STRICT_TIMESTAMP_ENCODER
  }

  // need the codec because the type for LocalDateTime is not an exact instant but TimestampNTZType
  implicit val localDateTimeCodec = frameless.InjectionCodecs.codec[LocalDateTime, jsql.Timestamp](
    ld => jsql.Timestamp.valueOf(ld), jt => jt.toLocalDateTime)

  implicit val yearMonthIntervalType = new TypedEncoder[java.time.Period]() {
    override def agnosticEncoder: AgnosticEncoder[java.time.Period] = YearMonthIntervalEncoder
  }
}
