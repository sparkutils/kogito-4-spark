package com.sparkutils.dmn.kogito

import com.sparkutils.dmn
import com.sparkutils.dmn.DMNResultProvider
import com.sparkutils.dmn.impl.DMNExpression
import com.sparkutils.dmn.kogito.types.Utils.exprCode
import com.sparkutils.dmn.kogito.types.ResultInterfaces
import com.sparkutils.dmn.kogito.types.ResultInterfaces.{EVALUATING, FAILED, NOT_EVALUATED, NOT_FOUND, SKIPPED_ERROR, SKIPPED_WARN, SUCCEEDED, evalStatusEnding, forTypeCodeGen}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.Block.BlockHelper
import org.apache.spark.sql.catalyst.expressions.{GenericInternalRow, LeafExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, CodegenFallback, ExprCode}
import org.apache.spark.sql.catalyst.util.GenericArrayData
import org.apache.spark.sql.types.{ArrayType, BooleanType, DataType, IntegerType, StringType, StructField, StructType}
import org.apache.spark.unsafe.types.UTF8String
import org.kie.dmn.api.core
import org.kie.dmn.api.core.DMNDecisionResult.DecisionEvaluationStatus
import org.kie.dmn.api.core.{DMNMessage, DMNResult}
import org.kie.dmn.feel.lang.types.impl.ComparablePeriod
import org.kie.dmn.model.api.LiteralExpression
import org.kie.kogito.dmn.rest.DMNFEELComparablePeriodSerializer
import sparkutilsKogito.com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import sparkutilsKogito.com.fasterxml.jackson.databind.module.SimpleModule

import scala.collection.JavaConverters._
import scala.util.Try

trait KogitoProcess extends DMNResultProvider {

  def nullable = false

  def debug: Boolean

  def underlyingType: StructType

  val messagesDDL =
    ArrayType(StructType(Seq(
      StructField("sourceId", StringType),
      StructField("sourceReference", StringType), // TODO should this be structs for DMNModelInstrumentedBase ?
      StructField("exception", StringType, nullable = true),
      StructField("feelEvent", StructType(Seq(
        StructField("severity", StringType),
        StructField("message", StringType),
        StructField("line", IntegerType),
        StructField("column", IntegerType),
        StructField("sourceException", StringType, nullable = true),
        StructField("offendingSymbol", StringType)
      )))
    )))

  val debugDDL =
    ArrayType(StructType(Seq(
      StructField("decisionId", StringType),
      StructField("decisionName", StringType),
      StructField("hasErrors", BooleanType),
      StructField("messages", messagesDDL, nullable = true),
      StructField("evaluationStatus", StringType),
    )))

  override def dataType: DataType = {
    val nullables = underlyingType.copy(fields = underlyingType.fields.map(_.copy(nullable = true)))
    if (debug)
      nullables.copy(fields = nullables.fields ++ Seq(StructField("dmnDebugMode", debugDDL), StructField("dmnMessages", messagesDDL)))
    else
      nullables
  }

  def process(result: org.kie.dmn.api.core.DMNResult): Any
  override def process(dmnResult: dmn.DMNResult): Any = {
    val res = dmnResult.asInstanceOf[KogitoDMNResult].result
    process(res)
  }

  // helpful for debugging if you uncomment in the processors codegen
  // $COVERAGE-OFF$
  def checkResult(res: DMNResult): Unit = {
    println(res)
  }
  // $COVERAGE-ON$

  def kogitoResultStr = s"((${classOf[KogitoDMNResult].getName})${DMNExpression.runtimeVar.get()}).result()"

  val config: Map[String, String]

