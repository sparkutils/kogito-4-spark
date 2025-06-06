package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.kogito.types.Utils.optEqual
import com.sparkutils.dmn.{DMNConfiguration, DMNExecution, DMNFile, DMNInputField, DMNModelService}
import frameless.{RecordFieldEncoder, TypedEncoder, TypedExpressionEncoder}
import org.apache.spark.sql.Encoder
import org.junit.runner.RunWith
import org.scalatest.{FunSuite, Matchers}
import org.scalatestplus.junit.JUnitRunner
import frameless._

import java.time.temporal.ChronoUnit
import java.time.{LocalDate, LocalDateTime, ZoneOffset}

case class Pair(a: Boolean, b: Boolean) extends Serializable
case class Deep[A,B](a: String, b: Option[java.math.BigDecimal], d: Pair, c: Map[A,B]) extends Serializable {
  override def equals(obj: Any): Boolean = obj match {
    // precision isn't correct in frameless encoding
    case o: Deep[A,B] => a == o.a /* && b == o.b */ && d == o.d && c == o.c
    case _ => false
  }
}
case class Top[A,B](top1: String, strings: Seq[String], structs: Seq[Deep[A, B]]) extends Serializable

case class Wrapper[A,B](top: Top[A,B]) extends Serializable

case class Result[A,B](eval: Top[A,B]) extends Serializable

case class Quality[A,B](quality: Result[A,B]) extends Serializable

case class DebugResult[A,B](eval: Top[A,B], dmnDebugMode: Seq[KogitoResult], messages: Seq[KogitoMessage]) extends Serializable

case class DebugQuality[A,B](quality: DebugResult[A,B]) extends Serializable

case class Others(s: Option[String], l: Option[Long], b: Option[Boolean], d: Option[Double],
                  f: Option[Float], by: Option[Byte], bytes: Option[Array[Byte]],
                  sh: Option[Short], date: Option[LocalDate], dateTime: Option[LocalDateTime],
                  m: Option[Map[Int, Int]], ar: Option[Seq[Int]], bd: Option[java.math.BigDecimal]
                 ) extends Serializable {
  override def equals(obj: Any): Boolean = obj match {
    // precision isn't correct in frameless encoding
    case o: Others =>
      s == o.s && l == o.l && b == o.b && d == o.d && f == o.f && by == o.by &&
        sh == o.sh && date == o.date &&
        optEqual(dateTime, o.dateTime)(_.truncatedTo(ChronoUnit.MICROS) == _.truncatedTo(ChronoUnit.MICROS)) &&
        optEqual(bytes, o.bytes)(_ sameElements _) && m == o.m && ar == o.ar
    case _ => false
  }
}

@RunWith(classOf[JUnitRunner])
class DeepTest extends FunSuite with Matchers with TestUtils {

  val oneDotZero = "1.000000000000000000"

  val ns = "deepns"

  val deep_struct = Seq(
    DMNFile("deep_structs_and_arrays.dmn",
      this.getClass.getClassLoader.getResourceAsStream("deep_structs_and_arrays.dmn").readAllBytes()
    )
  )

  def deepType(mapType: String) =
    s"""struct<
         a: string, b: decimal(10,1), d: struct<
         a: boolean, b: boolean
        >, c: map$mapType
       >"""

  def theType(mapType: String) =
    s"""
    struct<top1: String, strings: array<string>, structs: array<
        ${deepType(mapType)}
      >
    >
    """

  def dmnModel(outputProvider: String) =
    DMNModelService("deep_fun", ns, None, outputProvider)

  def dataBasis[A,B](maps: Seq[Map[A,B]]): Seq[Wrapper[A, B]] = maps.zipWithIndex.map{ case (m, i) =>
    Wrapper(
      Top(i.toString, Seq("a","b","c","d").map(_+i.toString), Seq(Deep(i.toString, Others.bd, Pair(true, true), m)))
  )}

