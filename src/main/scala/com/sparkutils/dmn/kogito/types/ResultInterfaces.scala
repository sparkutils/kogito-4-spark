package com.sparkutils.dmn.kogito.types

import com.sparkutils.dmn.DMNException
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.{ArrayBasedMapData, DateTimeUtils, GenericArrayData}
import org.apache.spark.sql.types.{ArrayType, BinaryType, BooleanType, ByteType, DataType, DateType, Decimal, DecimalType, DoubleType, FloatType, IntegerType, LongType, MapType, ShortType, StringType, StructType, TimestampType}
import org.apache.spark.unsafe.types.UTF8String

import java.time.{LocalDate, LocalDateTime}
import java.util
import scala.collection.JavaConverters._

/**
 * Reverse the logic from ContextInterfaces
 */
object ResultInterfaces {

  val evalStatusEnding = "_dmnEvalStatus"

  val NOT_EVALUATED: Byte = -5.toByte // shouldn't happen
  val EVALUATING: Byte = -4.toByte // shouldn't happen as it'll be overwritten in KogitoDDLResult
  val SUCCEEDED: Byte  = 1.toByte
  val SKIPPED_WARN: Byte  = -3.toByte
  val SKIPPED_ERROR: Byte  = -2.toByte
  val FAILED: Byte  = 0.toByte

  trait Getter {
    def get(path: Any): Any
  }

  def forType(dataType: DataType): Getter = dataType match {
    case structType: StructType =>

      val s= structType.fields.map { f =>
        (f.name,
          if (f.name.endsWith(evalStatusEnding))
            ((path: Any) => EVALUATING): Getter
          else
            forType(f.dataType)
        )
      }

      (path: Any) => {
        val m = path.asInstanceOf[util.Map[String, Object]]
        InternalRow(s.map { case (name, g) => g.get(m.get(name)) }: _*)
      }
    case StringType => (path: Any) => UTF8String.fromString( path.toString )
    case IntegerType => (path: Any) => path.asInstanceOf[Integer]
    case LongType => (path: Any) => path.asInstanceOf[Long]
    case BooleanType => (path: Any) => path.asInstanceOf[Boolean]
    case DoubleType => (path: Any) => path.asInstanceOf[Double]
    case FloatType => (path: Any) => path.asInstanceOf[Float]
    case BinaryType => (path: Any) => path.asInstanceOf[Array[Byte]]
    case ByteType => (path: Any) => path.asInstanceOf[Byte]
    case ShortType => (path: Any) => path.asInstanceOf[Short]
    case DateType => (path: Any) => DateTimeUtils.localDateToDays( path.asInstanceOf[LocalDate] )
    case TimestampType => (path: Any) => DateTimeUtils.localDateTimeToMicros( path.asInstanceOf[LocalDateTime] )
    case _: DecimalType => (path: Any) =>
      Decimal.apply(path.asInstanceOf[java.math.BigDecimal])
    case ArrayType(typ, _) =>
      val g = forType(typ)
      (path: Any) => {
        val a = path.asInstanceOf[util.List[_]].toArray.map(g.get(_))
        new GenericArrayData(a)
      }
    case MapType(k, v, _) =>
      val kG = forType(k)
      val vG = forType(v)
      (path: Any) => {
        val m = path.asInstanceOf[util.Map[Object, Object]].asScala.toMap.map(e => kG.get(e._1) -> vG.get(e._2))
        new ArrayBasedMapData(new GenericArrayData(m.keys.toArray), new GenericArrayData(m.values.toArray))
      }
    case _ => throw new DMNException(s"Could not load Kogito Result Provider for dataType $dataType")
  }

}
