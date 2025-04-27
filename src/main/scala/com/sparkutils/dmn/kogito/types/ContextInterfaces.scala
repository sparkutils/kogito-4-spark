package com.sparkutils.dmn.kogito.types

import com.sparkutils.dmn.{DMNContextPath, DMNContextProvider, DMNException}
import com.sparkutils.dmn.impl.{SimpleContextProvider, StringContextProvider}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types.{ArrayType, BinaryType, BooleanType, ByteType, DataType, DateType, Decimal, DecimalType, DoubleType, FloatType, IntegerType, LongType, MapType, ShortType, StringType, StructType, TimestampType}
import sparkutilsKogito.com.fasterxml.jackson.annotation.{JsonIgnore, JsonIgnoreProperties}

import java.time.{LocalDate, LocalDateTime}
import scala.collection.JavaConverters._
import java.util

object ContextInterfaces {

  trait Accessor[T] extends Serializable {
    def forPath(path: Any): T
  }

  def forType(dataType: DataType, i: Int): Accessor[_] = dataType match {
    case structType: StructType =>
      val s = struct(structType.fields.zipWithIndex.map { case (f, i) => (f.name, forType(f.dataType, i)) }.toMap)
      new Accessor[util.Map[String, Object]] {
        override def forPath(path: Any): util.Map[String, Object] = {
          val r = path.asInstanceOf[InternalRow]
          val input = r.getStruct(i, structType.fields.size)
          s.forPath(input)
        }
      }
    case StringType => (path: Any) => path.asInstanceOf[InternalRow].getString(i).toString
    case IntegerType => (path: Any) => path.asInstanceOf[InternalRow].getInt(i)
    case LongType => (path: Any) => path.asInstanceOf[InternalRow].getLong(i)
    case BooleanType => (path: Any) => path.asInstanceOf[InternalRow].getBoolean(i)
    case DoubleType => (path: Any) => path.asInstanceOf[InternalRow].getDouble(i)
    case FloatType => (path: Any) => path.asInstanceOf[InternalRow].getFloat(i)
    case BinaryType => (path: Any) => path.asInstanceOf[InternalRow].getBinary(i)
    case ByteType => (path: Any) => path.asInstanceOf[InternalRow].getByte(i)
    case ShortType => (path: Any) => path.asInstanceOf[InternalRow].getShort(i)
    case DateType => (path: Any) => DateTimeUtils.daysToLocalDate( path.asInstanceOf[InternalRow].getInt(i) )
    case TimestampType => (path: Any) => DateTimeUtils.microsToLocalDateTime( path.asInstanceOf[InternalRow].getLong(i) )
    case _: DecimalType => (path: Any) =>
      // max needed as Spark's past 3.4 move everything to max anyway, 1.0 comes back as 1.0 instead of 2016...
      path.asInstanceOf[InternalRow].getDecimal(i, DecimalType.MAX_PRECISION, DecimalType.DEFAULT_SCALE).toJavaBigDecimal
    case ArrayType(typ, _) =>
      val entryAccessor = forType(typ, 0)
      (path: Any) => {
        val ar = Arrays.toArray(path.asInstanceOf[InternalRow].getArray(i), typ)
        ar.map { e =>
          val p = InternalRow(e)
          entryAccessor.forPath(p)
        }.toVector.asJava
      } // perhaps it supports Array?
    case MapType(k, v, _) => {
      val kAccessor = forType(k, 0)
      val vAccessor = forType(v, 0)
      (path: Any) => {
        val m = path.asInstanceOf[InternalRow].getMap(i)
        val map = (Arrays.toArray(m.keyArray(), k) zip Arrays.toArray(m.valueArray(), v)) map {
          case (k, v) =>
            val key = kAccessor.forPath(InternalRow(k))
            val value = vAccessor.forPath(InternalRow(v))
            key -> value
        }
        val mm = map.toMap
        mapAsJavaMap(mm)
      }
    }
    case _ => throw new DMNException(s"Could not load Kogito Context Accessor for dataType $dataType")
  }

  def mapProvider(mapType: MapType, path: DMNContextPath, expr: Expression): DMNContextProvider[util.Map[String, Object]] = {
    val sa = forType(mapType, 0)
    SimpleContextProvider[util.Map[String, Object]](path, expr, Some{t: Any =>
      sa.forPath(InternalRow(t)).asInstanceOf[util.Map[String, Object]]
    })
  }

  def arrayProvider(arrayType: ArrayType, path: DMNContextPath, expr: Expression): DMNContextProvider[util.List[Object]] = {
    val sa = forType(arrayType, 0)
    SimpleContextProvider[util.List[Object]](path, expr, Some{t: Any =>
      sa.forPath(InternalRow(t)).asInstanceOf[util.List[Object]]
    })
  }

  /**
   * Provides a map interface over underlying struct data
   * @param structType
   * @return
   */
  def structProvider(structType: StructType, path: DMNContextPath, expr: Expression): DMNContextProvider[util.Map[String, Object]] = {
    val sa = forType(structType, 0)
    SimpleContextProvider[util.Map[String, Object]](path, expr, Some{t: Any =>
      sa.forPath(InternalRow(t)).asInstanceOf[util.Map[String, Object]]
    })
  }

  def struct(pairs: Map[String, Accessor[_]]): Accessor[util.Map[String, Object]] =
    (path: Any) => new util.Map[String, Object] {

      override def get(key: Any): AnyRef = pairs(key.toString).forPath(path).asInstanceOf[AnyRef]

      // called by Jackson serializing
      override def entrySet(): util.Set[util.Map.Entry[String, Object]] = pairs.map{case (key, accessor) => new util.Map.Entry[String, Object]{
        override def getKey: String = key

        override def getValue: Object = accessor.forPath(path).asInstanceOf[AnyRef]

        override def setValue(value: Object): AnyRef = ???
      }}.toSet.asJava

      // Never called by kogito

      override def keySet(): util.Set[String] = pairs.keySet.asJava

      override def size(): Int = pairs.size

      override def isEmpty: Boolean = false

      override def containsKey(key: Any): Boolean = pairs.contains(key.toString)

      // Never being implemented

      override def containsValue(value: Any): Boolean = ???

      override def values(): util.Collection[Object] = ???

      override def put(key: String, value: Object): AnyRef = ???

      override def remove(key: Any): AnyRef = ???

      override def putAll(m: util.Map[_ <: String, _ <: Object]): Unit = ???

      override def clear(): Unit = ???
    }

}
