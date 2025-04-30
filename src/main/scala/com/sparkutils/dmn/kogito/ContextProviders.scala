package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.DMNContextPath
import com.sparkutils.dmn.impl.UTF8StringInputStreamContextProvider
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenContext
import org.kie.dmn.feel.lang.types.impl.ComparablePeriod
import org.kie.kogito.dmn.rest.DMNFEELComparablePeriodSerializer
import sparkutilsKogito.com.fasterxml.jackson.databind.module.SimpleModule
import sparkutilsKogito.com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}

import java.io.InputStreamReader
import java.util
import scala.reflect.ClassTag


case class KogitoJSONContextProvider(contextPath: DMNContextPath, child: Expression) extends UTF8StringInputStreamContextProvider[java.util.Map[String, Object]] {

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
