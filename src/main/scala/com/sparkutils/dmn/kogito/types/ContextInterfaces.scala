package com.sparkutils.dmn.kogito.types

import com.sparkutils.dmn.kogito.types.Utils.{exprCode, exprCodeInterim, exprCodeIsNullAt, nullOr}
import com.sparkutils.dmn.{DMNContextPath, DMNContextProvider, DMNException, UnaryDMNContextProvider}
import org.apache.spark.sql.catalyst.expressions.codegen.Block.BlockHelper
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeGenerator, CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.expressions.{Expression, SpecializedGetters}
import org.apache.spark.sql.catalyst.util.{ArrayData, DateTimeUtils, MapData}
import org.apache.spark.sql.types.{ArrayType, BinaryType, BooleanType, ByteType, DataType, DateType, Decimal, DecimalType, DoubleType, FloatType, IntegerType, LongType, MapType, ShortType, StringType, StructType, TimestampNTZType, TimestampType}

import java.time.{LocalDate, LocalDateTime}
import scala.collection.JavaConverters._
import java.util
import scala.reflect.{ClassTag, classTag}
import scala.util.Try

object ContextInterfaces {

  trait Accessor[T] extends Serializable {
    def forPath(path: Any, i: Int): T
  }

  def nullAtOr(f: (SpecializedGetters, Int) => Any): Accessor[Any] =
    (path: Any, i: Int) =>
      if (path.asInstanceOf[SpecializedGetters].isNullAt(i))
        null
      else
        f( path.asInstanceOf[SpecializedGetters], i)

