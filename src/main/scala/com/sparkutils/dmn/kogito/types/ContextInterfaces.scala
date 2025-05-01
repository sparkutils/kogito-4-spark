package com.sparkutils.dmn.kogito.types

import com.sparkutils.dmn.{DMNContextPath, DMNContextProvider, DMNException}
import com.sparkutils.dmn.impl.{SimpleContextProvider, StringContextProvider}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.Block.BlockHelper
import org.apache.spark.sql.catalyst.expressions.codegen.{Block, CodeGenerator, CodegenContext, CodegenFallback, ExprCode, FalseLiteral, JavaCode, VariableValue}
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
                r.getStruct(i, structType.fields.length)
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

  trait AccessorCodeGen extends Serializable {
    /**
     * Generate code for this path, object casting and input null checking will be added by the caller
     * @param ctx
     * @param pathName variable to access (typically a SpecializedGetters)
     * @param iName index in SpecializedGetters
     * @return generated code which returns either the underlying type or util.map/util.list
     */
    def forPath(ctx: CodegenContext, pathName: String, iName: String): ExprCode
  }

  def exprCode(clazz: Class[_], ctx: CodegenContext): ExprCode = {
    val isNull = ctx.freshName("isNull")
    val value = ctx.freshName("value")

    val expr = ExprCode(
      JavaCode.isNullVariable(isNull),
      JavaCode.variable(value, clazz)
    )
    expr
  }

  def exprCode(boxed: Class[_], ctx: CodegenContext, code: Block): ExprCode = {
    val isNull = ctx.freshName("isNull")
    val value = ctx.freshName("value")

    val expr = ExprCode(
      JavaCode.isNullVariable(isNull),
      JavaCode.variable(value, classOf[Object])
    )
    expr.copy(code =
      code"""
        Object ${expr.value} = (${boxed.getName}) $code
        boolean ${expr.isNull} = (${expr.value} == null);
          """)
  }

  def forTypeCodeGen(dataType: DataType, inCollection: Boolean, topLevel: Boolean = false): AccessorCodeGen = dataType match {
    case structType: StructType =>  (ctx: CodegenContext, pathName: String, iName: String) => {
      // when inCollection is true we cannot have a static map or results
      val struct = ctx.freshName("struct")

      val (theHash, vars) = generateHashMap(ctx, structType, inCollection)

      val expr = exprCode(classOf[util.Map[_,_]], ctx)
      val setup = structType.fields.zipWithIndex.map { case (f, i) => (i, forTypeCodeGen(f.dataType, inCollection).forPath(ctx, struct, i.toString)) }.foldLeft(code""){
        case (c, (i, e)) =>
          code"""
            $c
            ${e.code}
            $vars[$i] = ${e.value};
            """
      }
      expr.copy(code"""
        ${CodeGenerator.javaType(dataType)} $struct = ${if (topLevel) pathName else s"$pathName.getStruct($iName, ${structType.fields.length})"};
          ${theHash.code}

          $setup
          java.util.Map ${expr.value} = ${theHash.value};
          boolean ${expr.isNull} = false;
          """)
    }
    case StringType => (ctx: CodegenContext, pathName: String, iName: String) => exprCode(classOf[String], ctx, code"$pathName.getUTF8String($iName).toString();")
    case IntegerType => (ctx: CodegenContext, pathName: String, iName: String) => exprCode(classOf[Integer], ctx, code"$pathName.getInt($iName);")
    case LongType => (ctx: CodegenContext, pathName: String, iName: String) => exprCode(classOf[Long], ctx, code"$pathName.getLong($iName);")
    case BooleanType => (ctx: CodegenContext, pathName: String, iName: String) => exprCode(classOf[Boolean], ctx, code"$pathName.getBoolean($iName);")
    case DoubleType => (ctx: CodegenContext, pathName: String, iName: String) => exprCode(classOf[Double], ctx, code"$pathName.getDouble($iName);")
    case FloatType => (ctx: CodegenContext, pathName: String, iName: String) => exprCode(classOf[Float], ctx, code"$pathName.getFloat($iName);")
    case BinaryType => (ctx: CodegenContext, pathName: String, iName: String) => exprCode(classOf[Array[Byte]], ctx, code"$pathName.getBinary($iName);")
    case ByteType => (ctx: CodegenContext, pathName: String, iName: String) => exprCode(classOf[Byte], ctx, code"$pathName.getByte($iName);")
    case ShortType => (ctx: CodegenContext, pathName: String, iName: String) => exprCode(classOf[Short], ctx, code"$pathName.getShort($iName);")
    case DateType => (ctx: CodegenContext, pathName: String, iName: String) => exprCode(classOf[LocalDate], ctx, code"org.apache.spark.sql.catalyst.util.DateTimeUtils.daysToLocalDate($pathName.getInt($iName));")
    case TimestampType => (ctx: CodegenContext, pathName: String, iName: String) => exprCode(classOf[LocalDateTime], ctx, code"org.apache.spark.sql.catalyst.util.DateTimeUtils.microsToLocalDateTime($pathName.getLong($iName));")
    case _: DecimalType => (ctx: CodegenContext, pathName: String, iName: String) =>
      // max needed as Spark's past 3.4 move everything to max anyway, 1.0 comes back as 1.0 instead of 2016...
      exprCode(classOf[java.math.BigDecimal], ctx, code"$pathName.getDecimal($iName, org.apache.spark.sql.types.DecimalType.MAX_PRECISION(), org.apache.spark.sql.types.DecimalType.DEFAULT_SCALE()).toJavaBigDecimal();")
    case ArrayType(typ, _) =>
      (ctx: CodegenContext, pathName: String, iName: String) => {
        val arr = ctx.freshVariable("arr", dataType)
        val i = ctx.freshVariable("i", classOf[Int])

        val typCode = forTypeCodeGen(typ, inCollection = true).forPath(ctx, arr, i)
        val arrRes = ctx.freshVariable("arrRes", dataType)

        val expr = exprCode(classOf[java.util.List[_]], ctx)
        expr.copy(
          code =
            code"""
              ${CodeGenerator.javaType(dataType)} $arr = ${if (topLevel) pathName else s"$pathName.getArray($iName)"};
              Object[] $arrRes = new Object[$arr.numElements()];
              for (int $i = 0; $i < $arr.numElements(); $i++) {
                ${typCode.code}
                $arrRes[$i] = ${typCode.value};
              }
              java.util.List ${expr.value} = java.util.Arrays.asList($arrRes);
              boolean ${expr.isNull} = false;
              """
        )
      }
    case MapType(k, v, _) =>
      (ctx: CodegenContext, pathName: String, iName: String) => {
        val map = ctx.freshVariable("map", dataType)
        val keys = ctx.freshVariable("keys", ArrayType(k))
        val values = ctx.freshVariable("values", ArrayType(v))
        val i = ctx.freshVariable("i", classOf[Int])

        val kCode = forTypeCodeGen(k, inCollection = true).forPath(ctx, keys, i)
        val vCode = forTypeCodeGen(v, inCollection = true).forPath(ctx, values, i)

        val expr = exprCode(classOf[java.util.Map[_,_]], ctx)
        expr.copy(
          code =
            code"""
              ${CodeGenerator.javaType(dataType)} $map = ${if (topLevel) pathName else s"$pathName.getMap($iName)"};
              ${keys.javaType.getName} $keys = $map.keyArray();
              ${values.javaType.getName} $values = $map.valueArray();
              java.util.Map ${expr.value} = new java.util.TreeMap();
              for (int $i = 0; $i < $keys.numElements(); $i++) {
                ${kCode.code}
                ${vCode.code}

                if (!${kCode.isNull}) {
                  ${expr.value}.put(${kCode.value}, ${vCode.value});
                }
              }
              boolean ${expr.isNull} = false;
              """)
      }
    case _ => throw new DMNException(s"Could not load Kogito Context AccessorCodeGen for dataType $dataType")
  }

  // when inCollection is true we cannot have a static map or results
  private def generateHashMap(ctx: CodegenContext, structType: StructType, inCollection: Boolean): (ExprCode, String) = {
    val varsFinal = ctx.freshVariable("varsProxy", classOf[Array[Object]])

    val vars =
      if (inCollection)
        ctx.freshName("vars")
      else
        ctx.addMutableState("Object[]", "structVars", str => s"$str = new Object[${structType.fields.length}];", useFreshName = true)

    val theMap = (str: String) =>
      s"""
          final Object[] $varsFinal = $vars;
          $str = new com.sparkutils.dmn.kogito.types.SimpleMap() {
              public Object get(Object key) {
                int index = -1;
                switch (key.toString()) {
                    ${
        structType.fields.zipWithIndex.foldLeft("") {
          case (c, (f, i)) =>
            s"""
                         $c
                         case "${f.name}":
                            index = $i;
                            break;
                          """
        }
      }
                    default:
                        index = -1;
                }
                if (index == -1) {
                  return null;
                }
                return $varsFinal[index];
              }

              private final String[] names = { ${structType.fields.map(f => s"\"${f.name}\"").mkString(",")} };
              private final java.util.Map.Entry<String, Object>[] backingSet = {
                ${structType.fields.indices.map(i => s"new com.sparkutils.dmn.kogito.types.ArrayEntry<String, Object>(names, $varsFinal, $i)").mkString(",")}
              };
              private final java.util.Set<java.util.Map.Entry<String, Object>> set = new com.sparkutils.dmn.kogito.types.ArraySet(backingSet);

              // called by Jackson serializing
              public java.util.Set<java.util.Map.Entry<String, Object>> entrySet() {
                  return set;
              }

              public int size() {
                  return ${structType.fields.length};
              }
          };
          """

    val theHash =
      if (inCollection) {
        val ev = exprCode( classOf[java.util.Map[String, Object]], ctx)
        ev.copy(code =
          code"""
             java.util.Map ${ev.value} = null;
             Object[] $vars = new Object[${structType.fields.length}];
             ${theMap(ev.value.code)}""")
      } else {
        val name = ctx.addMutableState("java.util.Map<String, Object>", "struct", theMap, useFreshName = true)
        val ev = exprCode( classOf[java.util.Map[String, Object]], ctx)
        ev.copy(code =
          code"java.util.Map ${ev.value} = $name;")
      }


    (theHash, vars)
  }

  private def arrayOfType(entryAccessor: Accessor[_], ar: ArrayData) =
    for {i <- 0 until ar.numElements()}
      yield entryAccessor.forPath(ar, i)

  // NOTE -1 must be provided for top level as we don't know the index the data is taken from

  def mapProvider(mapType: MapType, path: DMNContextPath, expr: Expression): DMNContextProvider[util.Map[String, Object]] = {
    val ma = forType(mapType)
    ComplexContextProvider[util.Map[String, Object]](mapType, ma, path, expr)
  }

  def arrayProvider(arrayType: ArrayType, path: DMNContextPath, expr: Expression): DMNContextProvider[util.List[Object]] = {
    val aa = forType(arrayType)
    ComplexContextProvider[util.List[Object]](arrayType, aa, path, expr)
  }

  /**
   * Provides a map interface over underlying struct data
   * @param structType
   * @return
   */
  def structProvider(structType: StructType, path: DMNContextPath, expr: Expression): DMNContextProvider[util.Map[String, Object]] = {
    val sa = forType(structType)
    ComplexContextProvider[util.Map[String, Object]](structType, sa, path, expr)
  }

  case class ComplexContextProvider[T: ClassTag](actualDataType: DataType, accessor: Accessor[_], contextPath: DMNContextPath, child: Expression) extends UnaryExpression with DMNContextProvider[T] {

    def withNewChildInternal(newChild: Expression): Expression = copy(child = newChild)

    override def nullSafeEval(input: Any): Any = {
      (contextPath, accessor.forPath(input, -1).asInstanceOf[T])
    }

    /**
     * Result class type
     */
    override val resultType: Class[T] = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
      val (contextClassName, contextPath) = genContext(ctx)
      val rClassName = resultType.getName
      val boxed = CodeGenerator.boxedType(rClassName)

      nullSafeCodeGen(ctx, ev, f = input => {
        val typeCode = forTypeCodeGen(actualDataType, inCollection = false, topLevel = true).forPath(ctx, input, "")
        s"""
        // kogito-4-spark context provider - start
        ${typeCode.code}
        ${ev.value} = new scala.Tuple2<$contextClassName, String>($contextPath, ($boxed) ${typeCode.value});
        // kogito-4-spark context provider - end
      """
      })
    }
  }

  def struct(pairs: Map[String, (Int, Accessor[_])]): Accessor[util.Map[String, Object]] =
    (path: Any, i: Int) => new BaseKogitoMap(path, pairs)

  class BaseKogitoMap(path: Any, pairs: Map[String, (Int, Accessor[_])]) extends SimpleMap {
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

    override def size(): Int = pairs.size
  }
}


