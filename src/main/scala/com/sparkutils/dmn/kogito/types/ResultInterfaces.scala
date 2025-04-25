package com.sparkutils.dmn.kogito.types

import com.sparkutils.dmn.{DMNContextPath, DMNContextProvider, DMNException}
import com.sparkutils.dmn.impl.SimpleContextProvider
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types.{ArrayType, BinaryType, BooleanType, ByteType, DataType, DateType, DecimalType, DoubleType, FloatType, IntegerType, LongType, MapType, ShortType, StringType, StructType, TimestampType}

import java.util
import scala.collection.JavaConverters.mapAsJavaMap

/**
 * Reverse the logic from ContextInterfaces, either it's basic types or
 */
object ResultInterfaces {
/*
  trait Writer[T] extends Serializable {
    def forPath(path: T): AnyRef
  }

  def forType(dataType: DataType, i: Int): Writer[_] = dataType match {
    case structType: StructType =>
      val s = struct(structType.fields.zipWithIndex.map{case (f, i) => (f.name, forType(f.dataType, i))}.toMap)
      new Writer[util.Map[String, Object]] {
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
    case dt: DecimalType => (path: Any) => path.asInstanceOf[InternalRow].getDecimal(i, dt.precision, dt.scale).toJavaBigDecimal
    case ArrayType(typ, _) =>
      val entryAccessor = forType(typ, 1)
      (path: Any) => {
        val ar = path.asInstanceOf[InternalRow].getArray(i).array
        ar.map { e =>
          val p = InternalRow(e)
          entryAccessor.forPath(p)
        }
      } // perhaps it supports Array?
    case MapType(k, v, _) => {
      val kAccessor = forType(k, 1)
      val vAccessor = forType(v, 1)
      (path: Any) => {
        val m = path.asInstanceOf[InternalRow].getMap(i)
        val map = (m.keyArray().array zip m.valueArray().array) map {
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

  /**
   * Provides a map interface over underlying struct data
   * @param structType
   * @return
   */
  def structProvider(structType: StructType, path: DMNContextPath, expr: Expression): DMNContextProvider[java.util.Map[String, Object]] = {
    val structAccessor = struct(structType.fields.zipWithIndex.map{case (f, i) => (f.name, forType(f.dataType, i))}.toMap)
    SimpleContextProvider[java.util.Map[String, Object]](path, expr, Some{t: Any => structAccessor.forPath(t) })
  }

  def struct(outputs: Seq[(String, Writer[_])]): Writer[util.Map[String, Object]] = (path: util.Map[String, Object]) => {
    InputRow
    ???
  }


    new util.Map[String, Object] {

    override def get(key: Any): AnyRef = pairs(key.toString.toUpperCase).forPath(path).asInstanceOf[AnyRef]

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

*/
}