  // -1 as top level field, only for struct/map/array
  def forType(dataType: DataType, dmnConfiguration: Map[String, String]): Accessor[_] = dataType match {
    case structType: StructType =>
      val s = struct(structType.fields.zipWithIndex.map { case (f, i) => (f.name, (i, forType(f.dataType, dmnConfiguration))) }.toMap)

      (path: Any, i: Int) => {
        val r = path.asInstanceOf[SpecializedGetters]
        val input =
          if (i == -1)
            r
          else
            r.getStruct(i, structType.fields.length)
        s.forPath(input, i)
      }
    case StringType => nullAtOr {
      (path: SpecializedGetters, i: Int) =>
        path.getUTF8String(i).toString
    }
    case IntegerType => nullAtOr {
      (path: SpecializedGetters, i: Int) =>
        path.getInt(i)
    }
    case LongType => nullAtOr {
      (path: SpecializedGetters, i: Int) =>
        path.getLong(i)
    }
    case BooleanType =>nullAtOr {
      (path: SpecializedGetters, i: Int) =>
        path.getBoolean(i)
    }
    case DoubleType => nullAtOr {
      (path: SpecializedGetters, i: Int) =>
        path.getDouble(i)
    }
    case FloatType =>nullAtOr {
      (path: SpecializedGetters, i: Int) =>
        path.getFloat(i)
    }
    case BinaryType => nullAtOr {
      (path: SpecializedGetters, i: Int) =>
        path.getBinary(i)
    }
    case ByteType => nullAtOr {
      (path: SpecializedGetters, i: Int) =>
        path.getByte(i)
    }
    case ShortType => nullAtOr {
      (path: SpecializedGetters, i: Int) =>
        path.getShort(i)
    }
    case DateType => nullAtOr{
      (path: SpecializedGetters, i: Int) =>
        DateTimeUtils.daysToLocalDate( path.getInt(i) )
    }
    case TimestampType | TimestampNTZType => nullAtOr{
      (path: SpecializedGetters, i: Int) =>
        DateTimeUtils.microsToLocalDateTime( path.getLong(i) )
    }
    case _: DecimalType => // max needed as Spark's past 3.4 move everything to max anyway, 1.0 comes back as 1.0 instead of 2016...
      nullAtOr{
        (path: SpecializedGetters, i: Int) =>
        val t = path.getDecimal(i, DecimalType.MAX_PRECISION, DecimalType.DEFAULT_SCALE)
        nullOr((_: Decimal).toJavaBigDecimal)(t)
      }
    case ArrayType(typ, _) =>
      val entryAccessor = forType(typ, dmnConfiguration)
      nullAtOr{ (path: Any, i: Int) =>
        val ar = {
          if (i == -1)
            path.asInstanceOf[ArrayData]
          else
            path.asInstanceOf[SpecializedGetters].getArray(i)
        }
        nullOr((ar: ArrayData) => arrayOfType(entryAccessor, ar).asJava)(ar)
      }
    case MapType(k, v, _) => {
      val kAccessor = forType(k, dmnConfiguration)
      val vAccessor = forType(v, dmnConfiguration)
      nullAtOr{ (path: Any, i: Int) =>
        val m =
          if (i == -1)
            path.asInstanceOf[MapData]
          else
            path.asInstanceOf[SpecializedGetters].getMap(i)

        nullOr{(m: MapData) =>
          val ka = arrayOfType(kAccessor, m.keyArray())
          val va = arrayOfType(vAccessor, m.valueArray())
          (ka zip va).toMap.asJava
        }(m)
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

  def forTypeCodeGen(dataType: DataType, inCollection: Boolean, dmnConfiguration: Map[String,String], topLevel: Boolean = false): AccessorCodeGen = dataType match {
    case structType: StructType =>  (ctx: CodegenContext, pathName: String, iName: String) => {
      // when inCollection is true we cannot have a static map or results
      val struct = ctx.freshName("struct")

      val (theHash, vars) = generateHashMap(ctx, structType, inCollection)

      val expr = exprCode(classOf[util.Map[_,_]], ctx)
      val setup = structType.fields.zipWithIndex.map { case (f, i) => (i, forTypeCodeGen(f.dataType, inCollection, dmnConfiguration).forPath(ctx, struct, i.toString)) }.foldLeft(code""){
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
    case StringType => (ctx: CodegenContext, pathName: String, iName: String) =>
      exprCodeInterim(classOf[String], ctx,
        code"$pathName.getUTF8String($iName)", i => code"((UTF8String)$i).toString()")
    case IntegerType => (ctx: CodegenContext, pathName: String, iName: String) =>
      exprCodeIsNullAt(classOf[Integer], ctx, code"$pathName.isNullAt($iName)",
        code"$pathName.getInt($iName);")
    case LongType => (ctx: CodegenContext, pathName: String, iName: String) =>
      exprCodeIsNullAt(classOf[Long], ctx, code"$pathName.isNullAt($iName)",
        code"$pathName.getLong($iName);")
    case BooleanType => (ctx: CodegenContext, pathName: String, iName: String) =>
      exprCodeIsNullAt(classOf[Boolean], ctx, code"$pathName.isNullAt($iName)",
        code"$pathName.getBoolean($iName);")
    case DoubleType => (ctx: CodegenContext, pathName: String, iName: String) =>
      exprCodeIsNullAt(classOf[Double], ctx, code"$pathName.isNullAt($iName)",
        code"$pathName.getDouble($iName);")
    case FloatType => (ctx: CodegenContext, pathName: String, iName: String) =>
      exprCodeIsNullAt(classOf[Float], ctx, code"$pathName.isNullAt($iName)",
        code"$pathName.getFloat($iName);")
    case BinaryType => (ctx: CodegenContext, pathName: String, iName: String) =>
      exprCodeIsNullAt(classOf[Array[Byte]], ctx, code"$pathName.isNullAt($iName)",
        code"$pathName.getBinary($iName);")
    case ByteType => (ctx: CodegenContext, pathName: String, iName: String) =>
      exprCodeIsNullAt(classOf[Byte], ctx, code"$pathName.isNullAt($iName)",
        code"$pathName.getByte($iName);")
    case ShortType => (ctx: CodegenContext, pathName: String, iName: String) =>
      exprCodeIsNullAt(classOf[Short], ctx, code"$pathName.isNullAt($iName)",
        code"$pathName.getShort($iName);")
    case DateType => (ctx: CodegenContext, pathName: String, iName: String) =>
      exprCodeIsNullAt(classOf[LocalDate], ctx, code"$pathName.isNullAt($iName)",
        code"org.apache.spark.sql.catalyst.util.DateTimeUtils.daysToLocalDate($pathName.getInt($iName))")
    case TimestampType | TimestampNTZType => (ctx: CodegenContext, pathName: String, iName: String) =>
      exprCodeIsNullAt(classOf[LocalDateTime], ctx, code"$pathName.isNullAt($iName)",
        code"org.apache.spark.sql.catalyst.util.DateTimeUtils.microsToLocalDateTime($pathName.getLong($iName));")
    case _: DecimalType => (ctx: CodegenContext, pathName: String, iName: String) =>
      // max needed as Spark's past 3.4 move everything to max anyway, 1.0 comes back as 1.0 instead of 2016...
      exprCodeInterim(classOf[java.math.BigDecimal], ctx,
        code"$pathName.getDecimal($iName, org.apache.spark.sql.types.DecimalType.MAX_PRECISION(), org.apache.spark.sql.types.DecimalType.DEFAULT_SCALE())",
        i => code"((org.apache.spark.sql.types.Decimal)$i).toJavaBigDecimal()")
    case ArrayType(typ, _) =>
      (ctx: CodegenContext, pathName: String, iName: String) => {
        val arr = ctx.freshVariable("arr", dataType)
        val i = ctx.freshVariable("i", classOf[Int])

        val typCode = forTypeCodeGen(typ, inCollection = true, dmnConfiguration).forPath(ctx, arr, i)
        val arrRes = ctx.freshVariable("arrRes", dataType)

        val expr = exprCode(classOf[java.util.List[_]], ctx)
        expr.copy(
          code =
            code"""
              ${CodeGenerator.javaType(dataType)} $arr = ${if (topLevel) pathName else s"$pathName.getArray($iName)"};
              boolean ${expr.isNull} = ($arr == null);
              java.util.List ${expr.value} = null;
              if (!${expr.isNull}) {
                Object[] $arrRes = new Object[$arr.numElements()];
                for (int $i = 0; $i < $arr.numElements(); $i++) {
                  ${typCode.code}
                  $arrRes[$i] = ${typCode.value};
                }
                ${expr.value} = java.util.Arrays.asList($arrRes);
              }
              """
        )
      }
    case MapType(k, v, _) =>
      (ctx: CodegenContext, pathName: String, iName: String) => {
        val map = ctx.freshVariable("map", dataType)
        val keys = ctx.freshVariable("keys", ArrayType(k))
        val values = ctx.freshVariable("values", ArrayType(v))
        val i = ctx.freshVariable("i", classOf[Int])

        val kCode = forTypeCodeGen(k, inCollection = true, dmnConfiguration).forPath(ctx, keys, i)
        val vCode = forTypeCodeGen(v, inCollection = true, dmnConfiguration).forPath(ctx, values, i)

        val mapImpl =
          if (Try(dmnConfiguration.getOrElse("useTreeMap", "false").toBoolean).fold(_ => false, identity))
            "TreeMap"
          else
            "HashMap"

        val expr = exprCode(classOf[java.util.Map[_,_]], ctx)
        expr.copy(
          code =
            code"""
              ${CodeGenerator.javaType(dataType)} $map = ${if (topLevel) pathName else s"$pathName.getMap($iName)"};
              boolean ${expr.isNull} = ($map == null);
              java.util.Map ${expr.value} = null;
              if (!${expr.isNull}) {
                ${keys.javaType.getName} $keys = $map.keyArray();
                ${values.javaType.getName} $values = $map.valueArray();
                ${expr.value} = new java.util.$mapImpl();
                for (int $i = 0; $i < $keys.numElements(); $i++) {
                  ${kCode.code}
                  ${vCode.code}

                  if (!${kCode.isNull}) {
                    ${expr.value}.put(${kCode.value}, ${vCode.value});
                  }
                }
              }
              """)
      }
    // cannot be called in current spark, Eval will be type checked first
    // $COVERAGE-OFF$
    case _ => throw new DMNException(s"Could not load Kogito Context Accessor for dataType $dataType")
    // $COVERAGE-ON$
  }

  private def genFieldLookup(structType: StructType, funName: String, resultType: String, success: String, failure: String): String = {
    s"""
    public $resultType $funName(Object key) {
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
        return $failure;
      }
      return $success;
    }"""
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
              ${genFieldLookup(structType, funName = "containsKey",
                resultType = "boolean", success = "true", failure = "false")}
              ${genFieldLookup(structType, funName = "get",
                resultType = "Object", success = s"$varsFinal[index]", failure = "null")}

              private final String[] names = { ${structType.fields.map(f => s""""${f.name}"""").mkString(",")} };
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

  def mapProvider(mapType: MapType, path: DMNContextPath, expr: Expression, stillSetWhenNull: Boolean, dmnConfiguration: Map[String,String]): DMNContextProvider[util.Map[String, Object]] = {
    val ma = forType(mapType, dmnConfiguration)
    ComplexContextProvider[util.Map[String, Object]](mapType, dmnConfiguration, ma, path, stillSetWhenNull, expr)
  }

  def arrayProvider(arrayType: ArrayType, path: DMNContextPath, expr: Expression, stillSetWhenNull: Boolean, dmnConfiguration: Map[String,String]): DMNContextProvider[util.List[Object]] = {
    val aa = forType(arrayType, dmnConfiguration)
    ComplexContextProvider[util.List[Object]](arrayType, dmnConfiguration, aa, path, stillSetWhenNull, expr)
  }

  /**
   * Provides a map interface over underlying struct data
   * @param structType
   * @return
   */
  def structProvider(structType: StructType, path: DMNContextPath, expr: Expression, stillSetWhenNull: Boolean, dmnConfiguration: Map[String,String]): DMNContextProvider[util.Map[String, Object]] = {
    val sa = forType(structType, dmnConfiguration)
    ComplexContextProvider[util.Map[String, Object]](structType, dmnConfiguration, sa, path, stillSetWhenNull, expr)
  }

  case class ComplexContextProvider[T: ClassTag](actualDataType: DataType, dmnConfiguration: Map[String,String], accessor: Accessor[_], contextPath: DMNContextPath, stillSetWhenNull: Boolean, child: Expression) extends UnaryDMNContextProvider[T] {

    def withNewChildInternal(newChild: Expression): Expression = copy(child = newChild)

    override def nullSafeContextEval(input: Any): Any =
      accessor.forPath(input, -1).asInstanceOf[T]

    /**
     * Result class type
     */
    override val resultType: Class[T] = classTag[T].runtimeClass.asInstanceOf[Class[T]]
    override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
      val (_, contextPath) = genContext(ctx)
      val rClassName = resultType.getName
      val boxed = CodeGenerator.boxedType(rClassName)

      nullSafeContextCodeGen(child, ctx, ev, contextPath, f = input => {
        val typeCode = forTypeCodeGen(actualDataType, inCollection = false, dmnConfiguration, topLevel = true).forPath(ctx, input, "")
        s"""
        // kogito-4-spark context provider - start
        ${typeCode.code}
        ${ev.value}[0] = $contextPath;
        ${ev.value}[1] = ($boxed) ${typeCode.value};
        // kogito-4-spark context provider - end
      """
      })
    }
  }

  def struct(pairs: Map[String, (Int, Accessor[_])]): Accessor[util.Map[String, Object]] =
    (path: Any, i: Int) => new BaseKogitoMap(path, pairs)
}

