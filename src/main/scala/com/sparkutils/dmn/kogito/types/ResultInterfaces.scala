package com.sparkutils.dmn.kogito.types

import com.sparkutils.dmn.DMNException
import com.sparkutils.dmn.kogito.types.Utils.{exprCode, exprCodeInterim}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.Block.BlockHelper
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeGenerator, CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.util.{ArrayBasedMapData, DateTimeUtils, GenericArrayData}
import org.apache.spark.sql.types.{ArrayType, BinaryType, BooleanType, ByteType, DataType, DateType, Decimal, DecimalType, DoubleType, FloatType, IntegerType, LongType, MapType, ShortType, StringType, StructType, TimestampNTZType, TimestampType}
import org.apache.spark.unsafe.types.UTF8String

import java.time.{LocalDate, LocalDateTime}
import java.util
import scala.collection.JavaConverters._
import scala.reflect.{ClassTag, classTag}

/**
 * Reverse the logic from ContextInterfaces
 */
object ResultInterfaces {

  val evalStatusEnding = "_dmnEvalStatus"

  // The errors in intellij are not real
  val NOT_FOUND: Byte = -6.toByte // DDL has a decision which isn't in the DMN, possibly a typo or the dmn decision was removed / not there yet
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

      val s = structType.fields.map { f =>
        (f.name,
          if (f.name.endsWith(evalStatusEnding))
            ((path: Any) => EVALUATING): Getter
          else
            forType(f.dataType)
        )
      }

