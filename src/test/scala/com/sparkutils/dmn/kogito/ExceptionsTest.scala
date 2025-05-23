package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.kogito.Errors.CONTEXT_PROVIDER_PARSE
import com.sparkutils.dmn.{DMNException, DMNExecution, DMNFile, DMNInputField, DMNModelService}
import frameless.{TypedDataset, TypedEncoder, TypedExpressionEncoder}
import org.apache.spark.sql.SaveMode
import org.junit.runner.RunWith
import org.scalatest.{FunSuite, Matchers}
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ExceptionsTest extends FunSuite with Matchers with TestUtils {

  val bns = "decisionsooo"
  val ns = "decisions"

  val testData =   TestData("US", "a", 1, 1, "sales")

  val badImportDmnFiles = scala.collection.immutable.Seq(
    DMNFile("decisions.dmn",
      this.getClass.getClassLoader.getResourceAsStream("decisions.dmn").readAllBytes()
    ),
    DMNFile("",
      this.getClass.getClassLoader.getResourceAsStream("common.dmn").readAllBytes()
    ),
  )
  val badDmnModel = DMNModelService(bns, bns, Some("DQService"), "struct<evaluate: array<boolean>>")
  val dmnModel = DMNModelService(ns, ns, Some("DQService"), "struct<evaluate: array<boolean>>")


  test("empty input expression should throw"){
    implicit val spark = sparkSession
    val tds = TypedDataset.create(Seq(testData)).dataset
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = DMNExecution(badImportDmnFiles, badDmnModel, scala.collection.immutable.Seq(
      DMNInputField("","","")
    ))
    val e = intercept[DMNException] {
      val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
      dres.select("quality.evaluate.*").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    }
    e.message should include(CONTEXT_PROVIDER_PARSE)
  }

  test("bad class input type should throw"){
    implicit val spark = sparkSession
    val tds = TypedDataset.create(Seq(testData)).dataset
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = DMNExecution(badImportDmnFiles, badDmnModel, scala.collection.immutable.Seq(
      DMNInputField("location","fred","")
    ))
    val e = intercept[DMNException] {
      val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
      dres.select("quality.evaluate.*").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    }
    e.message should include("Could not loadUnaryContextProvider fred")
  }

  test("unsupported ddl input type should throw"){
    implicit val spark = sparkSession
    val tds = TypedDataset.create(Seq(testData)).dataset
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = DMNExecution(badImportDmnFiles, badDmnModel, scala.collection.immutable.Seq(
      DMNInputField("location","interval","")
    ))
    val e = intercept[DMNException] {
      val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
      dres.select("quality.evaluate.*").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    }
    e.message should include("Provider type CalendarIntervalType is not supported")
  }

  test("unsupported nested ddl input type should throw"){
    evalCodeGens {
      implicit val spark = sparkSession
      val tds = TypedDataset.create(Seq(testData)).dataset
      val ds = if (inCodegen)
        tds.repartition(4)
      else
        tds

      val exec = DMNExecution(badImportDmnFiles, badDmnModel, scala.collection.immutable.Seq(
        DMNInputField("named_struct('i',location)", "struct<i: interval>", "")
      ))
      val e = intercept[DMNException] {
        val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
        dres.select("quality.evaluate.*").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
      }
      e.message should include("Could not load Kogito Context Accessor for dataType CalendarIntervalType")
    }
  }

  test("incompatible ddl should throw"){
    implicit val spark = sparkSession
    val tds = TypedDataset.create(Seq(testData)).dataset
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = DMNExecution(badImportDmnFiles, dmnModel.copy(resultProvider = "string"),
      scala.collection.immutable.Seq(
      DMNInputField("location","","")
    ))
    val e = intercept[DMNException] {
      val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
      dres.select("quality.evaluate.*").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    }
    e.message should include("ResultProvider type string is not supported,")
  }


  test("incompatible nested ddl should throw"){
    evalCodeGens {
      implicit val spark = sparkSession
      val tds = TypedDataset.create(Seq(testData)).dataset
      val ds = if (inCodegen) tds.repartition(4) else tds

      val exec = DMNExecution(badImportDmnFiles, dmnModel.copy(resultProvider = "struct<evaluate: interval>"),
        scala.collection.immutable.Seq(
          DMNInputField("location", "", "")
        ))
      val e = intercept[DMNException] {
        val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
        dres.select("quality.evaluate").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
      }
      e.message should include("Could not load Kogito Result Provider for dataType CalendarIntervalType")
    }
  }

  test("bad model should throw"){
    implicit val spark = sparkSession
    val tds = TypedDataset.create(Seq(testData)).dataset
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = DMNExecution(badImportDmnFiles, badDmnModel, scala.collection.immutable.Seq(
      DMNInputField("location","","")
    ))
    val e = intercept[DMNException] {
      val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
      dres.select("quality.evaluate").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    }
    e.message should include("Could not load model from Kogito runtime with namespace decisionsooo")
  }

  // doesn't actually throw - kogito doesn't seem to care about uri but uses the qname instead
  /*
  test("bad imports should throw"){
    implicit val spark = sparkSession
    val tds = TypedDataset.create(Seq(testData)).dataset
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = DMNExecution(badImportDmnFiles, dmnModel, scala.collection.immutable.Seq(
      DMNInputField("location","","")
    ))
    val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
    val asSeqs = dres.select("quality.evaluate").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()

  }*/

  val ons = "onetoone"

  val odmnFiles = scala.collection.immutable.Seq(
    DMNFile("onetoone.dmn",
      this.getClass.getClassLoader.getResourceAsStream("onetoone.dmn").readAllBytes()
    )
  )
  val odmnModel = DMNModelService(ons, ons, None, s"struct<evaluate: ${Others.ddl}>")

  test("bad result providers should throw"){
    implicit val spark = sparkSession
    val tds = TypedDataset.create(Seq(testData)).dataset
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = DMNExecution(odmnFiles, odmnModel.copy(resultProvider = "fred"),
      scala.collection.immutable.Seq(
      DMNInputField("struct(*)","","")
    ))
    val e = intercept[DMNException] {
      val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
      dres.select("quality.evaluate").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    }
    e.message should include("Could not loadResultProvider fred")
  }

  test("sqrt string should throw"){
    implicit val spark = sparkSession
    import sparkSession.implicits._

    val tds = TypedDataset.create(Seq("testData")).dataset
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = DMNExecution(scala.collection.immutable.Seq(
      DMNFile("sqrt_name.dmn",
        this.getClass.getClassLoader.getResourceAsStream("sqrt_name.dmn").readAllBytes()
      )
    ), DMNModelService("throws","throws", None, resultProvider = "struct<evaluate: double>"),
      scala.collection.immutable.Seq(
        DMNInputField("value","","inputData")
      ))
    val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec, debug = true))
    dres.show
    val messages = dres.select("quality.dmnMessages").as[Seq[KogitoMessage]].collect
    messages.length shouldBe 1
    messages.head.length shouldBe 1
    messages.head.head shouldBe KogitoMessage("_EEA70EE7-2AD0-4466-B326-8C0514EE2E6E","sqrt(\"my name\")",null,KogitoFeelEvent("ERROR","Unable to find function 'sqrt( lass org.kie.dmn.feel.runtime.functions.SqrtFunctio )'",-1,-1,null,null))
  }

}
