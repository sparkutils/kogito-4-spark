package com.sparkutils.dmn.kogito

import com.sparkutils.dmn
import com.sparkutils.dmn.{DMNResult, DMNResultProvider}
import com.sparkutils.dmn.impl.SeqOfBools
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.LeafExpression
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.util.GenericArrayData
import org.apache.spark.sql.types.{DataType, StringType}
import org.apache.spark.unsafe.types.UTF8String
import org.kie.dmn.api.core
import org.kie.dmn.feel.lang.types.impl.ComparablePeriod
import org.kie.kogito.dmn.rest.DMNFEELComparablePeriodSerializer
import sparkutilsKogito.com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import sparkutilsKogito.com.fasterxml.jackson.databind.module.SimpleModule

import scala.collection.JavaConverters._
import java.util

trait KogitoProcess extends DMNResultProvider {
  def process(result: org.kie.dmn.api.core.DMNResult): Any
  override def process(dmnResult: dmn.DMNResult): Any = {
    val res = dmnResult.asInstanceOf[KogitoDMNResult].result
    process(res)
  }
}

case class KogitoSeqOfBools() extends LeafExpression with SeqOfBools with KogitoProcess {

  override def process(res: org.kie.dmn.api.core.DMNResult): Any = {
    if (res.hasErrors || res.getDecisionResults.isEmpty || res.getDecisionResults.asScala.head.hasErrors || res.getDecisionResults.asScala.head.getResult == null)
      null
    else
      new GenericArrayData(res.getDecisionResults.asScala.head.getResult.asInstanceOf[util.ArrayList[Boolean]].toArray)
  }

  override def eval(input: InternalRow): Any = ???

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = ???
}

case class KogitoJSONResultProvider() extends LeafExpression with DMNResultProvider with KogitoProcess {

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

  override def process(result: core.DMNResult): Any =
    UTF8String.fromString(mapper.writeValueAsString(result.getDecisionResults))
}