      (path: Any) => {
        if (path == null) null else {
          val m = path.asInstanceOf[util.Map[String, Object]]
          InternalRow(s.map { case (name, g) => g.get(m.get(name)) }: _*)
        }
      }
    case StringType => (path: Any) => if (path == null) null else UTF8String.fromString( path.toString )
    case IntegerType => (path: Any) => if (path == null) null else path.asInstanceOf[Integer]
    case LongType => (path: Any) => if (path == null) null else path.asInstanceOf[Long]
    case BooleanType => (path: Any) => if (path == null) null else path.asInstanceOf[Boolean]
    case DoubleType => (path: Any) => if (path == null) null else path.asInstanceOf[Double]
    case FloatType => (path: Any) => if (path == null) null else path.asInstanceOf[Float]
    case BinaryType => (path: Any) => if (path == null) null else path.asInstanceOf[Array[Byte]]
    case ByteType => (path: Any) => if (path == null) null else path.asInstanceOf[Byte]
    case ShortType => (path: Any) => if (path == null) null else path.asInstanceOf[Short]
    case DateType => (path: Any) => if (path == null) null else DateTimeUtils.localDateToDays( path.asInstanceOf[LocalDate] )
    case TimestampType | TimestampNTZType => (path: Any) => if (path == null) null else DateTimeUtils.localDateTimeToMicros( path.asInstanceOf[LocalDateTime] )
    case _: DecimalType => (path: Any) =>
      if (path == null) null else Decimal.apply(path.asInstanceOf[java.math.BigDecimal])
    case ArrayType(typ, _) =>
      val g = forType(typ)
      (path: Any) => {
        if (path == null) null else {
          val a = path.asInstanceOf[util.List[_]].toArray.map(g.get(_))
          new GenericArrayData(a)
        }
      }
    case MapType(k, v, _) =>
      val kG = forType(k)
      val vG = forType(v)
      (path: Any) => {
        if (path == null) null else {
          val m = path.asInstanceOf[util.Map[Object, Object]].asScala.toMap.map(e => kG.get(e._1) -> vG.get(e._2))
          new ArrayBasedMapData(new GenericArrayData(m.keys.toArray), new GenericArrayData(m.values.toArray))
        }
      }
    case _ => throw new DMNException(s"Could not load Kogito Result Provider for dataType $dataType")
  }

  trait GetterCodeGen extends Serializable {
    /**
     * Generate code for this path, object casting and input null checking will be added by the caller
     * @param ctx
     * @param pathName variable to access (typically a SpecializedGetters)
     * @return generated code which returns either the underlying type or util.map/util.list
     */
    def forPath(ctx: CodegenContext, pathName: String): ExprCode
  }
  def nullOr[T: ClassTag](f: String => String = path => s"$path"): GetterCodeGen =
    (ctx: CodegenContext, pathName: String) => {
      exprCodeInterim(classTag[T].runtimeClass, ctx, code"$pathName",
        i => code"""${f(i)}""", cast = false) // all are object anyway
    }

  def forTypeCodeGen(dataType: DataType): GetterCodeGen = dataType match {
    case structType: StructType =>
      val mappedFields = structType.fields.map { f =>
        (f.name,
          if (f.name.endsWith(evalStatusEnding))
            nullOr[Byte](_ => s"com.sparkutils.dmn.kogito.types.ResultInterfaces.EVALUATING()")
          else
            forTypeCodeGen(f.dataType)
        )
      }
      (ctx: CodegenContext, pathName: String) => {
        val expr = exprCode(classOf[GenericInternalRow],ctx)

        val mapName = ctx.freshVariable("map", classOf[util.Map[String,Object]])

        val s = mappedFields.map{
          case (n,f) =>
            f.forPath(ctx, s"""$mapName.get("$n")""")
        }
        val init = s.foldLeft(code""){
          case (c, e) =>
            code"""$c
                ${e.code}
                """
        }
        val fields = s.map(_.value).mkString("\n,")

        val resArr = ctx.freshName("resArr")

        expr.copy(code =
          code"""
            org.apache.spark.sql.catalyst.expressions.GenericInternalRow ${expr.value} = null;
            java.util.Map $mapName = (java.util.Map<String, Object>)$pathName;
            boolean ${expr.isNull} = ($mapName == null);
            if (!${expr.isNull}) {
              $init
              Object[] $resArr = new Object[]{$fields};

              ${expr.value} = new org.apache.spark.sql.catalyst.expressions.GenericInternalRow(
                $resArr
              );
            }
              """
        )
      }
    case StringType => nullOr[String]( path => s"UTF8String.fromString( $path.toString() )")
    case IntegerType => nullOr[Integer]()
    case LongType => nullOr[Long]()
    case BooleanType => nullOr[Boolean]()
    case DoubleType => nullOr[Double]()
    case FloatType => nullOr[Float]()
    case BinaryType => nullOr[Array[Byte]]()
    case ByteType => nullOr[Byte]()
    case ShortType => nullOr[Short]()
    case DateType =>
      nullOr[Int]( path => s"org.apache.spark.sql.catalyst.util.DateTimeUtils.localDateToDays( (java.time.LocalDate) $path )")
    case TimestampType  | TimestampNTZType =>
      nullOr[Long]( path => s"org.apache.spark.sql.catalyst.util.DateTimeUtils.localDateTimeToMicros( (java.time.LocalDateTime) $path )")
    case _: DecimalType =>
      nullOr[Decimal]( path => s"org.apache.spark.sql.types.Decimal.apply( (java.math.BigDecimal) $path )")
    case ArrayType(typ, _) =>
      val arrCode = forTypeCodeGen(typ)
      (ctx: CodegenContext, pathName: String) => {
        val expr = exprCode(classOf[GenericArrayData],ctx)

        val iar = ctx.freshVariable("ar", classOf[util.List[Object]])

        val i = ctx.freshVariable("i", classOf[Int])

        val arrRes = ctx.freshVariable("arr", classOf[Array[Object]])

        val typCode = arrCode.forPath(ctx, s"$arrRes[$i]")

        expr.copy(
          code =
            code"""
            org.apache.spark.sql.catalyst.util.GenericArrayData ${expr.value} = null;
            java.util.List $iar = (java.util.List<Object>)$pathName;
            boolean ${expr.isNull} = ($iar == null);
            if (!${expr.isNull}) {
              Object[] $arrRes = $iar.toArray();

              for (int $i = 0; $i < $arrRes.length; $i++) {
                ${typCode.code}
                $arrRes[$i] = ${typCode.value};
              }
              ${expr.value} = new org.apache.spark.sql.catalyst.util.GenericArrayData($arrRes);
            }
            """
        )
      }
    case MapType(k, v, _) =>
      val kCode = forTypeCodeGen(k)
      val vCode = forTypeCodeGen(v)
      (ctx: CodegenContext, pathName: String) => {
        val expr = exprCode(classOf[ArrayBasedMapData], ctx)

        val map = ctx.freshVariable("map", classOf[util.Map[String, Object]])

        val i = ctx.freshVariable("i", classOf[Int])

        val entry = ctx.freshVariable("entry", classOf[util.Map.Entry[String, Object]])

        val typCodeK = kCode.forPath(ctx, s"$entry.getKey()")
        val typCodeV = vCode.forPath(ctx, s"$entry.getValue()")

        val arrKeyRes = ctx.freshVariable("arrKey", classOf[Array[Object]])
        val arrValueRes = ctx.freshVariable("arrValue", classOf[Array[Object]])

        val itr = ctx.freshName("itr")
        // NB most maps iterators aren't bounds checking, AbstractCollection.toArray uses iterator anyway
        expr.copy(
          code =
            code"""
            org.apache.spark.sql.catalyst.util.ArrayBasedMapData ${expr.value} = null;
            java.util.Map $map = (java.util.Map<String, Object>)$pathName;
            boolean ${expr.isNull} = ($map == null);
            if (!${expr.isNull}) {
              Object[] $arrKeyRes = new Object[$map.size()];
              Object[] $arrValueRes = new Object[$map.size()];
              java.util.Iterator<java.util.Map.Entry<String, Object>> $itr = $map.entrySet().iterator();
              int $i = 0;
              while ($itr.hasNext()){
                java.util.Map.Entry<String, Object> $entry = (java.util.Map.Entry<String, Object>) $itr.next();
                ${typCodeK.code}
                ${typCodeV.code}
                $arrKeyRes[$i] = ${typCodeK.value};
                $arrValueRes[$i] = ${typCodeV.value};
                $i = $i + 1;
              }

              ${expr.value} = new org.apache.spark.sql.catalyst.util.ArrayBasedMapData(
                new org.apache.spark.sql.catalyst.util.GenericArrayData($arrKeyRes),
                new org.apache.spark.sql.catalyst.util.GenericArrayData($arrValueRes)
                );
            }
            """
        )
      }
    case _ => throw new DMNException(s"Could not load Kogito Result Provider for dataType $dataType")
  }

}
