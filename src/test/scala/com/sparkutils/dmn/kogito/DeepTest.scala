package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.{DMNDecisionService, DMNExecution, DMNFile, DMNInputField, DMNModelService, DMNResultProvider}
import org.apache.spark.sql.ShimUtils.{column, expression}
import org.apache.spark.sql.{DataFrame, Encoder, SparkSession}
import org.apache.spark.sql.functions.col
import org.scalatest.{FunSuite, Matchers}

import scala.collection.immutable.Seq
import scala.reflect.ClassTag

import frameless._

case class Pair(a: Boolean, b: Boolean) extends Serializable
case class Deep[A,B](a: String, b: java.math.BigDecimal, d: Pair, c: Map[A,B]) extends Serializable
case class Top[A,B](top1: String, strings: Seq[String], structs: Seq[Deep[A, B]]) extends Serializable

case class Wrapper[A,B](top: Top[A,B]) extends Serializable

class DeepTest extends FunSuite with Matchers {

  lazy val sparkSession = {
    val s = SparkSession.builder().config("spark.master", "local[*]").config("spark.ui.enabled", false).getOrCreate()
    s.sparkContext.setLogLevel("ERROR") // set to debug to get actual code lines etc.
    s
  }

  val ns = "deepns"

  val dmnFiles = Seq(
    DMNFile("deep_structs_and_arrays.dmn",
      this.getClass.getClassLoader.getResourceAsStream("deep_structs_and_arrays.dmn").readAllBytes()
    )
  )

  def theType(mapType: String) =
    s"""
    struct<top1: String, strings: array<string>, structs: array<
        struct<
          a: string, b: decimal(10,2), d: struct<
           a: boolean, b: boolean
          >, c: map$mapType
        >
      >
    >
    """

  def dmnModel(mapType: String, outputProvider: String) =
    DMNModelService("deep_fun", ns, None, outputProvider)

  def dataBasis[A,B](maps: Seq[Map[A,B]]): Seq[Wrapper[A, B]] = maps.zipWithIndex.map{ case (m, i) =>
    Wrapper(
      Top(i.toString, Seq("a","b","c","d").map(_+i.toString), Seq(Deep(i.toString, java.math.BigDecimal.valueOf(1.0), Pair(true, true), m)))
  )}
  def testResults[A: RecordFieldEncoder,B: RecordFieldEncoder,R: Encoder](maps: Seq[Map[A,B]], mapType: String, outputProvider: String): Seq[R] = {
    import sparkSession.implicits._

    implicit val enc = {
      frameless.TypedExpressionEncoder[Wrapper[A, B]]
    }
    val ds = dataBasis(maps).toDS()

    val res = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(
      DMNExecution(dmnFiles = dmnFiles, model = dmnModel(mapType, outputProvider), contextProviders =
        Seq(DMNInputField("top", theType(mapType), "input"))
      )
    ))
    val asSeqs = res.select("quality").as[R].collect()
    asSeqs.toVector
  }

  test("Deep test JSON Reply - String, String context") {
    import sparkSession.implicits._

    val res = testResults[String, String, String]( (1 to 5). map( i => Map(s"a$i" -> s"b$i") ), "<String, String>", "JSON")
    res shouldBe Seq(
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"TOP1":"0","STRINGS":["a0","b0","c0","d0"],"STRUCTS":[{"A":"0","B":2061584302.16,"D":{"A":true,"B":true},"C":{"a1":"b1"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"TOP1":"1","STRINGS":["a1","b1","c1","d1"],"STRUCTS":[{"A":"1","B":2061584302.16,"D":{"A":true,"B":true},"C":{"a2":"b2"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"TOP1":"2","STRINGS":["a2","b2","c2","d2"],"STRUCTS":[{"A":"2","B":2061584302.16,"D":{"A":true,"B":true},"C":{"a3":"b3"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"TOP1":"3","STRINGS":["a3","b3","c3","d3"],"STRUCTS":[{"A":"3","B":2061584302.16,"D":{"A":true,"B":true},"C":{"a4":"b4"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"TOP1":"4","STRINGS":["a4","b4","c4","d4"],"STRUCTS":[{"A":"4","B":2061584302.16,"D":{"A":true,"B":true},"C":{"a5":"b5"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]"""
    )
  }
}