class SimpleMap() extends util.Map[String, Object] {

  override def get(key: Any): AnyRef = ???

  // called by Jackson serializing
  override def entrySet(): util.Set[util.Map.Entry[String, Object]] = ???

  // Never called by kogito

  override def keySet(): util.Set[String] = ??? //pairs.keySet.asJava

  override def size(): Int = ??? //pairs.size

  override def isEmpty: Boolean = false

  override def containsKey(key: Any): Boolean = ??? // pairs.contains(key.toString)

  // Never being implemented

  override def containsValue(value: Any): Boolean = ???

  override def values(): util.Collection[Object] = ???

  override def put(key: String, value: Object): AnyRef = ???

  override def remove(key: Any): AnyRef = ???

  override def putAll(m: util.Map[_ <: String, _ <: Object]): Unit = ???

  override def clear(): Unit = ???
}

class ArrayEntry[K, V](keys: Array[K], values: Array[V], i: Int) extends java.util.Map.Entry[K, V] {

  override def getKey: K = keys(i)

  override def getValue: V = values(i)

  override def setValue(value: V): V = ???
}

class ArraySet[E](backed: Array[E]) extends java.util.Set[E] {

  override def size(): Int = backed.length

  override def isEmpty: Boolean = backed.isEmpty

  override def contains(o: Any): Boolean =
    o match {
      case o: E =>  backed.contains(o)
      case _ => false
    }

  override def iterator(): util.Iterator[E] = backed.iterator.asJava

  override def toArray: Array[AnyRef] = backed.asInstanceOf[Array[AnyRef]]

  // ERROR in intellij is not present in actual compiler
  override def toArray[T](a: Array[T with Object]): Array[T with Object] = {
    System.arraycopy(backed, 0, a, 0, backed.length)
    a
  }

  override def add(e: E): Boolean = ???

  override def remove(o: Any): Boolean = ???

  override def containsAll(c: util.Collection[_]): Boolean = ???

  override def addAll(c: util.Collection[_ <: E]): Boolean = ???

  override def retainAll(c: util.Collection[_]): Boolean = ???

  override def removeAll(c: util.Collection[_]): Boolean = ???

  override def clear(): Unit = ???
}
