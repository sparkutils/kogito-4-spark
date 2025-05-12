package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.kogito.types.ResultInterfaces.{FAILED, NOT_FOUND, SKIPPED_ERROR, SKIPPED_WARN, SUCCEEDED, evalStatusEnding}
import com.sparkutils.dmn.{DMN, DMNExecution, DMNFile, DMNInputField, DMNModelService}
import org.apache.spark.sql.{Row, SaveMode, SparkSession}
import org.junit.runner.RunWith
import org.scalatest.{FunSuite, Matchers}
import org.scalatestplus.junit.JUnitRunner

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

@RunWith(classOf[JUnitRunner])
class EvalAllResultsTest extends FunSuite with Matchers with TestUtils {

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

  test("Lots of decisions with different statuses") { evalCodeGens {
    import sparkSession.implicits._

    val ds = data.toDS
    val res = ds.withColumn("quality", DMN.dmnEval(DMNExecution(dmnFiles = dmnFiles, model = dmnModel,
      contextProviders = Seq(DMNInputField("value", "String", "inString")
      ))))
    val asSeqs = res.select("quality.*").as[AllTest].collect()

    asSeqs.size shouldBe 1
    asSeqs.head shouldBe AllTest(null, SKIPPED_ERROR,
      "a", SUCCEEDED,
      null, SKIPPED_WARN,
      "a", SUCCEEDED,
      null, FAILED
    )
  }}

  test("Lots of decisions with different statuses - debug") { evalCodeGens {
    import sparkSession.implicits._
    // only to verify debug mode is working in this scenario (null handling etc.)
    val ds = data.toDS
    val res = ds.withColumn("quality", DMN.dmnEval(DMNExecution(dmnFiles = dmnFiles, model = dmnModel,
      contextProviders = Seq(DMNInputField("value", "String", "inString")
      )), debug = true))
    val asSeqs = res.select("quality.*").collect()
    asSeqs.length shouldBe 1
  }}

  test("Missing decisions in struct") { evalCodeGens {
    import sparkSession.implicits._

    val ds = data.toDS
    val res = ds.withColumn("quality", DMN.dmnEval(DMNExecution(dmnFiles = dmnFiles,
      model = dmnModel.copy(resultProvider = dmnModel.resultProvider.replace(s"badInputAndOutput: String, badInputAndOutput$evalStatusEnding: Byte,","")),
      contextProviders = Seq(DMNInputField("value", "String", "inString")
      ))))
    val asSeqs = res.select("quality.*").as[MissingInStruct].collect()

    asSeqs.size shouldBe 1
    asSeqs.head shouldBe MissingInStruct(
      "a", SUCCEEDED,
      null, SKIPPED_WARN,
      "a", SUCCEEDED,
      null, FAILED
    )
  }}

  test("Extra decisions in struct") { evalCodeGens {
    import sparkSession.implicits._

    val ds = data.toDS
    val res = ds.withColumn("quality", DMN.dmnEval(DMNExecution(dmnFiles = dmnFiles,
      model = dmnModel.copy(resultProvider = dmnModel.resultProvider.replace(s"badInputAndOutput: String, badInputAndOutput$evalStatusEnding: Byte,",
        s"aBadInputAndOutput: String, aBadInputAndOutput$evalStatusEnding: Byte,")),
      contextProviders = Seq(DMNInputField("value", "String", "inString")
      ))))
    val asSeqs = res.select("quality.*").as[DifferentTest].collect()

    asSeqs.size shouldBe 1
    asSeqs.head shouldBe DifferentTest(
      null, NOT_FOUND,
      "a", SUCCEEDED,
      null, SKIPPED_WARN,
      "a", SUCCEEDED,
      null, FAILED
    )
  }}

  test("Lots of decisions with different statuses - json out - for compilation tests only") { evalCodeGens {
    import sparkSession.implicits._

    val ds = (1 to 1000).map("a"+_).toDS.repartition(4)
    val res = ds.withColumn("quality", DMN.dmnEval(DMNExecution(dmnFiles = dmnFiles, model = dmnModel.copy(resultProvider = "JSON"),
      contextProviders = Seq(DMNInputField("value", "String", "inString")
      ))))
    res.write.mode(SaveMode.Overwrite).parquet(outputDir+"/jsonOut")

  }}

  /*
  TODO some form of issue on serialization with json here it blows the stack, probably on exception causes
  test("Lots of decisions with different statuses - json out - debug - for compilation tests only") { evalCodeGens {
    import sparkSession.implicits._

    val ds = (1 to 1000).map("a"+_).toDS.repartition(4)
    val res = ds.withColumn("quality", DMN.dmnEval(DMNExecution(dmnFiles = dmnFiles, model = dmnModel.copy(resultProvider = "JSON"),
      contextProviders = Seq(DMNInputField("value", "String", "inString")
      )), debug = true))
    res.write.mode(SaveMode.Overwrite).parquet(outputDir+"/jsonOut")

  }}*/

}
