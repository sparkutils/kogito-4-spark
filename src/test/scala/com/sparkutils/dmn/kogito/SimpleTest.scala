package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.kogito.types.ResultInterfaces.{SUCCEEDED, evalStatusEnding}
import com.sparkutils.dmn.{DMNExecution, DMNFile, DMNInputField, DMNModelService}
import frameless.{TypedDataset, TypedEncoder, TypedExpressionEncoder}
import org.apache.spark.sql.{DataFrame, SaveMode}
import org.junit.runner.RunWith
import org.scalatest.{FunSuite, Matchers}
import org.scalatestplus.junit.JUnitRunner

//import scala.collection.immutable.Seq

case class TestData(location: String, idPrefix: String, id: Int, page: Long, department: String)

@RunWith(classOf[JUnitRunner])
class SimpleTest extends FunSuite with Matchers with TestUtils {

  val ns = "decisions"

  val dmnFiles = scala.collection.immutable.Seq(
    DMNFile("common.dmn",
      this.getClass.getClassLoader.getResourceAsStream("common.dmn").readAllBytes()
    ),
    DMNFile("decisions.dmn",
      this.getClass.getClassLoader.getResourceAsStream("decisions.dmn").readAllBytes()
    )
  )
  val dmnModel = DMNModelService(ns, ns, Some("DQService"), "struct<evaluate: array<boolean>>")
  val dataBasis = Seq(
    TestData("US", "a", 1, 1, "sales"),
    TestData("UK", "a", 1, 2, "marketing"),
    TestData("CH", "a", 1, 3, "hr"),
    TestData("MX", "a", 1, 4, "it"),
    TestData("BR", "a", 1, 5, "ops"),
  )
  val dataBasisNulls = Seq(
    TestData(null, null , 1, 1, "sales"),
    TestData("UK", "a", 1, 2, null),
    TestData("CH", null, 1, 3, "hr"),
    TestData(null, "a", 1, 4, "it"),
    TestData(null, "a", 1, 5, null),
  )

  def testResults(res: DataFrame): Unit = {
    import sparkSession.implicits._

    //res.select("quality.evaluate").write.mode(SaveMode.Overwrite).parquet(outputDir+"/simples")
    //val asSeqs = sparkSession.read.parquet(outputDir+"/simples").as[Seq[Boolean]].collect()
    val asSeqs =  res.select("quality.evaluate").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()

    asSeqs.forall(_.size == 15) shouldBe true
    asSeqs(0).head shouldBe true
    asSeqs(0)(10) shouldBe true
    asSeqs(0)(12) shouldBe true
    asSeqs(0).count(identity) shouldBe 3
    asSeqs(1)(1) shouldBe true
    asSeqs(1).count(identity) shouldBe 1
  }

  val testDebug = KogitoResult("_1B2DFBAA-DD62-4F1D-A375-38FB6A868A8C", "evaluate", false, Seq(), "SUCCEEDED")

