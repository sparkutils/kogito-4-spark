package com.sparkutils.dmn.kogito

import frameless.TypedEncoder
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoder
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.{LENIENT_LOCAL_DATE_ENCODER, LocalDateTimeEncoder}

import java.time.{LocalDate, LocalDateTime}

trait EncodingTestUtils {

  implicit val sqlDate: TypedEncoder[LocalDate] =  new TypedEncoder[LocalDate] {

    override def agnosticEncoder: AgnosticEncoder[LocalDate] = LENIENT_LOCAL_DATE_ENCODER
  }

  implicit val timestampEncoder: TypedEncoder[LocalDateTime] = new TypedEncoder[LocalDateTime] {
    override def agnosticEncoder: AgnosticEncoder[LocalDateTime] = LocalDateTimeEncoder
  }
}
