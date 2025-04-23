package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.{DMNDecisionService, DMNExecution, DMNFile, DMNInputField, DMNModelService}
import org.apache.spark.sql.ShimUtils.{column, expression}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.col
import org.scalatest.{FunSuite, Matchers}

import scala.collection.immutable.Seq

case class TestData(location: String, idPrefix: String, id: Int, page: Long, department: String)

class SimpleTest extends FunSuite with Matchers {

  lazy val sparkSession = {
    val s = SparkSession.builder().config("spark.master", "local[*]").config("spark.ui.enabled", false).getOrCreate()
    s.sparkContext.setLogLevel("ERROR") // set to debug to get actual code lines etc.
    s
  }

  val ns = "decisions"

  val dmnFiles = Seq(
    DMNFile("common.dmn",
      this.getClass.getClassLoader.getResourceAsStream("common.dmn").readAllBytes()
    ),
    DMNFile("decisions.dmn",
      this.getClass.getClassLoader.getResourceAsStream("decisions.dmn").readAllBytes()
    )
  )
  val dmnModel = DMNModelService(ns, ns, Some("DQService"), "array<boolean>")
  val dataBasis = Seq(
    TestData("US", "a", 1, 1, "sales"),
    TestData("UK", "a", 1, 2, "marketing"),
    TestData("CH", "a", 1, 3, "hr"),
    TestData("MX", "a", 1, 4, "it"),
    TestData("BR", "a", 1, 5, "ops"),
  )
  def testResults(ds: DataFrame, exec: DMNExecution): Unit = {
    import sparkSession.implicits._

    val res = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
    val asSeqs = res.select("quality").as[Seq[Boolean]].collect()

    asSeqs.forall(_.size == 15) shouldBe true
    asSeqs(0).head shouldBe true
    asSeqs(0)(10) shouldBe true
    asSeqs(0)(12) shouldBe true
    asSeqs(0).count(identity) shouldBe 3
    asSeqs(1)(1) shouldBe true
    asSeqs(1).count(identity) shouldBe 1
  }

  def testJSONResults(service: DMNModelService): Unit = {
    import sparkSession.implicits._

    val ds = Seq(dataBasis).toDS.selectExpr("explode(value) as f").selectExpr("to_json(f) payload")

    val exec = DMNExecution(dmnFiles, service,
      Seq(DMNInputField("payload", "JSON", "testData")))
    testResults(ds, exec)
  }

  def testTopLevelFieldsResults(service: DMNModelService): Unit = {
    import sparkSession.implicits._

    val ds = Seq(dataBasis).toDS.selectExpr("explode(value) as f").selectExpr("f.*")

    val exec = DMNExecution(dmnFiles, service,
      Seq(DMNInputField("location", "String", "testData.location"),
        DMNInputField("idPrefix", "String", "testData.idPrefix"),
        DMNInputField("id", "Int", "testData.id"),
        DMNInputField("page", "Long", "testData.page"),
        DMNInputField("department", "String", "testData.department")
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
    testTopLevelFieldsResults(dmnModel)
  }

  test("Loading of Kogito and sample test should work - evaluate all top level fields") {
    testTopLevelFieldsResults(dmnModel.copy(service = None))
  }

  test("Write as json") {
    import sparkSession.implicits._

    val ds = Seq(dataBasis).toDS.selectExpr("explode(value) as f").selectExpr("to_json(f) payload")

    val exec = DMNExecution(dmnFiles, dmnModel.copy(resultProvider = "JSON"),
      Seq(DMNInputField("payload", "JSON", "testData")))

    val res = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(exec))
    val strs = res.select("quality").as[String].collect()
    strs shouldBe Array( """[{"decisionId":"_1B2DFBAA-DD62-4F1D-A375-38FB6A868A8C","decisionName":"evaluate","result":[true,false,false,false,false,false,false,false,false,false,true,false,true,false,false],"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_1B2DFBAA-DD62-4F1D-A375-38FB6A868A8C","decisionName":"evaluate","result":[false,true,false,false,false,false,false,false,false,false,false,false,false,false,false],"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_1B2DFBAA-DD62-4F1D-A375-38FB6A868A8C","decisionName":"evaluate","result":[false,false,false,false,false,false,false,false,false,false,false,false,false,true,false],"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_1B2DFBAA-DD62-4F1D-A375-38FB6A868A8C","decisionName":"evaluate","result":[false,false,false,false,false,false,false,true,false,false,false,false,false,false,false],"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_1B2DFBAA-DD62-4F1D-A375-38FB6A868A8C","decisionName":"evaluate","result":[false,false,false,false,false,false,false,false,false,false,false,false,false,false,false],"messages":[],"evaluationStatus":"SUCCEEDED"}]""")
  }
}
