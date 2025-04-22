package com.sparkutils.dmn.kogito

import com.sparkutils.dmn
import com.sparkutils.dmn.impl.SeqOfBools
import org.apache.spark.sql.catalyst.util.GenericArrayData

import java.util

case class KogitoSeqOfBools() extends SeqOfBools {

  override def process(dmnResult: dmn.DMNResult): Any = {
    val res = dmnResult.asInstanceOf[KogitoDMNResult].result
    if (res.hasErrors || res.getDecisionResults.isEmpty || res.getDecisionResults.getFirst.hasErrors || res.getDecisionResults.getFirst.getResult == null)
      null
    else
      new GenericArrayData(res.getDecisionResults.getFirst.getResult.asInstanceOf[util.ArrayList[Boolean]].toArray)
  }

}
