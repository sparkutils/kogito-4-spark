package com.sparkutils.dmn.kogito

import com.sparkutils.dmn
import com.sparkutils.dmn.impl.SeqOfBools
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{LeafExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.util.GenericArrayData

import scala.collection.JavaConverters._

import java.util

case class KogitoSeqOfBools() extends LeafExpression with SeqOfBools {

  override def process(dmnResult: dmn.DMNResult): Any = {
    val res = dmnResult.asInstanceOf[KogitoDMNResult].result
    if (res.hasErrors || res.getDecisionResults.isEmpty || res.getDecisionResults.asScala.head.hasErrors || res.getDecisionResults.asScala.head.getResult == null)
      null
    else
      new GenericArrayData(res.getDecisionResults.asScala.head.getResult.asInstanceOf[util.ArrayList[Boolean]].toArray)
  }

  override def eval(input: InternalRow): Any = ???

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = ???
}
