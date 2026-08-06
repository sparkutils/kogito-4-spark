package com.sparkutils.dmn.kogito.common

import com.sparkutils.dmn
import com.sparkutils.dmn.kogito.infra.{Others => O}
import com.sparkutils.dmn.kogito.{KogitoFeelEvent, KogitoMessage, Errors => E} // api not impl
import frameless.{TypedDataset, TypedExpressionEncoder}

class ExceptionsTest extends SparkTests {

  val bns = "decisionsooo"
  val ns = "decisions"

  val testData =   TestData("US", "a", 1, 1, "sales")

  val badImportDmnFiles = scala.collection.immutable.Seq(
    dmn.DMNFile("decisions.dmn",
      this.getClass.getClassLoader.getResourceAsStream("decisions.dmn").readAllBytes()
    ),
    dmn.DMNFile("",
      this.getClass.getClassLoader.getResourceAsStream("common.dmn").readAllBytes()
    ),
  )
  val badDmnModel = dmn.DMNModelService(bns, bns, Some("DQService"), "struct<evaluate: array<boolean>>")
  val dmnModel = dmn.DMNModelService(ns, ns, Some("DQService"), "struct<evaluate: array<boolean>>")


  test("empty input expression should throw") {
    implicit val s = sparkSession
    import s.implicits._

    val tds = Seq(testData).toDS
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = dmn.DMNExecution(badImportDmnFiles, badDmnModel, scala.collection.immutable.Seq(
      dmn.DMNInputField("","","")
    ))
    val e = intercept[Exception] {
      val dres = ds.withColumn("quality", dmnEval(exec))
      dres.select("quality.evaluate.*").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    }
    e.getMessage should include(E.CONTEXT_PROVIDER_PARSE)
  }

  test("bad class input type should throw"){
    implicit val s = sparkSession
    import s.implicits._

    val tds = Seq(testData).toDS
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = dmn.DMNExecution(badImportDmnFiles, badDmnModel, scala.collection.immutable.Seq(
      dmn.DMNInputField("location","fred","")
    ))
    val e = intercept[Exception] {
      val dres = ds.withColumn("quality", dmnEval(exec))
      dres.select("quality.evaluate.*").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    }
    e.getMessage should include("Could not loadUnaryContextProvider fred")
  }

  test("unsupported ddl input type should throw"){
    implicit val s = sparkSession
    import s.implicits._

    val tds = Seq(testData).toDS()
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = dmn.DMNExecution(badImportDmnFiles, badDmnModel, scala.collection.immutable.Seq(
      dmn.DMNInputField("location","interval","")
    ))
    val e = intercept[Exception] {
      val dres = ds.withColumn("quality", dmnEval(exec))
      dres.select("quality.evaluate.*").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    }
    e.getMessage should include("Provider type CalendarIntervalType is not supported")
  }

  test("unsupported nested ddl input type should throw"){
    evalCodeGens {
      implicit val s = sparkSession
      import s.implicits._

      val tds = Seq(testData).toDS()
      val ds = if (inCodegen)
        tds.repartition(4)
      else
        tds

      val exec = dmn.DMNExecution(badImportDmnFiles, badDmnModel, scala.collection.immutable.Seq(
        dmn.DMNInputField("named_struct('i',location)", "struct<i: interval>", "")
      ))
      val e = intercept[Exception] {
        val dres = ds.withColumn("quality", dmnEval(exec))
        dres.select("quality.evaluate.*").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
      }
      e.getMessage should include("Could not load Kogito Context Accessor for dataType CalendarIntervalType")
    }
  }

  test("incompatible ddl should throw"){
    implicit val s = sparkSession
    import s.implicits._

    val tds = Seq(testData).toDS()
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = dmn.DMNExecution(badImportDmnFiles, dmnModel.copy(resultProvider = "string"),
      scala.collection.immutable.Seq(
      dmn.DMNInputField("location","","")
    ))
    val e = intercept[Exception] {
      val dres = ds.withColumn("quality", dmnEval(exec))
      dres.select("quality.evaluate.*").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    }
    e.getMessage should include("ResultProvider type string is not supported,")
  }