  def decisionMap(ctx: CodegenContext, decisionResults: String,
                  fullProxy: Boolean = Try(config.getOrElse("fullProxyDS", "true").toBoolean).
                    fold(_ => true, identity)): ExprCode = {
    val ex = exprCode(classOf[java.util.Map[_,_]], ctx)

    // assumption there aren't tonnes of decisions, ddl only asks for specific, json uses iterator.  Scala's map asJava is lazy and proxies iterator
    if (fullProxy) {
      val pe = ctx.addMutableState("com.sparkutils.dmn.kogito.types.DecisionResultFullProxyEntry", ctx.freshName("proxyEntryDS"),
        n => s"$n = new com.sparkutils.dmn.kogito.types.DecisionResultFullProxyEntry();"
        , useFreshName = false)
      val pm = ctx.addMutableState("com.sparkutils.dmn.kogito.types.ProxyMap", ctx.freshName("proxyEntryDS"),
        n => s"$n = new com.sparkutils.dmn.kogito.types.ProxyMap(0, null, $pe);"
        , useFreshName = false)

      ex.copy(
        code"""
          $pm.reset(  $decisionResults.size(),  $decisionResults );
          java.util.Map ${ex.value} = $pm;
          boolean ${ex.isNull} = false;
       """)
    } else
      ex.copy(
        code"""
          java.util.Map ${ex.value} = null;
          boolean ${ex.isNull} = false;
          ${ex.value} = new java.util.HashMap<String, Object>() {
              {{java.util.Iterator<org.kie.dmn.api.core.DMNDecisionResult> itr = $decisionResults.iterator();
                  while (itr.hasNext()){
                      org.kie.dmn.api.core.DMNDecisionResult r = (org.kie.dmn.api.core.DMNDecisionResult) itr.next();
                      put(r.getDecisionName(), r.getResult());
              }}}
          };

       """)
  }

}

case class KogitoDDLResult(debug: Boolean, underlyingType: StructType, config: Map[String, String]) extends LeafExpression with KogitoProcess {

  lazy val getter = ResultInterfaces.forType(underlyingType)

  lazy val evalStatus = underlyingType.fields.zipWithIndex.filter(_._1.name.endsWith(evalStatusEnding))

  override def process(res: org.kie.dmn.api.core.DMNResult): Any = {
    val m = res.getDecisionResults.asScala.map(r => r.getDecisionName -> r.getResult).toMap.asJava
    val ires = {
      val tres = getter.get(m).asInstanceOf[GenericInternalRow]
      if (evalStatus.length == 0)
        tres
      else
        setStatuses(tres, res)
    }

    // create a map over the results
    if (res.getDecisionResults.isEmpty)
      null
    else
      if (debug)
        withDebug(res, ires)
      else
        ires
  }

  def nullOr[A, R >: AnyRef](what: A)(f: A => R): R =
    if (what == null)
      null
    else
      f(what)

  def withMessages(messages: java.util.List[DMNMessage]): GenericArrayData =
    new GenericArrayData(
      messages.asScala.map {
        m =>
          InternalRow(
            nullOr(m.getSourceId)(a => UTF8String.fromString(a)),
            nullOr(m.getSourceReference){
              case feel: LiteralExpression => UTF8String.fromString(feel.getText)
              case a => UTF8String.fromString(a.toString)
            },
            nullOr(m.getException)(a => UTF8String.fromString(a.getMessage)),
            nullOr(m.getFeelEvent) { ev =>
              InternalRow(
                nullOr(ev.getSeverity)(a => UTF8String.fromString(a.toString)),
                UTF8String.fromString(ev.getMessage),
                ev.getLine,
                ev.getColumn,
                nullOr(ev.getSourceException)(a => UTF8String.fromString(a.getMessage)),
                nullOr(ev.getOffendingSymbol)(a => UTF8String.fromString(a.toString))
              )
            }
          )
      }
    )

  def withDebug(res: DMNResult, ires: GenericInternalRow) =
    new GenericInternalRow(ires.values ++ Seq( new GenericArrayData(
      res.getDecisionResults.asScala.map {
        d =>
          InternalRow(
            UTF8String.fromString(d.getDecisionId),
            UTF8String.fromString(d.getDecisionName),
            d.hasErrors,
            withMessages(d.getMessages),
            UTF8String.fromString(d.getEvaluationStatus.toString)
          )
      }
      ),
      withMessages(res.getMessages)
    ))

  def setStatuses(tres: GenericInternalRow, res: org.kie.dmn.api.core.DMNResult): GenericInternalRow =
    evalStatus.foldLeft(tres) {
      case (row, (field, i)) =>
        val decisionName = field.name.dropRight(evalStatusEnding.length)
        val dr = res.getDecisionResultByName(decisionName)
        if (dr ne null) {
          row.update(i, dr.getEvaluationStatus match {
            // $COVERAGE-OFF$
            // would require throwing in the evaluation, not sure how to reproduce that
            case DecisionEvaluationStatus.NOT_EVALUATED => NOT_EVALUATED
            case DecisionEvaluationStatus.EVALUATING => EVALUATING
            // $COVERAGE-ON$
            case DecisionEvaluationStatus.SUCCEEDED => SUCCEEDED
            case DecisionEvaluationStatus.SKIPPED if dr.hasErrors => SKIPPED_ERROR
            case DecisionEvaluationStatus.SKIPPED => SKIPPED_WARN
            case DecisionEvaluationStatus.FAILED => FAILED
          })
        } else {
          row.update(i, NOT_FOUND)
        }
        row
    }

  // $COVERAGE-OFF$
  override def eval(input: InternalRow): Any = ???
  // $COVERAGE-ON$

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    ctx.references += this
    val ddlResultClassName = classOf[KogitoDDLResult].getName

    val resultIdx = ctx.references.size - 1

    val decisionResults = ctx.freshName("decisionResults")
    val dm = decisionMap(ctx, decisionResults)
    val decisionMapN = dm.value


    val getExpr = forTypeCodeGen(underlyingType).forPath(ctx, decisionMapN)

    ev.copy(code =
      code"""
         org.apache.spark.sql.catalyst.expressions.GenericInternalRow ${ev.value} = null;
         // uncomment to debug the result
         // (($ddlResultClassName)references[$resultIdx]).checkResult($kogitoResultStr);
         boolean ${ev.isNull} = ($kogitoResultStr == null);
         if (!${ev.isNull}) {
           final java.util.List<org.kie.dmn.api.core.DMNDecisionResult> $decisionResults = $kogitoResultStr.getDecisionResults();
           ${dm.code}
           ${getExpr.code}

           ${ev.value} = ${getExpr.value};

           ${ev.isNull} = (${ev.value} == null);
           if (!${ev.isNull}) {
             ${if (evalStatus.nonEmpty) code"""${ev.value} = (($ddlResultClassName)references[$resultIdx]).setStatuses(${getExpr.value}, $kogitoResultStr);""" else code""}

             ${if (debug) code"${ev.value} = (($ddlResultClassName)references[$resultIdx]).withDebug($kogitoResultStr, ${ev.value});" else code""}
             ${ev.isNull} = (${ev.value} == null);
           }
         }
          """)
  }
}

