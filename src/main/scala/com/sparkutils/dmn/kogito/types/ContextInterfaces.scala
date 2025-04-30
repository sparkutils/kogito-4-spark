package com.sparkutils.dmn.kogito.types

import com.sparkutils.dmn.{DMNContextPath, DMNContextProvider, DMNException}
import com.sparkutils.dmn.impl.{SimpleContextProvider, StringContextProvider}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeGenerator, CodegenContext, CodegenFallback, ExprCode}
import org.apache.spark.sql.catalyst.expressions.{Expression, SpecializedGetters, UnaryExpression}
import org.apache.spark.sql.catalyst.util.{ArrayData, DateTimeUtils, MapData}
import org.apache.spark.sql.types.{ArrayType, BinaryType, BooleanType, ByteType, DataType, DateType, Decimal, DecimalType, DoubleType, FloatType, IntegerType, LongType, MapType, ShortType, StringType, StructType, TimestampType}
import sparkutilsKogito.com.fasterxml.jackson.annotation.{JsonIgnore, JsonIgnoreProperties}

import java.time.{LocalDate, LocalDateTime}
import scala.collection.JavaConverters._
import java.util
import scala.reflect.{ClassTag, classTag}

object ContextInterfaces {

  trait Accessor[T] extends Serializable {
    def forPath(path: Any, i: Int): T
  }

  // -1 as top level field, only for struct/map/array
  def forType(dataType: DataType): Accessor[_] = dataType match {
    case structType: StructType =>
      val s = struct(structType.fields.zipWithIndex.map { case (f, i) => (f.name, (i, forType(f.dataType))) }.toMap)
      new Accessor[util.Map[String, Object]] {
        override def forPath(path: Any, i: Int): util.Map[String, Object] = {
          if (path == null) null else {
            val r = path.asInstanceOf[SpecializedGetters]
            val input =
              if (i == -1)
                r
              else
                r.getStruct(i, structType.fields.size)
            s.forPath(input, i)
          }
        }
      }
    case StringType => (path: Any, i: Int) => if (path == null) null else path.asInstanceOf[SpecializedGetters].getUTF8String(i).toString
    case IntegerType => (path: Any, i: Int) => if (path == null) null else path.asInstanceOf[SpecializedGetters].getInt(i)
    case LongType => (path: Any, i: Int) => if (path == null) null else path.asInstanceOf[SpecializedGetters].getLong(i)
    case BooleanType => (path: Any, i: Int) => if (path == null) null else path.asInstanceOf[SpecializedGetters].getBoolean(i)
    case DoubleType => (path: Any, i: Int) => if (path == null) null else path.asInstanceOf[SpecializedGetters].getDouble(i)
    case FloatType => (path: Any, i: Int) => if (path == null) null else path.asInstanceOf[SpecializedGetters].getFloat(i)
    case BinaryType => (path: Any, i: Int) => if (path == null) null else path.asInstanceOf[SpecializedGetters].getBinary(i)
    case ByteType => (path: Any, i: Int) => if (path == null) null else path.asInstanceOf[SpecializedGetters].getByte(i)
    case ShortType => (path: Any, i: Int) => if (path == null) null else path.asInstanceOf[SpecializedGetters].getShort(i)
    case DateType => (path: Any, i: Int) => if (path == null) null else DateTimeUtils.daysToLocalDate( path.asInstanceOf[SpecializedGetters].getInt(i) )
    case TimestampType => (path: Any, i: Int) => if (path == null) null else DateTimeUtils.microsToLocalDateTime( path.asInstanceOf[SpecializedGetters].getLong(i) )
    case _: DecimalType => (path: Any, i: Int) =>
      // max needed as Spark's past 3.4 move everything to max anyway, 1.0 comes back as 1.0 instead of 2016...
      if (path == null) null else path.asInstanceOf[SpecializedGetters].getDecimal(i, DecimalType.MAX_PRECISION, DecimalType.DEFAULT_SCALE).toJavaBigDecimal
    case ArrayType(typ, _) =>
      val entryAccessor = forType(typ)
      (path: Any, i: Int) => {
        if (path == null) null else {
          val ar = {
            if (i == -1)
              path.asInstanceOf[ArrayData]
            else
              path.asInstanceOf[SpecializedGetters].getArray(i)
          }
          arrayOfType(entryAccessor, ar).asJava
        }
      }
    case MapType(k, v, _) => {
      val kAccessor = forType(k)
      val vAccessor = forType(v)
      (path: Any, i: Int) => {
        if (path == null) null else {
          val m =
            if (i == -1)
              path.asInstanceOf[MapData]
            else
              path.asInstanceOf[SpecializedGetters].getMap(i)
          val ka = arrayOfType(kAccessor, m.keyArray())
          val va = arrayOfType(vAccessor, m.valueArray())
          (ka zip va).toMap.asJava
        }
      }
    }
    case _ => throw new DMNException(s"Could not load Kogito Context Accessor for dataType $dataType")
  }