  def testResults[A: RecordFieldEncoder, B: RecordFieldEncoder, R: TypedEncoder](maps: Seq[Map[A,B]], mapType: String, outputProvider: String, dmnFiles: Seq[DMNFile], deriveContextTypes: Boolean = false, debug: Boolean = false, useTreeMap: Boolean = false, fullProxyDS: Boolean = true): Seq[R] = {
    import sparkSession.implicits._

    implicit val enc = {
      frameless.TypedExpressionEncoder[Wrapper[A, B]]
    }

    val ds =
      if (inCodegen)
        dataBasis(maps).toDS().repartition(4) // requires using sorted in tests but needed to force compilation
      else
        dataBasis(maps).toDS()

    val config = (useTreeMap, fullProxyDS) match {
      case (true, true) => DMNConfiguration(options = "useTreeMap=true")
      case (true, false) => DMNConfiguration(options = "useTreeMap=true;fullProxyDS=false")
      case (_, false) => DMNConfiguration(options = "fullProxyDS=false")
      case _ => DMNConfiguration.empty
    }

    val res = ds.withColumn("quality", com.sparkutils.dmn.DMN.dmnEval(
      DMNExecution(dmnFiles = scala.collection.immutable.Seq() ++ dmnFiles, model = dmnModel(outputProvider), contextProviders =
        scala.collection.immutable.Seq() ++ Seq(DMNInputField("top", if (deriveContextTypes) "" else theType(mapType), "input")),
        configuration = config
      )
    , debug = debug))

    import frameless._
    val asSeqs = res.select("quality").as[R](TypedExpressionEncoder[R]).collect()
    asSeqs.toVector
  }

