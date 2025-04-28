package com.sparkutils.dmn.kogito

import com.sparkutils.dmn
import com.sparkutils.dmn.{DMNResult, DMNResultProvider}
import com.sparkutils.dmn.kogito.types.ContextInterfaces.Accessor
import com.sparkutils.dmn.kogito.types.ResultInterfaces
import com.sparkutils.dmn.kogito.types.ResultInterfaces.{EVALUATING, FAILED, NOT_EVALUATED, SKIPPED_ERROR, SKIPPED_WARN, SUCCEEDED, evalStatusEnding}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{GenericInternalRow, LeafExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.util.GenericArrayData
import org.apache.spark.sql.types.{ArrayType, BooleanType, DataType, IntegerType, StringType, StructField, StructType}
import org.apache.spark.unsafe.types.UTF8String
import org.kie.dmn.api.core
import org.kie.dmn.api.core.DMNDecisionResult.DecisionEvaluationStatus
import org.kie.dmn.feel.lang.types.impl.ComparablePeriod
import org.kie.kogito.dmn.rest.DMNFEELComparablePeriodSerializer
import sparkutilsKogito.com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import sparkutilsKogito.com.fasterxml.jackson.databind.module.SimpleModule
import sparkutilsKogito.com.fasterxml.jackson.annotation.JsonIgnoreProperties

import java.lang.annotation.Annotation
import scala.collection.JavaConverters._
import java.util

trait KogitoProcess extends DMNResultProvider {

  def nullable = false

  def debug: Boolean

  def underlyingType: StructType

  val debugDDL =
    ArrayType(StructType(Seq(
      StructField("decisionId", StringType),
      StructField("decisionName", StringType),
      StructField("hasErrors", BooleanType),
      StructField("messages", ArrayType(StructType(Seq(
        StructField("sourceId", StringType),
        StructField("sourceReference", StringType), // TODO should this be structs for DMNModelInstrumentedBase ?
        StructField("exception", StringType, nullable = true),
        StructField("feelEvent", StructType(Seq(
          StructField("severity", StringType),
          StructField("message", StringType),
          StructField("line", IntegerType),
          StructField("column", IntegerType), // TODO should this be structs for DMNModelInstrumentedBase ?
          StructField("sourceException", StringType, nullable = true),
          StructField("offendingSymbol", StringType)
        )))
      )))),
      StructField("evaluationStatus", StringType),
    )))

  override def dataType: DataType = {
    val nullables = underlyingType.copy(fields = underlyingType.fields.map(_.copy(nullable = true)))
    if (debug)
      nullables.copy(fields = nullables.fields :+ StructField("debugMode", debugDDL))
    else
      nullables
  }

  def process(result: org.kie.dmn.api.core.DMNResult): Any
  override def process(dmnResult: dmn.DMNResult): Any = {
    val res = dmnResult.asInstanceOf[KogitoDMNResult].result
    process(res)
  }
}

case class KogitoDDLResult(debug: Boolean, underlyingType: StructType) extends LeafExpression with KogitoProcess {

  lazy val getter = ResultInterfaces.forType(underlyingType)

  lazy val evalStatus = underlyingType.fields.zipWithIndex.filter(_._1.name.endsWith(evalStatusEnding))

  override def process(res: org.kie.dmn.api.core.DMNResult): Any = {
    val m = res.getDecisionResults.asScala.map(r => r.getDecisionName -> r.getResult).toMap.asJava
    val ires = {
      val tres = getter.get(m).asInstanceOf[GenericInternalRow]
      if (evalStatus.length == 0)
        tres
      else
        evalStatus.foldLeft(tres){
          case (row, (field, i)) =>
            val decisionName = field.name.dropRight(evalStatusEnding.length)
            val dr = res.getDecisionResultByName(decisionName)
            if (dr ne null) {
              row.update(i, dr.getEvaluationStatus match {
                case DecisionEvaluationStatus.NOT_EVALUATED => NOT_EVALUATED
                case DecisionEvaluationStatus.EVALUATING => EVALUATING
                case DecisionEvaluationStatus.SUCCEEDED => SUCCEEDED
                case DecisionEvaluationStatus.SKIPPED if dr.hasErrors => SKIPPED_ERROR
                case DecisionEvaluationStatus.SKIPPED => SKIPPED_WARN
                case DecisionEvaluationStatus.FAILED => FAILED
              })
            }
            row
        }
    }

    // create a map over the results
    if (res.hasErrors || res.getDecisionResults.isEmpty)
      null
    else
      if (debug)
        new GenericInternalRow(ires.values :+ new GenericArrayData(
          res.getDecisionResults.asScala.map{
            d =>
              InternalRow(
                UTF8String.fromString(d.getDecisionId),
                UTF8String.fromString(d.getDecisionName),
                d.hasErrors,
                new GenericArrayData(
                  d.getMessages.asScala.map{
                    m =>
                      InternalRow(
                        UTF8String.fromString(m.getSourceId),
                        UTF8String.fromString(m.getSourceReference.toString),
                        if (m.getException eq null) null else UTF8String.fromString(m.getException.getMessage),
                        InternalRow(
                          UTF8String.fromString(m.getFeelEvent.getSeverity.toString),
                          UTF8String.fromString(m.getFeelEvent.getMessage),
                          m.getFeelEvent.getLine,
                          m.getFeelEvent.getColumn,
                          if (m.getFeelEvent.getSourceException eq null) null else UTF8String.fromString(m.getFeelEvent.getSourceException.getMessage),
                          UTF8String.fromString(m.getFeelEvent.getOffendingSymbol.toString)
                        )
                      )
                  }
                ),
                UTF8String.fromString(d.getEvaluationStatus.toString)
              )
          }
        ))
      else
        ires
  }

  override def eval(input: InternalRow): Any = ???

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = ???
}

/**
 * @param debug
 */
case class KogitoJSONResultProvider(debug: Boolean) extends LeafExpression with DMNResultProvider with KogitoProcess {

  override def underlyingType: StructType = ???

  @transient
  lazy val mapper =
    new ObjectMapper()
      .registerModule(
        new SimpleModule()
          .addSerializer(classOf[ComparablePeriod], new DMNFEELComparablePeriodSerializer())
      )
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

  override def nullable: Boolean = true

  override def eval(input: InternalRow): Any = ???

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = ???

  override def dataType: DataType = StringType

  override def process(result: core.DMNResult): Any = {
    val what =
      if (debug)
        result.getDecisionResults
      else {
        result.getDecisionResults.asScala.map(r => r.getDecisionName -> r.getResult).toMap.asJava
      }

    UTF8String.fromString(mapper.writeValueAsString(
      what
    ))
  }
}