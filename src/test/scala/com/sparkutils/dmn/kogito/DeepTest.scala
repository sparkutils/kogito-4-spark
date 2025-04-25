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

  val deep_struct = Seq(
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
  def testResults[A: RecordFieldEncoder,B: RecordFieldEncoder,R: Encoder](maps: Seq[Map[A,B]], mapType: String, outputProvider: String, dmnFiles: Seq[DMNFile]): Seq[R] = {
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

  test("Deep test JSON 1:1 Reply - String, String context") {
    import sparkSession.implicits._

    val res = testResults[String, String, String]( (1 to 5). map( i => Map(s"a$i" -> s"b$i") ), "<String, String>", "JSON", deep_struct)
    res shouldBe Seq(
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a1":"b1"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a2":"b2"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a3":"b3"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a4":"b4"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a5":"b5"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]"""
    )
  }

  test("Deep test JSON 1:1 Reply - String, Boolean context") {
    import sparkSession.implicits._

    val res = testResults[String, Boolean, String]( (1 to 5). map( i => Map(s"a$i" -> true, s"b$i" -> false, s"c$i" -> true) ), "<String, Boolean>", "JSON", deep_struct)
    res shouldBe Seq(
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a1":true,"b1":false,"c1":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a2":true,"b2":false,"c2":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a3":true,"b3":false,"c3":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a4":true,"b4":false,"c4":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a5":true,"b5":false,"c5":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]"""
    )
  }

  test("Deep test JSON 1:1 Reply - String, Pair context") {
    import sparkSession.implicits._

    val res = testResults[String, Pair, String]( (1 to 5). map( i => Map(s"a$i" -> Pair(true, false), s"b$i" -> Pair(false, true), s"c$i" -> Pair(true,false)) ), "<String, struct<a: boolean, b: boolean>>", "JSON", deep_struct)
    res shouldBe Seq(
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a1":{"a":true,"b":false},"b1":{"a":false,"b":true},"c1":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a2":{"a":true,"b":false},"b2":{"a":false,"b":true},"c2":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a3":{"a":true,"b":false},"b3":{"a":false,"b":true},"c3":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a4":{"a":true,"b":false},"b4":{"a":false,"b":true},"c4":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      """[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a5":{"a":true,"b":false},"b5":{"a":false,"b":true},"c5":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]"""
    )
  }
}