  test("Deep test JSON 1:1 Reply - String, String context") { evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, String, String]( (1 to 5). map( i => Map(s"a$i" -> s"b$i") ),
      "<String, String>", "JSON", deep_struct, useTreeMap = true)
    res.sorted shouldBe Seq(
      s"""{"eval":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":"b1"}}]}}""",
      s"""{"eval":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":"b2"}}]}}""",
      s"""{"eval":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":"b3"}}]}}""",
      s"""{"eval":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":"b4"}}]}}""",
      s"""{"eval":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":"b5"}}]}}"""
    )
  } }

  test("Deep test JSON 1:1 Reply - String, String context - null entries") { evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, String, String]( (1 to 5). map( i => Map(s"a$i" -> null) ),
      "<String, String>", "JSON", deep_struct, useTreeMap = true)
    res.sorted shouldBe Seq(
      s"""{"eval":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":null}}]}}""",
      s"""{"eval":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":null}}]}}""",
      s"""{"eval":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":null}}]}}""",
      s"""{"eval":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":null}}]}}""",
      s"""{"eval":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":null}}]}}"""
    )
  } }

  test("Deep test JSON 1:1 Reply - String, String context - null maps") { evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, String, String]( (1 to 5). map( i => null ),
      "<String, String>", "JSON", deep_struct, useTreeMap = true)
    res.sorted shouldBe Seq(
      s"""{"eval":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":null}]}}""",
      s"""{"eval":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":null}]}}""",
      s"""{"eval":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":null}]}}""",
      s"""{"eval":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":null}]}}""",
      s"""{"eval":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":null}]}}"""
    )
  } }

  test("Deep test JSON 1:1 Reply - String, String context - debug") { forceCodeGen { ////evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, String, String]( (1 to 5). map( i => Map(s"a$i" -> s"b$i") ),
      "<String, String>", "JSON", deep_struct, debug = true)
    res.sorted shouldBe Seq(
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":"b1"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":"b2"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":"b3"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":"b4"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":"b5"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]"""
    )
  }}

  test("Deep test JSON 1:1 Reply - String, Boolean context") {evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, Boolean, String]( (1 to 5). map( i => Map(s"a$i" -> true, s"b$i" -> false, s"c$i" -> true) ),
      "<String, Boolean>", "JSON", deep_struct, useTreeMap = true)
    res.sorted shouldBe Seq(
      s"""{"eval":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":true,"b1":false,"c1":true}}]}}""",
      s"""{"eval":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":true,"b2":false,"c2":true}}]}}""",
      s"""{"eval":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":true,"b3":false,"c3":true}}]}}""",
      s"""{"eval":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":true,"b4":false,"c4":true}}]}}""",
      s"""{"eval":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":true,"b5":false,"c5":true}}]}}"""
    )
  }}

  test("Deep test JSON 1:1 Reply - String, Boolean context - debug") {evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, Boolean, String]( (1 to 5). map( i => Map(s"a$i" -> true, s"b$i" -> false, s"c$i" -> true) ),
      "<String, Boolean>", "JSON", deep_struct, debug = true, useTreeMap = true)
    res.sorted shouldBe Seq(
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":true,"b1":false,"c1":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":true,"b2":false,"c2":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":true,"b3":false,"c3":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":true,"b4":false,"c4":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":true,"b5":false,"c5":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]"""
    )
  }}

  test("Deep test JSON 1:1 Reply - String, Pair context") {evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, Pair, String]( (1 to 5). map( i => Map(s"a$i" -> Pair(true, false),
      s"b$i" -> Pair(false, true), s"c$i" -> Pair(true,false)) ),
      "<String, struct<a: boolean, b: boolean>>", "JSON", deep_struct, useTreeMap = true)
    res.sorted shouldBe Seq(
      s"""{"eval":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":{"a":true,"b":false},"b1":{"a":false,"b":true},"c1":{"a":true,"b":false}}}]}}""",
      s"""{"eval":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":{"a":true,"b":false},"b2":{"a":false,"b":true},"c2":{"a":true,"b":false}}}]}}""",
      s"""{"eval":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":{"a":true,"b":false},"b3":{"a":false,"b":true},"c3":{"a":true,"b":false}}}]}}""",
      s"""{"eval":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":{"a":true,"b":false},"b4":{"a":false,"b":true},"c4":{"a":true,"b":false}}}]}}""",
      s"""{"eval":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":{"a":true,"b":false},"b5":{"a":false,"b":true},"c5":{"a":true,"b":false}}}]}}"""
    )
  }}

  test("Deep test JSON 1:1 Reply - String, Pair context - debug") {evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, Pair, String]( (1 to 5). map( i => Map(s"a$i" -> Pair(true, false),
      s"b$i" -> Pair(false, true), s"c$i" -> Pair(true,false)) ),
      "<String, struct<a: boolean, b: boolean>>", "JSON", deep_struct, debug = true, useTreeMap = true)
    res.sorted shouldBe Seq(
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":{"a":true,"b":false},"b1":{"a":false,"b":true},"c1":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":{"a":true,"b":false},"b2":{"a":false,"b":true},"c2":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":{"a":true,"b":false},"b3":{"a":false,"b":true},"c3":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":{"a":true,"b":false},"b4":{"a":false,"b":true},"c4":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":{"a":true,"b":false},"b5":{"a":false,"b":true},"c5":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]"""
    )
  }}

  test("Deep test JSON 1:1 Reply - String, String context - derive context types") { evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, String, String]( (1 to 5). map( i => Map(s"a$i" -> s"b$i") ),
      "<String, String>", "JSON", deep_struct, useTreeMap = true, deriveContextTypes = true)
    res.sorted shouldBe Seq(
      s"""{"eval":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":"b1"}}]}}""",
      s"""{"eval":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":"b2"}}]}}""",
      s"""{"eval":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":"b3"}}]}}""",
      s"""{"eval":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":"b4"}}]}}""",
      s"""{"eval":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":"b5"}}]}}"""
    )
  } }


  test("Deep test JSON 1:1 Reply - String, String context - debug - derive context types") { forceCodeGen { ////evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, String, String]( (1 to 5). map( i => Map(s"a$i" -> s"b$i") ),
      "<String, String>", "JSON", deep_struct, debug = true, deriveContextTypes = true)
    res.sorted shouldBe Seq(
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":"b1"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":"b2"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":"b3"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":"b4"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":"b5"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]"""
    )
  }}

  test("Deep test JSON 1:1 Reply - String, Boolean context - derive context types") {evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, Boolean, String]( (1 to 5). map( i => Map(s"a$i" -> true, s"b$i" -> false, s"c$i" -> true) ),
      "<String, Boolean>", "JSON", deep_struct, useTreeMap = true, deriveContextTypes = true)
    res.sorted shouldBe Seq(
      s"""{"eval":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":true,"b1":false,"c1":true}}]}}""",
      s"""{"eval":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":true,"b2":false,"c2":true}}]}}""",
      s"""{"eval":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":true,"b3":false,"c3":true}}]}}""",
      s"""{"eval":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":true,"b4":false,"c4":true}}]}}""",
      s"""{"eval":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":true,"b5":false,"c5":true}}]}}"""
    )
  }}

  test("Deep test JSON 1:1 Reply - String, Boolean context - debug - derive context types") {evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, Boolean, String]( (1 to 5). map( i => Map(s"a$i" -> true, s"b$i" -> false, s"c$i" -> true) ),
      "<String, Boolean>", "JSON", deep_struct, debug = true, useTreeMap = true, deriveContextTypes = true)
    res.sorted shouldBe Seq(
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":true,"b1":false,"c1":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":true,"b2":false,"c2":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":true,"b3":false,"c3":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":true,"b4":false,"c4":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":true,"b5":false,"c5":true}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]"""
    )
  }}

  test("Deep test JSON 1:1 Reply - String, Pair context - derive context types") {evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, Pair, String]( (1 to 5). map( i => Map(s"a$i" -> Pair(true, false),
      s"b$i" -> Pair(false, true), s"c$i" -> Pair(true,false)) ),
      "<String, struct<a: boolean, b: boolean>>", "JSON", deep_struct, useTreeMap = true, deriveContextTypes = true)
    res.sorted shouldBe Seq(
      s"""{"eval":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":{"a":true,"b":false},"b1":{"a":false,"b":true},"c1":{"a":true,"b":false}}}]}}""",
      s"""{"eval":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":{"a":true,"b":false},"b2":{"a":false,"b":true},"c2":{"a":true,"b":false}}}]}}""",
      s"""{"eval":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":{"a":true,"b":false},"b3":{"a":false,"b":true},"c3":{"a":true,"b":false}}}]}}""",
      s"""{"eval":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":{"a":true,"b":false},"b4":{"a":false,"b":true},"c4":{"a":true,"b":false}}}]}}""",
      s"""{"eval":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":{"a":true,"b":false},"b5":{"a":false,"b":true},"c5":{"a":true,"b":false}}}]}}"""
    )
  }}

  test("Deep test JSON 1:1 Reply - String, Pair context - debug - derive context types") {evalCodeGens {
    import sparkSession.implicits._

    val res = testResults[String, Pair, String]( (1 to 5). map( i => Map(s"a$i" -> Pair(true, false),
      s"b$i" -> Pair(false, true), s"c$i" -> Pair(true,false)) ),
      "<String, struct<a: boolean, b: boolean>>", "JSON", deep_struct, debug = true, useTreeMap = true, deriveContextTypes = true)
    res.sorted shouldBe Seq(
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a1":{"a":true,"b":false},"b1":{"a":false,"b":true},"c1":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"1a","strings":["a1i","b1i","c1i","d1i"],"structs":[{"a":"1","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a2":{"a":true,"b":false},"b2":{"a":false,"b":true},"c2":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"2a","strings":["a2i","b2i","c2i","d2i"],"structs":[{"a":"2","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a3":{"a":true,"b":false},"b3":{"a":false,"b":true},"c3":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"3a","strings":["a3i","b3i","c3i","d3i"],"structs":[{"a":"3","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a4":{"a":true,"b":false},"b4":{"a":false,"b":true},"c4":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]""",
      s"""[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"4a","strings":["a4i","b4i","c4i","d4i"],"structs":[{"a":"4","b":$oneDotZero,"d":{"a":true,"b":true},"c":{"a5":{"a":true,"b":false},"b5":{"a":false,"b":true},"c5":{"a":true,"b":false}}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]"""
    )
  }}

  implicit def qualityOrdering[A,B] = new Ordering[Quality[A,B]] {
    override def compare(x: Quality[A, B], y: Quality[A, B]): Int = x.quality.eval.top1.compare( y.quality.eval.top1)
  }

  def testStructs[A: RecordFieldEncoder,B: RecordFieldEncoder](extra: String, maps: Seq[Map[A, B]], deriveContextTypes: Boolean = false)(implicit enc: Encoder[Quality[A,B]], wenc: Encoder[Wrapper[A,B]]): Unit = evalCodeGens {
    val data = dataBasis(maps)
    val res = testResults[A, B, Quality[A, B]]( maps, extra, s"struct<eval: ${theType(extra)}>", deep_struct, deriveContextTypes = deriveContextTypes)
    res.sorted shouldBe data.map(t => Quality(Result(t.top.copy(top1 = t.top.top1 +"a", strings = t.top.strings.map(_+"i") ))))
  }
  implicit def debugQualityOrdering[A,B] = new Ordering[DebugQuality[A,B]] {
    override def compare(x: DebugQuality[A, B], y: DebugQuality[A, B]): Int = x.quality.eval.top1.compare( y.quality.eval.top1)
  }

  def testDebugStructs[A: RecordFieldEncoder, B: RecordFieldEncoder](extra: String, maps: Seq[Map[A, B]], deriveContextTypes: Boolean = false, fullProxyDS: Boolean = true): Unit = evalCodeGens {
    val data = dataBasis(maps)
    val res = testResults[A, B, DebugQuality[A, B]]( maps, extra, s"struct<eval: ${theType(extra)}>", deep_struct, debug = true, fullProxyDS = fullProxyDS, deriveContextTypes = deriveContextTypes)
    res.sorted shouldBe data.map(t => DebugQuality(DebugResult(t.top.copy(top1 = t.top.top1 +"a", strings = t.top.strings.map(_+"i") ), Seq(testDebug), List())))
  }

  val testDebug = KogitoResult("_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15", "eval", false, List(), "SUCCEEDED")

  test("Deep test struct 1:1 Reply - String, String context") {
    import sparkSession.implicits._

    val extra = "<String, String>"
    val maps = (1 to 5).map(i => Map(s"a$i" -> s"b$i"))
    testStructs(extra, maps)
  }

  test("Deep test struct 1:1 Reply - String, String context - debug") {
    //import sparkSession.implicits._

    import frameless._
    val extra = "<String, String>"
    val maps = (1 to 5).map(i => Map(s"a$i" -> s"b$i"))
    testDebugStructs(extra, maps)
  }

  test("Deep test struct 1:1 Reply - String, Boolean context") {
    import sparkSession.implicits._

    testStructs("<String, Boolean>", (1 to 5). map( i => Map(s"a$i" -> true, s"b$i" -> false, s"c$i" -> true) ))
  }

  test("Deep test struct 1:1 Reply - String, Boolean context - debug") {
    import sparkSession.implicits._

    testDebugStructs("<String, Boolean>", (1 to 5). map( i => Map(s"a$i" -> true, s"b$i" -> false, s"c$i" -> true) ))
  }

  test("Deep test struct 1:1 Reply - String, Pair context") {
    import sparkSession.implicits._

    testStructs("<String, struct<a: boolean, b: boolean>>",  (1 to 5). map( i => Map(s"a$i" -> Pair(true, false), s"b$i" -> Pair(false, true), s"c$i" -> Pair(true,false)) ))
  }

  test("Deep test struct 1:1 Reply - String, Pair context - debug") {
    import sparkSession.implicits._

    testDebugStructs("<String, struct<a: boolean, b: boolean>>",  (1 to 5). map( i => Map(s"a$i" -> Pair(true, false), s"b$i" -> Pair(false, true), s"c$i" -> Pair(true,false)) ),
      fullProxyDS = false)
  }

  test("Deep test struct 1:1 Reply - String, String context - derive context types") {
    import sparkSession.implicits._

    val extra = "<String, String>"
    val maps = (1 to 5).map(i => Map(s"a$i" -> s"b$i"))
    testStructs(extra, maps, deriveContextTypes = true)
  }

  test("Deep test struct 1:1 Reply - String, String context - debug - derive context types") {
    //import sparkSession.implicits._

    import frameless._
    val extra = "<String, String>"
    val maps = (1 to 5).map(i => Map(s"a$i" -> s"b$i"))
    testDebugStructs(extra, maps, deriveContextTypes = true)
  }

  test("Deep test struct 1:1 Reply - String, Boolean context - derive context types") {
    import sparkSession.implicits._

    testStructs("<String, Boolean>", (1 to 5). map( i => Map(s"a$i" -> true, s"b$i" -> false, s"c$i" -> true) ), deriveContextTypes = true)
  }

  test("Deep test struct 1:1 Reply - String, Boolean context - debug - derive context types") {
    import sparkSession.implicits._

    testDebugStructs("<String, Boolean>", (1 to 5). map( i => Map(s"a$i" -> true, s"b$i" -> false, s"c$i" -> true) ), deriveContextTypes = true)
  }

  test("Deep test struct 1:1 Reply - String, Pair context - derive context types") {
    import sparkSession.implicits._

    testStructs("<String, struct<a: boolean, b: boolean>>",  (1 to 5). map( i => Map(s"a$i" -> Pair(true, false),
      s"b$i" -> Pair(false, true), s"c$i" -> Pair(true,false)) ), deriveContextTypes = true)
  }

  test("Deep test struct 1:1 Reply - String, Pair context - debug - derive context types") {
    import sparkSession.implicits._

    testDebugStructs("<String, struct<a: boolean, b: boolean>>",  (1 to 5). map( i => Map(
      s"a$i" -> Pair(true, false), s"b$i" -> Pair(false, true), s"c$i" -> Pair(true,false)) ),
      fullProxyDS = false, deriveContextTypes = true)
  }

  test("Deep test struct 1:1 Reply - String, Pair context - debug - derive context types - deep deep with nulls") {
    import sparkSession.implicits._

    testDebugStructs(s"<String, ${deepType("<int, int>")}>",  (1 to 5). map( i => Map(
      s"a$i" -> Deep(i.toString, null, Pair(true, true), Map(1 -> 2)) )),
      fullProxyDS = false, deriveContextTypes = true)
  }

  test("Deep test struct 1:1 Reply - String, Pair context - debug - derive context types - deep deep with nulls all other types") {
    import sparkSession.implicits._

    implicit val oenc = TypedEncoder[Others]

    testDebugStructs(s"<String, ${Others.ddl}>",  (1 to 5). map( i => Map(
      s"a$i" -> Others.nulls,
      s"b$i" -> Others.vals,
    )),
      fullProxyDS = false, deriveContextTypes = true)
  }

}