  test("incompatible nested ddl should throw"){
    evalCodeGens {
      implicit val s = sparkSession
      import s.implicits._

      val tds = Seq(testData).toDS()
      val ds = if (inCodegen) tds.repartition(4) else tds

      val exec = dmn.DMNExecution(badImportDmnFiles, dmnModel.copy(resultProvider = "struct<evaluate: INTERVAL YEAR>"),
        scala.collection.immutable.Seq(
          dmn.DMNInputField("location", "", "")
        ))
      val e = (intercept[Exception]) {
        val dres = ds.withColumn("quality", dmnEval(exec))
        dres.select("quality.evaluate").as[java.time.Period](TypedExpressionEncoder[java.time.Period]).collect()
      }

      e.getMessage should include("Could not load Kogito Result Provider for dataType YearMonthIntervalType(0,0)")
    }
  }

  test("bad model should throw"){
    implicit val s = sparkSession
    import s.implicits._

    val tds = Seq(testData).toDS()
    val ds = if (inCodegen) tds.repartition(4) else tds

    val e = intercept[Throwable] {
      val exec = dmn.DMNExecution(badImportDmnFiles, badDmnModel, scala.collection.immutable.Seq(
        dmn.DMNInputField("location","","")
      ))
      val dres = ds.withColumn("quality", dmnEval(exec))
      dres.select("quality.evaluate").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    }
    e.getMessage should include("Could not load model from Kogito runtime with namespace decisionsooo")
  }

  // doesn't actually throw - kogito doesn't seem to care about uri but uses the qname instead
  /*
  test("bad imports should throw"){
    implicit val spark = sparkSession
    val tds = TypedDataset.create(Seq(testData)).dataset
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = dmn.DMNExecution(badImportDmnFiles, dmnModel, scala.collection.immutable.Seq(
      dmn.DMNInputField("location","","")
    ))
    val dres = ds.withColumn("quality", dmnEval(exec))
    val asSeqs = dres.select("quality.evaluate").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()

  }*/

  val ons = "onetoone"

  val odmnFiles = scala.collection.immutable.Seq(
    dmn.DMNFile("onetoone.dmn",
      this.getClass.getClassLoader.getResourceAsStream("onetoone.dmn").readAllBytes()
    )
  )
  val odmnModel = dmn.DMNModelService(ons, ons, None, s"struct<evaluate: ${O.ddl}>")

  test("bad result providers should throw") {
    implicit val spark = sparkSession
    import spark.implicits._

    val tds = Seq(testData).toDS()
    val ds = if (inCodegen) tds.repartition(4) else tds

    val e = intercept[Throwable] {

      val exec = dmn.DMNExecution(odmnFiles, odmnModel.copy(resultProvider = "fred"),
        scala.collection.immutable.Seq(
          dmn.DMNInputField("struct(*)","","")
        ))
      val dres = ds.withColumn("quality", dmnEval(exec))
      dres.select("quality.evaluate").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    }
    e.getMessage should include("Could not loadResultProvider fred")
  }

  test("sqrt string should throw"){
    implicit val s = sparkSession
    import s.implicits._

    val tds = Seq("testData").toDS()
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = dmn.DMNExecution(scala.collection.immutable.Seq(
      dmn.DMNFile("sqrt_name.dmn",
        this.getClass.getClassLoader.getResourceAsStream("sqrt_name.dmn").readAllBytes()
      )
    ), dmn.DMNModelService("throws","throws", None, resultProvider = "struct<evaluate: double>"),
      scala.collection.immutable.Seq(
        dmn.DMNInputField("value","","inputData")
      ))
    val dres = ds.withColumn("quality", dmnEval(exec, debug = true))
    dres.show
    val messages = dres.select("quality.messages").as[Seq[KogitoMessage]].collect
    messages.length shouldBe 1
    messages.head.length should be >= 1
    messages.head.head shouldBe KogitoMessage("_EEA70EE7-2AD0-4466-B326-8C0514EE2E6E","sqrt(\"my name\")",null,
      KogitoFeelEvent("ERROR","Unable to find function 'sqrt( lass org.kie.dmn.feel.runtime.functions.SqrtFunctio )'",-1,-1,null,null))
  }

}
