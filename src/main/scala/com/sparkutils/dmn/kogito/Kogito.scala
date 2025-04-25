package com.sparkutils.dmn.kogito

import com.sparkutils.dmn._
import com.sparkutils.dmn.impl._
import com.sparkutils.dmn.kogito.Types.MAP
import com.sparkutils.dmn.kogito.types.ContextInterfaces
import org.apache.spark.sql.types.{BinaryType, BooleanType, ByteType, DataType, DateType, Decimal, DecimalType, DoubleType, FloatType, IntegerType, LongType, ShortType, StringType, StructType, TimestampType}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder
import org.kie.internal.io.ResourceFactory

import java.time.{LocalDate, LocalDateTime}
import java.util
import scala.collection.JavaConverters._
import scala.util.Try
import scala.collection.immutable.Seq

case class KogitoDMNResult(result: org.kie.dmn.api.core.DMNResult) extends DMNResult

case class KogitoDMNContextPath(path: String) extends DMNContextPath

/**
 * Represents an executable DMN Model
 */
case class KogitoDMNModel(model: org.kie.dmn.api.core.DMNModel, runtime: org.kie.dmn.api.core.DMNRuntime) extends DMNModel {

  def evaluateAll(ctx: DMNContext): DMNResult =
    KogitoDMNResult(runtime.evaluateAll(model, ctx.asInstanceOf[KogitoDMNContext].ctx))

  def evaluateDecisionService(ctx: DMNContext, service: String): DMNResult =
    KogitoDMNResult(runtime.evaluateDecisionService(model, ctx.asInstanceOf[KogitoDMNContext].ctx, service))

}

/**
 * Represents a repository of DMN, this is the actual root provider
 */
class KogitoDMNRepository() extends DMNRepository {
  /**
   * Throws DMNException if it can't be constructed
   * @param dmnFiles
   * @return
   */
  def dmnRuntimeFor(dmnFiles: Seq[DMNFile]): DMNRuntime = {

    val resources = dmnFiles.map{ f =>
      val r = ResourceFactory.newByteArrayResource(f.bytes)
      f.locationURI -> r.setSourcePath(f.locationURI)
    }.toMap

    KogitoDMNRuntime(
      DMNRuntimeBuilder.fromDefaults()
        .setRelativeImportResolver((_,_, locationURI) => resources(locationURI).getReader)
        .buildConfiguration()
        .fromResources(resources.values.asJavaCollection)
        .getOrElseThrow(p => new DMNException("Could not create Kogito DMNRuntime", p))
    )
  }

  override def supportsDecisionService: Boolean = true

  override def providerForType(inputField: DMNInputField): DMNContextProvider[_] = {
    val (path, expr) = (KogitoDMNContextPath(inputField.contextPath), inputField.defaultExpr)

    inputField.providerType.toUpperCase match {
      case "JSON" => KogitoJSONContextProvider(path, expr)
      case t if Try(DataType.fromDDL(t)).isSuccess =>
        val dataType = DataType.fromDDL(t)
        dataType match {
          case StringType => StringContextProvider(path, expr)
          case IntegerType => SimpleContextProvider[Integer](path, expr)
          case LongType => SimpleContextProvider[Long](path, expr)
          case BooleanType => SimpleContextProvider[Boolean](path, expr)
          case DoubleType => SimpleContextProvider[Double](path, expr)
          case FloatType => SimpleContextProvider[Float](path, expr)
          case BinaryType => SimpleContextProvider[Array[Byte]](path, expr)
          case ByteType => SimpleContextProvider[Byte](path, expr)
          case ShortType => SimpleContextProvider[Short](path, expr)
          case DateType => SimpleContextProvider[LocalDate](path, expr, Some{t: Any => DateTimeUtils.daysToLocalDate(t.asInstanceOf[Int])}) // an int
          case TimestampType => SimpleContextProvider[LocalDateTime](path, expr, Some{t: Any => DateTimeUtils.microsToLocalDateTime(t.asInstanceOf[Long])}) // a long
          case _: DecimalType => SimpleContextProvider[java.math.BigDecimal](path, expr, Some{t: Any => t.asInstanceOf[Decimal].toJavaBigDecimal})
          case structType: StructType => ContextInterfaces.structProvider(structType, path, expr)

          // calendar interval? TODO top level array and MAP
          case t => throw new DMNException(s"Provider type $t is not supported")
        }
      case _ =>
        utils.loadUnaryContextProvider(inputField.providerType, path, expr)
    }

  }

  override def resultProviderForType(resultProviderType: String): DMNResultProvider =
    resultProviderType.toUpperCase match {
      case "ARRAY<BOOLEAN>" =>
        KogitoSeqOfBools()
      case "JSON" =>
        KogitoJSONResultProvider()
      case _ =>
        utils.loadResultProvider(resultProviderType)
    }
}

object Types {
  type MAP = java.util.Map[String, Object]
}

case class KogitoDMNContext(ctx: org.kie.dmn.api.core.DMNContext) extends DMNContext {

  def set(path: DMNContextPath, data: Any): Unit = {
    val bits = path.asInstanceOf[KogitoDMNContextPath].path.split('.')
    val starter =
      ctx.get(bits(0)) match {
        case _ if bits.length == 1 =>
          ctx.set(bits.head, data)
          return
        case null if bits.length > 1 =>
          val n = new util.HashMap[String, Object]()
          ctx.set(bits.head, n)
          n
        case t: MAP =>
          t
        case _ => // TODO log warn
          ctx.set(bits.head, data)
          return
      }

    // top is the root context, bottom is the place we'd store things
    bits.drop(1).dropRight(1).foldLeft(starter){
      (map, pathBit) =>
        map match {
          case t: MAP =>
            val n =
              t.get(pathBit) match {
                case null => new util.HashMap[String, Object]()
                case t: MAP => t
                case _ => new util.HashMap[String, Object]()
              }
            t.put(pathBit, n)
            n
          case _ => map
        }
    }

    def updateContext(bits: Seq[String], map: MAP): MAP =
      if (bits.size == 1) {
        map.put(bits.head, data.asInstanceOf[Object])
        map
      } else
        updateContext(bits.drop(1), map.get(bits.head).asInstanceOf[MAP])

    ctx.set(bits.head, updateContext(bits.drop(1).toVector, starter)) // drop the 1st as that's for the root context
  }
}

case class KogitoDMNRuntime(runtime: org.kie.dmn.api.core.DMNRuntime) extends DMNRuntime {

  def getModel(name: String, namespace: String): DMNModel = {
    val model = runtime.getModel(namespace, name)
    if (model eq null) {
      throw new DMNException(s"Could not load model from Kogito runtime with namespace $namespace and name $name - {$namespace}$name")
    }
    KogitoDMNModel(model, runtime)
  }

  def context(): DMNContext = KogitoDMNContext(runtime.newContext())
}