  def testResults(ds: DataFrame, exec: DMNExecution): Unit = evalCodeGens {
    import sparkSession.implicits._

    val res = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
    testResults(res)
    val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec, debug = true))
    testResults(dres)
    val debugs = dres.select("quality.debugMode").as[Seq[KogitoResult]].collect
    debugs.forall( _ == Seq(testDebug)) shouldBe true
    if (exec.model.resultProvider.contains(evalStatusEnding)) {
      val statuses = dres.select(s"quality.evaluate$evalStatusEnding").as[Byte](TypedExpressionEncoder[Byte]).collect()
      statuses.forall( _ == SUCCEEDED ) shouldBe true
    }
  }

  def testJSONResults(service: DMNModelService): Unit = {
    import sparkSession.implicits._

    val ds = Seq(dataBasis).toDS.selectExpr("explode(value) as f").selectExpr("to_json(f) payload")

    val exec = DMNExecution(dmnFiles, service,
      scala.collection.immutable.Seq(DMNInputField("payload", "JSON", "testData")))
    testResults(ds, exec)
  }

  def testTopLevelFieldsResults(service: DMNModelService, deriveContextTypes: Boolean): Unit = {
    import sparkSession.implicits._

    val ds = Seq(dataBasis).toDS.selectExpr("explode(value) as f").selectExpr("f.*")

    val exec = DMNExecution(dmnFiles, service,
      scala.collection.immutable.Seq(
        DMNInputField("location", if (deriveContextTypes) "" else "String", "testData.location"),
        DMNInputField("idPrefix", if (deriveContextTypes) "" else "String", "testData.idPrefix"),
        DMNInputField("id", if (deriveContextTypes) "" else "Int", "testData.id"),
        DMNInputField("page", if (deriveContextTypes) "" else "Long", "testData.page"),
        DMNInputField("department", if (deriveContextTypes) "" else "String", "testData.department")
      )) //location: String, idPrefix: String, id: Int, page: Long, department: String)
    testResults(ds, exec)
  }

  def testTopLevelStructResults(service: DMNModelService, deriveContextTypes: Boolean): Unit = {
    import sparkSession.implicits._

    val ds = Seq(dataBasis).toDS.selectExpr("explode(value) as f")

    val exec = DMNExecution(dmnFiles, service,
      scala.collection.immutable.Seq(DMNInputField("f", if (deriveContextTypes) "" else
        "struct<location: String, idPrefix: String, id: Int, page: Long, department: String>", "testData")
      )) //location: String, idPrefix: String, id: Int, page: Long, department: String)
    testResults(ds, exec)
  }

  def testStarResults(service: DMNModelService, deriveContextTypes: Boolean): Unit = {
    import sparkSession.implicits._

    val ds = Seq(dataBasis).toDS.toDF.selectExpr("explode(value) f").select("f.*")

    val exec = DMNExecution(dmnFiles, service,
      scala.collection.immutable.Seq(DMNInputField("struct(*)", if (deriveContextTypes) "" else
        "struct<location: String, idPrefix: String, id: Int, page: Long, department: String>", "testData")
      )) //location: String, idPrefix: String, id: Int, page: Long, department: String)
    testResults(ds, exec)
  }

  test("Loading of Kogito and sample test should work - decision service json") {
    testJSONResults(dmnModel)
  }

  test("Loading of Kogito and sample test should work - evaluate all json") {
    testJSONResults(dmnModel.copy(service = None))
  }

  test("Loading of Kogito and sample test should work - decision service top level fields") {
    testTopLevelFieldsResults(dmnModel, deriveContextTypes = false)
  }

  test("Loading of Kogito and sample test should work - evaluate all top level fields") {
    testTopLevelFieldsResults(dmnModel.copy(service = None), deriveContextTypes = false)
  }

  test("Loading of Kogito and sample test should work - decision service top level struct") {
    testTopLevelStructResults(dmnModel, deriveContextTypes = false)
  }

  test("Loading of Kogito and sample test should work - evaluate all top level struct") {
    testTopLevelStructResults(dmnModel.copy(service = None), deriveContextTypes = false)
  }

  test("Loading of Kogito and sample test should work - evaluate *") {
    testStarResults(dmnModel.copy(service = None), deriveContextTypes = false)
  }

  test("Loading of Kogito and sample test should work - decision service top level fields - derive context types") {
    testTopLevelFieldsResults(dmnModel, deriveContextTypes = true)
  }

  test("Loading of Kogito and sample test should work - evaluate all top level fields - derive context types") {
    testTopLevelFieldsResults(dmnModel.copy(service = None), deriveContextTypes = true)
  }

  test("Loading of Kogito and sample test should work - decision service top level struct - derive context types") {
    testTopLevelStructResults(dmnModel, deriveContextTypes = true)
  }

  test("Loading of Kogito and sample test should work - evaluate all top level struct - derive context types") {
    testTopLevelStructResults(dmnModel.copy(service = None), deriveContextTypes = true)
  }

  test("Loading of Kogito and sample test should work - evaluate * - derive context types") {
    testStarResults(dmnModel.copy(service = None), deriveContextTypes = true)
  }

  def evalStatus(dmnModel: DMNModelService): DMNModelService =
    dmnModel.copy(resultProvider = dmnModel.resultProvider.replace(">>", s">, evaluate$evalStatusEnding: Byte>"))

  test("Loading of Kogito and sample test should work - decision service json - evalStatus") {
    testJSONResults(evalStatus(dmnModel))
  }

  test("Loading of Kogito and sample test should work - evaluate all json - evalStatus") {
    testJSONResults(evalStatus(dmnModel.copy(service = None)))
  }

  test("Loading of Kogito and sample test should work - decision service top level fields - evalStatus") {
    testTopLevelFieldsResults(evalStatus(dmnModel), deriveContextTypes = false)
  }

  test("Loading of Kogito and sample test should work - evaluate all top level fields - evalStatus") {
    testTopLevelFieldsResults(evalStatus(dmnModel.copy(service = None)), deriveContextTypes = false)
  }

  test("Loading of Kogito and sample test should work - decision service top level struct - evalStatus") {
    testTopLevelStructResults(evalStatus(dmnModel), deriveContextTypes = false)
  }

  test("Loading of Kogito and sample test should work - evaluate all top level struct - evalStatus") {
    testTopLevelStructResults(evalStatus(dmnModel.copy(service = None)), deriveContextTypes = false)
  }

  test("Loading of Kogito and sample test should work - decision service top level fields - evalStatus - derive context types") {
    testTopLevelFieldsResults(evalStatus(dmnModel), deriveContextTypes = true)
  }

  test("Loading of Kogito and sample test should work - evaluate all top level fields - evalStatus - derive context types") {
    testTopLevelFieldsResults(evalStatus(dmnModel.copy(service = None)), deriveContextTypes = true)
  }

  test("Loading of Kogito and sample test should work - decision service top level struct - evalStatus - derive context types") {
    testTopLevelStructResults(evalStatus(dmnModel), deriveContextTypes = true)
  }

  test("Loading of Kogito and sample test should work - evaluate all top level struct - evalStatus - derive context types") {
    testTopLevelStructResults(evalStatus(dmnModel.copy(service = None)), deriveContextTypes = true)
  }

  test("Write as json - debug") { evalCodeGens {
    import sparkSession.implicits._

    val ds = Seq(dataBasis).toDS.selectExpr("explode(value) as f").selectExpr("to_json(f) payload")

    val exec = DMNExecution(dmnFiles, dmnModel.copy(resultProvider = "JSON"),
      scala.collection.immutable.Seq(DMNInputField("payload", "JSON", "testData")))

    val res = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec, debug = true))
    val strs = res.select("quality").as[String](TypedExpressionEncoder[String]).collect()
    strs shouldBe Array( """[{"decisionId":"_1B2DFBAA-DD62-4F1D-A375-38FB6A868A8C","decisionName":"evaluate","result":[true,false,false,false,false,false,false,false,false,false,true,false,true,false,false],"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_1B2DFBAA-DD62-4F1D-A375-38FB6A868A8C","decisionName":"evaluate","result":[false,true,false,false,false,false,false,false,false,false,false,false,false,false,false],"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_1B2DFBAA-DD62-4F1D-A375-38FB6A868A8C","decisionName":"evaluate","result":[false,false,false,false,false,false,false,false,false,false,false,false,false,true,false],"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_1B2DFBAA-DD62-4F1D-A375-38FB6A868A8C","decisionName":"evaluate","result":[false,false,false,false,false,false,false,true,false,false,false,false,false,false,false],"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_1B2DFBAA-DD62-4F1D-A375-38FB6A868A8C","decisionName":"evaluate","result":[false,false,false,false,false,false,false,false,false,false,false,false,false,false,false],"messages":[],"evaluationStatus":"SUCCEEDED"}]""")
  } }

  test("Write as json - debug - forced code gen") { evalCodeGens {
    import sparkSession.implicits._

    val ds = Seq(dataBasis).toDS.selectExpr("explode(value) as f").selectExpr("to_json(f) payload")

    val exec = DMNExecution(dmnFiles, dmnModel.copy(resultProvider = "JSON"),
      scala.collection.immutable.Seq(DMNInputField("payload", "JSON", "testData")))

    val res = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec, debug = true)).repartition(4)
    res.select("quality").write.mode(SaveMode.Overwrite).parquet(outputDir+"/json_debug")
    // compilation and writing is enough
  } }

  def testNulls(field: DMNInputField) = {
    import sparkSession.implicits._

    val ds = sparkSession.sql("select null temp").selectExpr("cast(temp as string) payload")

    val exec = DMNExecution(scala.collection.immutable.Seq(DMNFile("nulls.dmn",
      this.getClass.getClassLoader.getResourceAsStream("nulls.dmn").readAllBytes())),
        DMNModelService("nulls", "nulls", None, "JSON"),
      scala.collection.immutable.Seq(field))

    val res = ds.select(com.sparkutils.dmn.DMN.dmnEval(exec)).as[String](TypedExpressionEncoder[String]).collect()
    res
  }

  // The result should be constant folded to null, but keeps the contextPath so it's present in the dmn
  test("empty with default context null value") {
    val res = testNulls(DMNInputField("payload", "JSON", "inputData"))

    res.size shouldBe 1
    res.head shouldBe """{"evaluate":"wasNull"}"""
  }

  // The entire expression is constant folded to null, nothing to evaluate
  test("empty with null result") {
    val res = testNulls(DMNInputField("payload", "JSON", "inputData", false))

    res.size shouldBe 1
    res.head shouldBe """{"evaluate":null}"""
  }

  def testNullable(fields: scala.collection.immutable.Seq[DMNInputField]): Unit = evalCodeGens {
    import sparkSession.implicits._

    val ds = Seq(dataBasisNulls).toDS.selectExpr("explode(value) as f").selectExpr("f.*")

    val exec = DMNExecution(dmnFiles, dmnModel, fields)
    val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec, debug = true))
    val asSeqs = dres.select("quality.evaluate").as[Seq[Boolean]](TypedExpressionEncoder[Seq[Boolean]]).collect()
    val (without3, after2) = asSeqs.toSeq.splitAt(2)
    val three = after2.head
    three(13) shouldBe true
    three.count(_ == true) shouldBe 1
    (without3 ++ after2.tail).forall(_.forall(_ == false)) shouldBe true
  }

  test("nullable fields") {
    testNullable(
      scala.collection.immutable.Seq(
        DMNInputField("location", "", "testData.location"),
        DMNInputField("idPrefix", "", "testData.idPrefix"),
        DMNInputField("id", "", "testData.id"),
        DMNInputField("page", "", "testData.page"),
        DMNInputField("department", "", "testData.department")
      ))
  }

  test("nullable struct") {
    testNullable(
      scala.collection.immutable.Seq(
        DMNInputField("struct(*)", "", "testData")
      ))
  }

  val ons = "onetoone"

  val odmnFiles = scala.collection.immutable.Seq(
    DMNFile("onetoone.dmn",
      this.getClass.getClassLoader.getResourceAsStream("onetoone.dmn").readAllBytes()
    )
  )
  def odmnModel(resDDL: String) = DMNModelService(ons, ons, None, s"struct<evaluate: $resDDL>")

  def testOneToOne[A: TypedEncoder](data: A, fields: scala.collection.immutable.Seq[DMNInputField], resDDL: String = Others.ddl, extraPath: String = ".*"): Unit = evalCodeGens {
    implicit val spark = sparkSession
    val tds = TypedDataset.create(Seq(data)).dataset
    val ds = if (inCodegen) tds.repartition(4) else tds

    val exec = DMNExecution(odmnFiles, odmnModel(resDDL), fields)
    val dres = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
    val asSeqs = dres.select(s"quality.evaluate$extraPath").as[A](TypedExpressionEncoder[A]).collect()
    asSeqs.length shouldBe 1
    asSeqs.head shouldBe data
  }

  test("top fields others nulls - straight through") {
    testOneToOne(Others.nulls, Others.fields)
  }

  test("top fields others - straight through") {
    testOneToOne(Others.vals, Others.fields)
  }

  test("top struct others nulls - straight through") {
    testOneToOne(Others.nulls, Others.struct)
  }

  test("top struct others - straight through") {
    testOneToOne(Others.vals, Others.struct)
  }

  test("top field - straight through") {
    testOneToOne("field", scala.collection.immutable.Seq(DMNInputField("value","","inputData")), "string", "")
  }

  test("top field null - straight through") {
    testOneToOne(null: String, scala.collection.immutable.Seq(DMNInputField("value","","inputData")), "string", "")
  }

}