object Others {
  val ddl = s"struct<s: String, l: Long, b: Boolean, d: Double, f: Float, " +
    s"by: Byte, bytes: Binary, sh: Short, date: Date, dateTime: timestamp, " +
    s"m: Map<int,int>, ar: array<int>, bd: decimal(10,1)>"

  val fields = scala.collection.immutable.Seq(
    DMNInputField("s", "", "inputData.s"),
    DMNInputField("l", "", "inputData.l"),
    DMNInputField("b", "", "inputData.b"),
    DMNInputField("d", "", "inputData.d"),
    DMNInputField("f", "", "inputData.f"),
    DMNInputField("by", "", "inputData.by"),
    DMNInputField("bytes", "", "inputData.bytes"),
    DMNInputField("sh", "", "inputData.sh"),
    DMNInputField("date", "", "inputData.date"),
    DMNInputField("dateTime", "", "inputData.dateTime"),
    DMNInputField("m", "", "inputData.m"),
    DMNInputField("ar", "", "inputData.ar"),
    DMNInputField("bd", "", "inputData.bd"),
  )

  val struct = scala.collection.immutable.Seq(
    DMNInputField("struct(*)", "", "inputData"),
  )

  val date = LocalDate.now()
  val dateTime = LocalDateTime.now(ZoneOffset.UTC)

  val bd = Some(java.math.BigDecimal.valueOf(1.0))

  val nulls = Others(None,None,None,None,None,None,None,None,None,None,None,None,None)
  val vals = Others(Some(""),Some(1l),Some(true),Some(0.2),Some(0.2f),Some(0),
    Some(Array(0: Byte)),Some(1),Some(date), Some(dateTime), Some(Map(1 -> 1)), Some(Array(1,2)),
    bd)
}