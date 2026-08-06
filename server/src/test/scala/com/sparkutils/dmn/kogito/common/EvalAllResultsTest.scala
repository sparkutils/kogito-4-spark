package com.sparkutils.dmn.kogito.common

import com.sparkutils.dmn
import com.sparkutils.dmn.kogito.{Constants => C}
import org.apache.spark.sql.SaveMode

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

class EvalAllResultsTest extends SparkTests {

  val ns = "https://kie.org/dmn/_1C1F4E1D-5F6F-4EA0-8C06-32F8A67C4D98"
  val name = "DMN_774D7D3A-E45E-4623-918B-AAE7ADBE6252"

  val dmnFiles = Seq(
    dmn.DMNFile("lots_of_decisions.dmn",
      this.getClass.getClassLoader.getResourceAsStream("lots_of_decisions.dmn").readAllBytes()
    )
  )
  val dmnModel = dmn.DMNModelService(name, ns, None, s"""struct<
    badInputAndOutput: String, badInputAndOutput${C.evalStatusEnding}: Byte,
    outstring: String, outstring${C.evalStatusEnding}: Byte,
    missingExpr: String, missingExpr${C.evalStatusEnding}: Byte,
    wrongOutputType: String, wrongOutputType${C.evalStatusEnding}: Byte,
    badExpr: String, badExpr${C.evalStatusEnding}: Byte
    >
  """)
  val data = Seq(
    "a"
  )

  test("Lots of decisions with different statuses") { evalCodeGens {
      implicit val session = sparkSession
      import session.implicits._

        val ds = data.toDS
        val res = ds.withColumn("quality", dmn.DMN.dmnEval(dmn.DMNExecution(dmnFiles = dmnFiles, model = dmnModel,
          contextProviders = Seq(dmn.DMNInputField("value", "String", "inString")
          ))))
        val asSeqs = res.select("quality.*").as[AllTest].collect()

        asSeqs.size shouldBe 1
        asSeqs.head shouldBe AllTest(null, C.SKIPPED_ERROR,
          "a", C.SUCCEEDED,
          null, C.SKIPPED_WARN,
          "a", C.SUCCEEDED,
          null, C.FAILED
        )
      }
  }

  test("Lots of decisions with different statuses - debug") { evalCodeGens {
      implicit val session = sparkSession
      import session.implicits._
        // only to verify debug mode is working in this scenario (null handling etc.)
        val ds = data.toDS
        val res = ds.withColumn("quality", dmn.DMN.dmnEval(dmn.DMNExecution(dmnFiles = dmnFiles, model = dmnModel,
          contextProviders = Seq(dmn.DMNInputField("value", "String", "inString")
          )), debug = true))
        val asSeqs = res.select("quality.*").collect()
        asSeqs.length shouldBe 1
      }
  }

  test("Missing decisions in struct") { evalCodeGens {
      implicit val session = sparkSession
      import session.implicits._

    val ds = data.toDS
    val res = ds.withColumn("quality", dmn.DMN.dmnEval(dmn.DMNExecution(dmnFiles = dmnFiles,
      model = dmnModel.copy(resultProvider = dmnModel.resultProvider.replace(s"badInputAndOutput: String, badInputAndOutput${C.evalStatusEnding}: Byte,","")),
      contextProviders = Seq(dmn.DMNInputField("value", "String", "inString")
      ))))
    val asSeqs = res.select("quality.*").as[MissingInStruct].collect()

    asSeqs.size shouldBe 1
    asSeqs.head shouldBe MissingInStruct(
      "a", C.SUCCEEDED,
      null, C.SKIPPED_WARN,
      "a", C.SUCCEEDED,
      null, C.FAILED
    )
  }}

  test("Extra decisions in struct") { evalCodeGens {
    implicit val session = sparkSession
    import session.implicits._

    val ds = data.toDS
    val res = ds.withColumn("quality", dmn.DMN.dmnEval(dmn.DMNExecution(dmnFiles = dmnFiles,
      model = dmnModel.copy(resultProvider = dmnModel.resultProvider.replace(s"badInputAndOutput: String, badInputAndOutput${C.evalStatusEnding}: Byte,",
        s"aBadInputAndOutput: String, aBadInputAndOutput${C.evalStatusEnding}: Byte,")),
      contextProviders = Seq(dmn.DMNInputField("value", "String", "inString")
      ))))
    val asSeqs = res.select("quality.*").as[DifferentTest].collect()

    asSeqs.size shouldBe 1
    asSeqs.head shouldBe DifferentTest(
      null, C.NOT_FOUND,
      "a", C.SUCCEEDED,
      null, C.SKIPPED_WARN,
      "a", C.SUCCEEDED,
      null, C.FAILED
    )
  }}

  test("Lots of decisions with different statuses - json out - for compilation tests only") { evalCodeGens {
    implicit val session = sparkSession
    import session.implicits._

    val ds = (1 to 1000).map("a"+_).toDS.repartition(4)
    val res = ds.withColumn("quality", dmn.DMN.dmnEval(dmn.DMNExecution(dmnFiles = dmnFiles, model = dmnModel.copy(resultProvider = "JSON"),
      contextProviders = Seq(dmn.DMNInputField("value", "String", "inString")
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
