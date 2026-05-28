package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.{DMNConfiguration, DMNExecution, DMNFile, DMNInputField, DMNModelService}
import frameless.TypedEncoder
import org.junit.runner.RunWith
import org.scalatest.{FunSuite, Matchers}
import org.scalatestplus.junit.JUnitRunner
import frameless._

case class LookupRow[T](key: Int, value: T)

@RunWith(classOf[JUnitRunner])
class StaticDataTest extends FunSuite with Matchers with TestUtils {

  val mapData = Seq(
    LookupRow(1,"1val"),
    LookupRow(2,"2val"),
    LookupRow(3,"3val"),
    LookupRow(4,"4val"),
    LookupRow(5,"5val"),
    LookupRow(6,"6val")
  )

  val rowData = Seq(
    1,2,3,4,5,6
  )

  val ns = "decisions"

  val dmnFiles =  scala.collection.immutable.Seq(
    DMNFile("lookups.dmn",
      this.getClass.getClassLoader.getResourceAsStream("lookups.dmn").readAllBytes()
    )
  )

  def testResults[A: TypedEncoder, R: TypedEncoder](rowData: Seq[A],
                                                          modelService: DMNModelService, respField: String,
                                                          inputFields: Seq[DMNInputField]): Seq[R] = {
    import sparkSession.implicits._

    implicit val aenc = {
      frameless.TypedExpressionEncoder[A]
    }

    val ds =
      if (inCodegen)
        rowData.toDS().repartition(4) // requires using sorted in tests but needed to force compilation
      else
        rowData.toDS()

    val config = DMNConfiguration.empty

    val res = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(
      DMNExecution(dmnFiles = scala.collection.immutable.Seq() ++ dmnFiles, model = modelService,
        contextProviders = inputFields.toVector,
        configuration = config
      )))

    import frameless._
    val asSeqs = res.selectExpr(s"quality.$respField.*").as[R](TypedExpressionEncoder[R]).collect()
    asSeqs.toVector
  }

  lazy val md: Unit = {
    import sparkSession.implicits._
    mapData.toDS().createOrReplaceTempView("baseData")
  }

  val lookupModel = DMNModelService(ns, ns, Some("LUService"), "struct<luEvaluate: struct<key: decimal, value: string>>")

  // note this really should be a correlated subquery but the example is used to prove the approach
  test("Maps work") { evalCodeGens {
    md

    val r = testResults[Int, (BigDecimal, String)](rowData.map(_ * 2), lookupModel, "luEvaluate", Seq(
      DMNInputField("(select map_from_entries( collect_list(struct(string(key), value)) ) from baseData)","","mapConfig"),
      DMNInputField("value","","input")
    ))

    r.map(p => LookupRow(p._1.toInt, p._2)).sortBy(_.key) shouldBe mapData
  } }

  val existsModel = DMNModelService(ns, ns, Some("ExistsService"), "struct<existsEvaluate: struct<key: decimal, value: boolean>>")

  test("Lists work") { evalCodeGens {
    md

    val r = testResults[Int, (BigDecimal, Boolean)](rowData.map(_ + 1), existsModel, "existsEvaluate", Seq(
      DMNInputField("(select collect_set(key) from baseData)","","listConfig"),
      DMNInputField("value","","input")
    ))

    r.map(p => LookupRow(p._1.toInt - 1, p._2)).sortBy(_.key) shouldBe Seq(
      LookupRow(1, true),
      LookupRow(2, true),
      LookupRow(3, true),
      LookupRow(4, true),
      LookupRow(5, true),
      LookupRow(6, false),
    )
  } }
}
