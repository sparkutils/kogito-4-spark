package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.{DMNDecisionService, DMNFile, DMNModelService}
import org.apache.spark.sql.ShimUtils.{column, expression}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.col
import org.scalatest.{FunSuite, Matchers}

import scala.collection.immutable.Seq

case class TestData(location: String, idPrefix: String, id: Int, page: Long, department: String)

class SimpleTest extends FunSuite with Matchers {

  lazy val sparkSession = SparkSession.builder().config("spark.master", "local[*]").getOrCreate()

  val ns = "decisions"

  val dmnFiles = Seq(
    DMNFile("common.dmn",
      this.getClass.getClassLoader.getResourceAsStream("common.dmn").readAllBytes()
    ),
    DMNFile("decisions.dmn",
      this.getClass.getClassLoader.getResourceAsStream("decisions.dmn").readAllBytes()
    )
  )
  val dmnModel = DMNModelService(ns, ns, Some("DQService"))
  val dataBasis = Seq(
    TestData("US", "a", 1, 1, "sales"),
    TestData("UK", "a", 1, 2, "marketing"),
    TestData("CH", "a", 1, 3, "hr"),
    TestData("MX", "a", 1, 4, "it"),
    TestData("BR", "a", 1, 5, "ops"),
  )

  def testResults(service: DMNModelService): Unit = {
    import sparkSession.implicits._

    val ds = Seq(dataBasis).toDS.selectExpr("explode(value) as f").selectExpr("f.*","to_json(f) payload")

    val res = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmn(KogitoDMNRepository, dmnFiles, service,
      Seq(column(KogitoJSONContext(KogitoDMNContextPath("testData"), expression(col("payload"))))), KogitoSeqOfBools()))
    val asSeqs = res.select("quality").as[Seq[Boolean]].collect()

    asSeqs.forall(_.size == 15) shouldBe true
    asSeqs(0).head shouldBe true
    asSeqs(0)(10) shouldBe true
    asSeqs(0)(12) shouldBe true
    asSeqs(0).count(identity) shouldBe 3
    asSeqs(1)(1) shouldBe true
    asSeqs(1).count(identity) shouldBe 1
  }

  test("Loading of Kogito and sample test should work - decision service") {
    testResults(dmnModel)
  }

  test("Loading of Kogito and sample test should work - evaluate all") {
    testResults(dmnModel.copy(service = None))
  }
}
