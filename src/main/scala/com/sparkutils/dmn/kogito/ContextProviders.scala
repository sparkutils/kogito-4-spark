package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.{DMNContextPath, JSONContext}
import org.apache.spark.sql.catalyst.expressions.Expression
import org.kie.dmn.feel.lang.types.impl.ComparablePeriod
import org.kie.kogito.dmn.rest.DMNFEELComparablePeriodSerializer
import sparkutilsKogito.com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import sparkutilsKogito.com.fasterxml.jackson.databind.module.SimpleModule

import java.io.InputStreamReader


case class KogitoJSONContext(contextPath: DMNContextPath, child: Expression) extends JSONContext {

  @transient
  lazy val mapper =
    new ObjectMapper()
      .registerModule(
        new SimpleModule()
          .addSerializer(classOf[ComparablePeriod], new DMNFEELComparablePeriodSerializer())
      )
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

  def withNewChildInternal(newChild: Expression): Expression = copy(child = newChild)

  override val resultClass: Class[_] = classOf[java.util.Map[String, Object]]

  override def readValue(str: InputStreamReader): Any = mapper.readValue(str, resultClass)
}
