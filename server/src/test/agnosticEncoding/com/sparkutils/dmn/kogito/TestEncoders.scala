package com.sparkutils.dmn.kogito

import frameless.TypedEncoder
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoder
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.{LENIENT_LOCAL_DATE_ENCODER, STRICT_TIMESTAMP_ENCODER, YearMonthIntervalEncoder}

import java.time.{LocalDate, LocalDateTime}
import java.{sql => jsql}


trait TestEncoders {

  implicit val sqlDate: TypedEncoder[LocalDate] = new TypedEncoder[LocalDate] {

    override def agnosticEncoder: AgnosticEncoder[LocalDate] = LENIENT_LOCAL_DATE_ENCODER
  }

  implicit val timestampEncoder: TypedEncoder[jsql.Timestamp] = new TypedEncoder[jsql.Timestamp] {
    override def agnosticEncoder: AgnosticEncoder[jsql.Timestamp] = STRICT_TIMESTAMP_ENCODER
  }

  // also need null handling for connect / arrow usage
  // need the codec because the type for LocalDateTime is not an exact instant but TimestampNTZType
  implicit val localDateTimeCodec = frameless.InjectionCodecs.codec[LocalDateTime, jsql.Timestamp](
    ld => if (ld == null) null else jsql.Timestamp.valueOf(ld),
    jt => if (jt == null) null else jt.toLocalDateTime)

  implicit val yearMonthIntervalType = new TypedEncoder[java.time.Period]() {
    override def agnosticEncoder: AgnosticEncoder[java.time.Period] = YearMonthIntervalEncoder
  }
}
