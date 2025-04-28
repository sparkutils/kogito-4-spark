package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.kogito.types.ResultInterfaces
import com.sparkutils.dmn.kogito.types.ResultInterfaces.{FAILED, NOT_EVALUATED, NOT_FOUND, SKIPPED_ERROR, SKIPPED_WARN, SUCCEEDED, evalStatusEnding}
import com.sparkutils.dmn.{DMN, DMNConfiguration, DMNDecisionService, DMNExecution, DMNFile, DMNInputField, DMNModelService}
import org.apache.spark.sql.ShimUtils.{column, expression}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.col
import org.scalatest.{FunSuite, Matchers}

import scala.collection.immutable.Seq


case class AllTest(badInputAndOutput: String, badInputAndOutput_dmnEvalStatus: Byte,
                   outstring: String, outstring_dmnEvalStatus: Byte,
                   missingExpr: String, missingExpr_dmnEvalStatus: Byte,
                   wrongOutputType: String, wrongOutputType_dmnEvalStatus: Byte,
                   badExpr: String, badExpr_dmnEvalStatus: Byte
                  )
case class DifferentTest(aBadInputAndOutput: String, aBadInputAndOutput_dmnEvalStatus: Byte,
                   outstring: String, outstring_dmnEvalStatus: Byte,
                   missingExpr: String, missingExpr_dmnEvalStatus: Byte,
                   wrongOutputType: String, wrongOutputType_dmnEvalStatus: Byte,
                   badExpr: String, badExpr_dmnEvalStatus: Byte
                  )
case class MissingInStruct(outstring: String, outstring_dmnEvalStatus: Byte,
                   missingExpr: String, missingExpr_dmnEvalStatus: Byte,
                   wrongOutputType: String, wrongOutputType_dmnEvalStatus: Byte,
                   badExpr: String, badExpr_dmnEvalStatus: Byte
                  )
class EvalAllResultsTest extends FunSuite with Matchers {

  lazy val sparkSession = {
    val s = SparkSession.builder().config("spark.master", "local[*]").config("spark.ui.enabled", false).getOrCreate()
    s.sparkContext.setLogLevel("ERROR") // set to debug to get actual code lines etc.
    s
  }

  val ns = "https://kie.org/dmn/_1C1F4E1D-5F6F-4EA0-8C06-32F8A67C4D98"
  val name = "DMN_774D7D3A-E45E-4623-918B-AAE7ADBE6252"

  val dmnFiles = Seq(
    DMNFile("lots_of_decisions.dmn",
      this.getClass.getClassLoader.getResourceAsStream("lots_of_decisions.dmn").readAllBytes()
    )
  )
  val dmnModel = DMNModelService(name, ns, None, s"""struct<
    badInputAndOutput: String, badInputAndOutput$evalStatusEnding: Byte,
    outstring: String, outstring$evalStatusEnding: Byte,
    missingExpr: String, missingExpr$evalStatusEnding: Byte,
    wrongOutputType: String, wrongOutputType$evalStatusEnding: Byte,
    badExpr: String, badExpr$evalStatusEnding: Byte
    >
  """)
  val data = Seq(
    "a"
  )

  test("Lots of decisions with different statuses") {
    import sparkSession.implicits._

    val ds = data.toDS
    val res = ds.withColumn("quality", DMN.dmnEval(DMNExecution(dmnFiles = dmnFiles, model = dmnModel,
      contextProviders = Seq(DMNInputField("value", "String", "inString")
      ), configuration = Empty.configuration)))
    val asSeqs = res.select("quality.*").as[AllTest].collect()

    asSeqs.size shouldBe 1
    asSeqs.head shouldBe AllTest(null, SKIPPED_ERROR,
      "a", SUCCEEDED,
      null, SKIPPED_WARN,
      "a", SUCCEEDED,
      null, FAILED
    )
  }

  test("Missing decisions in struct") {
    import sparkSession.implicits._

    val ds = data.toDS
    val res = ds.withColumn("quality", DMN.dmnEval(DMNExecution(dmnFiles = dmnFiles,
      model = dmnModel.copy(resultProvider = dmnModel.resultProvider.replace(s"badInputAndOutput: String, badInputAndOutput$evalStatusEnding: Byte,","")),
      contextProviders = Seq(DMNInputField("value", "String", "inString")
      ), configuration = Empty.configuration)))
    val asSeqs = res.select("quality.*").as[MissingInStruct].collect()

    asSeqs.size shouldBe 1
    asSeqs.head shouldBe MissingInStruct(
      "a", SUCCEEDED,
      null, SKIPPED_WARN,
      "a", SUCCEEDED,
      null, FAILED
    )
  }

  test("Extra decisions in struct") {
    import sparkSession.implicits._

    val ds = data.toDS
    val res = ds.withColumn("quality", DMN.dmnEval(DMNExecution(dmnFiles = dmnFiles,
      model = dmnModel.copy(resultProvider = dmnModel.resultProvider.replace(s"badInputAndOutput: String, badInputAndOutput$evalStatusEnding: Byte,",
        s"aBadInputAndOutput: String, aBadInputAndOutput$evalStatusEnding: Byte,")),
      contextProviders = Seq(DMNInputField("value", "String", "inString")
      ), configuration = Empty.configuration)))
    val asSeqs = res.select("quality.*").as[DifferentTest].collect()

    asSeqs.size shouldBe 1
    asSeqs.head shouldBe DifferentTest(
      null, NOT_FOUND,
      "a", SUCCEEDED,
      null, SKIPPED_WARN,
      "a", SUCCEEDED,
      null, FAILED
    )
  }

}
