package com.sparkutils.dmn.kogito

import com.sparkutils.dmn
import com.sparkutils.dmn.impl.SeqOfBools
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{LeafExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.util.GenericArrayData

import java.util

case class KogitoSeqOfBools() extends LeafExpression with SeqOfBools {

  override def process(dmnResult: dmn.DMNResult): Any = {
    val res = dmnResult.asInstanceOf[KogitoDMNResult].result
    if (res.hasErrors || res.getDecisionResults.isEmpty || res.getDecisionResults.getFirst.hasErrors || res.getDecisionResults.getFirst.getResult == null)
      null
    else
      new GenericArrayData(res.getDecisionResults.getFirst.getResult.asInstanceOf[util.ArrayList[Boolean]].toArray)
  }

  override def eval(input: InternalRow): Any = ???

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = ???
}