/**
 * @param debug
 */
case class KogitoJSONResultProvider(debug: Boolean, config: Map[String, String]) extends LeafExpression with DMNResultProvider with KogitoProcess {

  // $COVERAGE-OFF$
  override def underlyingType: StructType = ???
  // $COVERAGE-ON$

  @transient
  lazy val mapper =
    new ObjectMapper()
      .registerModule(
        new SimpleModule()
          .addSerializer(classOf[ComparablePeriod], new DMNFEELComparablePeriodSerializer())
      )
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

  override def nullable: Boolean = true

  // $COVERAGE-OFF$
  override def eval(input: InternalRow): Any = ???
  // $COVERAGE-ON$

  override def dataType: DataType = StringType

  override def process(result: core.DMNResult): Any = {
    val what =
      if (debug)
        result.getDecisionResults
      else
        result.getDecisionResults.asScala.map(r => r.getDecisionName -> r.getResult).toMap.asJava

    UTF8String.fromString(mapper.writeValueAsString(
      what
    ))
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val mapperName = ctx.addMutableState(classOf[ObjectMapper].getName, "mapper", v =>
      s"""
         $v = new ${classOf[ObjectMapper].getName}().registerModule(
            new ${classOf[SimpleModule].getName}()
              .addSerializer(${classOf[ComparablePeriod].getName}.class, new ${classOf[DMNFEELComparablePeriodSerializer].getName}())
          )
          .disable(${classOf[SerializationFeature].getName}.FAIL_ON_EMPTY_BEANS);
         """)

    val decisionResults = ctx.freshName("decisionResults")
    val what = ctx.freshName("what")
    val dm = decisionMap(ctx, decisionResults)

    ev.copy(code =
      code"""
         UTF8String ${ev.value} = null;
         boolean ${ev.isNull} = false;
         final java.util.List<org.kie.dmn.api.core.DMNDecisionResult> $decisionResults = $kogitoResultStr.getDecisionResults();
         ${dm.code}
         Object $what = ${
          if (debug)
            s"$decisionResults;"
          else
            s"${dm.value};"
          }
          try {
            ${ev.value} = UTF8String.fromString($mapperName.writeValueAsString(
              $what
            ));
            ${ev.isNull} = ${ev.value} == null;
          } catch (sparkutilsKogito.com.fasterxml.jackson.core.JsonProcessingException e) {
            ${ev.isNull} = true;
          }
      """)
  }
}