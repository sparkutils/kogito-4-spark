package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.{DMNContextPath, DMNException, DMNInputField, UnaryDMNContextProvider}
import com.sparkutils.dmn.impl.{SimpleContextProvider, StringContextProvider, StringWithIOProcessorContextProvider}
import com.sparkutils.dmn.kogito.types.ContextInterfaces
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types.{ArrayType, BinaryType, BooleanType, ByteType, DataType, DateType, Decimal, DecimalType, DoubleType, FloatType, IntegerType, LongType, MapType, ObjectType, ShortType, StringType, StructType, TimestampType}
import org.kie.dmn.feel.lang.types.impl.ComparablePeriod
import org.kie.kogito.dmn.rest.DMNFEELComparablePeriodSerializer
import sparkutilsKogito.com.fasterxml.jackson.databind.module.SimpleModule
import sparkutilsKogito.com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}

import java.time.{LocalDate, LocalDateTime}
import java.util

case class KogitoJSONContextProvider(contextPath: DMNContextPath, stillSetWhenNull: Boolean, child: Expression) extends StringWithIOProcessorContextProvider[java.util.Map[String, Object]] {

  @transient
  lazy val mapper =
    new ObjectMapper()
      .registerModule(
        new SimpleModule()
          .addSerializer(classOf[ComparablePeriod], new DMNFEELComparablePeriodSerializer())
      )
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

  def withNewChildInternal(newChild: Expression): Expression = copy(child = newChild)

//  override def readValue(str: InputStreamReader): java.util.Map[String, Object] = mapper.readValue(str, classOf[java.util.Map[String, Object]])
  override def readValue(str: String): java.util.Map[String, Object] = mapper.readValue(str, classOf[java.util.Map[String, Object]])

  override def codeGen(inputStreamReaderVal: String, ctx: CodegenContext): String = {
    val mapperName = ctx.addMutableState(classOf[ObjectMapper].getName, "mapper", v =>
      s"""
         $v = new ${classOf[ObjectMapper].getName}().registerModule(
            new ${classOf[SimpleModule].getName}()
              .addSerializer(${classOf[ComparablePeriod].getName}.class, new ${classOf[DMNFEELComparablePeriodSerializer].getName}())
          )
          .disable(${classOf[SerializationFeature].getName}.FAIL_ON_EMPTY_BEANS);
         """)

    s"$mapperName.readValue($inputStreamReaderVal, java.util.Map.class)"
  }

  val resultType: Class[util.Map[String, Object]] = classOf[util.Map[String, Object]]
}

/**
 * When a type is not provided attempt to late bind the type
 * @param contextPath
 * @param stillSetWhenNull
 * @param child
 * @param child
 * @param config
 */
case class ContextProviderProxy(contextPath: DMNContextPath, stillSetWhenNull: Boolean,
                                child: Expression, config: Map[String, String]) extends UnaryDMNContextProvider[Any] {

  override def eval(input: InternalRow): Any = child.eval(input)

  // $COVERAGE-OFF$
  override protected def nullSafeContextEval(input: Any): Any = ???
  // $COVERAGE-ON$

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = child.genCode(ctx)

  override protected def withNewChildInternal(newChild: Expression): Expression = {
    val nchild = {
      // object types are either because we already substituted or due to literal constant folds
      if (newChild.resolved && !newChild.dataType.isInstanceOf[ObjectType])
        ContextProviders.contextProviderFromDDL(stillSetWhenNull, contextPath.asInstanceOf[KogitoDMNContextPath], newChild, config, newChild.dataType)
      else
        newChild
    }
    copy(child = nchild)
  }

  override val resultType: Class[Any] = classOf[Any]
}

object ContextProviders {
  private[kogito] def contextProviderFromDDL(stillSetWhenNull: Boolean, path: KogitoDMNContextPath, expr: Expression, config: Map[String, String], dataType: DataType) = 
    dataType match {
      case StringType => StringContextProvider(path, stillSetWhenNull, expr)
      case IntegerType => SimpleContextProvider[Integer](path, stillSetWhenNull, expr)
      case LongType => SimpleContextProvider[Long](path, stillSetWhenNull, expr)
      case BooleanType => SimpleContextProvider[Boolean](path, stillSetWhenNull, expr)
      case DoubleType => SimpleContextProvider[Double](path, stillSetWhenNull, expr)
      case FloatType => SimpleContextProvider[Float](path, stillSetWhenNull, expr)
      case BinaryType => SimpleContextProvider[Array[Byte]](path, stillSetWhenNull, expr)
      case ByteType => SimpleContextProvider[Byte](path, stillSetWhenNull, expr)
      case ShortType => SimpleContextProvider[Short](path, stillSetWhenNull, expr)
      case DateType => SimpleContextProvider[LocalDate](path, stillSetWhenNull, expr,
        Some(((t: Any) => DateTimeUtils.daysToLocalDate(t.asInstanceOf[Int]),
          (codegen, input) => s"org.apache.spark.sql.catalyst.util.DateTimeUtils.daysToLocalDate((int)$input)"))
      ) // an int
      case TimestampType => SimpleContextProvider[LocalDateTime](path, stillSetWhenNull, expr,
        Some(((t: Any) => DateTimeUtils.microsToLocalDateTime(t.asInstanceOf[Long]),
          (codegen, input) => s"org.apache.spark.sql.catalyst.util.DateTimeUtils.microsToLocalDateTime((long)$input)"))
      ) // a long
      case _: DecimalType => SimpleContextProvider[java.math.BigDecimal](path, stillSetWhenNull, expr,
        Some(((t: Any) => t.asInstanceOf[Decimal].toJavaBigDecimal,
          (codegen, input) => s"((${classOf[Decimal].getName})$input).toJavaBigDecimal()"))
      )
      case structType: StructType => ContextInterfaces.structProvider(structType, path, expr, stillSetWhenNull, config)
      case mapType: MapType => ContextInterfaces.mapProvider(mapType, path, expr, stillSetWhenNull, config)
      case arrayType: ArrayType => ContextInterfaces.arrayProvider(arrayType, path, expr, stillSetWhenNull, config)
      // calendar interval?
      case t => throw new DMNException(s"Provider type $t is not supported")
    }

}