  private def arrayOfType(entryAccessor: Accessor[_], ar: ArrayData) =
    for {i <- 0 until ar.numElements()}
      yield entryAccessor.forPath(ar, i)

  // NOTE -1 must be provided for top level as we don't know the index the data is taken from

  def mapProvider(mapType: MapType, path: DMNContextPath, expr: Expression): DMNContextProvider[util.Map[String, Object]] = {
    val ma = forType(mapType)
    ComplexContextProvider[util.Map[String, Object]](ma, path, expr)
  }

  def arrayProvider(arrayType: ArrayType, path: DMNContextPath, expr: Expression): DMNContextProvider[util.List[Object]] = {
    val aa = forType(arrayType)
    ComplexContextProvider[util.List[Object]](aa, path, expr)
  }

  /**
   * Provides a map interface over underlying struct data
   * @param structType
   * @return
   */
  def structProvider(structType: StructType, path: DMNContextPath, expr: Expression): DMNContextProvider[util.Map[String, Object]] = {
    val sa = forType(structType)
    ComplexContextProvider[util.Map[String, Object]](sa, path, expr)
  }

  case class ComplexContextProvider[T: ClassTag](accessor: Accessor[_], contextPath: DMNContextPath, child: Expression) extends UnaryExpression with DMNContextProvider[T] with CodegenFallback {

    def withNewChildInternal(newChild: Expression): Expression = copy(child = newChild)

    override def nullSafeEval(input: Any): Any = {
      // TODO - compile the -1 logic out
      (contextPath, accessor.forPath(input, -1).asInstanceOf[T])
    }

    /**
     * Result class type
     */
    override val resultType: Class[T] = classTag[T].runtimeClass.asInstanceOf[Class[T]]
/*
    override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
      val (contextClassName, contextPath) = genContext(ctx)
      val rClassName = resultType.getName

      val boxed = CodeGenerator.boxedType(rClassName)

      nullSafeCodeGen(ctx, ev, input => s"""
      $rClassName res = ${
        converter.fold(input)( p =>
          p._2(ctx, input)
        )
      };
      ${ev.value} = new scala.Tuple2<$contextClassName, String>($contextPath, ($boxed) res);
    """)
    }*/
  }

  def struct(pairs: Map[String, (Int, Accessor[_])]): Accessor[util.Map[String, Object]] =
    (path: Any, i: Int) => new BaseKogitoMap(path, pairs)

  class BaseKogitoMap(path: Any, pairs: Map[String, (Int, Accessor[_])]) extends util.Map[String, Object] {

      override def get(key: Any): AnyRef = {
        val (i, a) = pairs(key.toString)
        val t = a.forPath(path, i)
        if (t == null) null else t.asInstanceOf[AnyRef]
      }

      // called by Jackson serializing
      override def entrySet(): util.Set[util.Map.Entry[String, Object]] = pairs.map{case (key, (i, accessor)) => new util.Map.Entry[String, Object]{
        override def getKey: String = key

        override def getValue: Object = {
          val t = accessor.forPath(path, i)
          if (t == null) null else t.asInstanceOf[AnyRef]
        }

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
