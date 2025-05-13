package com.sparkutils.dmn.kogito

import com.sparkutils.dmn._
import com.sparkutils.dmn.impl._
import com.sparkutils.dmn.impl.utils.configMap
import com.sparkutils.dmn.kogito.ContextProviders.contextProviderFromDDL
import com.sparkutils.dmn.kogito.Errors.CONTEXT_PROVIDER_PARSE
import com.sparkutils.dmn.kogito.Types.{MAP, OSEQ}
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.types.{DataType, StructType}
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder
import org.kie.internal.io.ResourceFactory

import java.util
import scala.collection.JavaConverters._
import scala.util.Try

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
  def dmnRuntimeFor(dmnFiles: scala.collection.immutable.Seq[DMNFile], dmnConfiguration: DMNConfiguration): DMNRuntime = {

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

  override def providerForType(inputField: DMNInputField, debug: Boolean, dmnConfiguration: DMNConfiguration): DMNContextProvider[_] = {
    val (path, expr) = try {
      (KogitoDMNContextPath(inputField.contextPath), inputField.defaultExpr)
    } catch {
      case p: ParseException => throw DMNException(s"$CONTEXT_PROVIDER_PARSE : ${inputField.fieldExpression}", p)
    }

    val config = configMap(dmnConfiguration)

    inputField.providerType match {
      case "" => ContextProviderProxy(path, inputField.stillSetWhenNull, expr, config)
      case "JSON" => KogitoJSONContextProvider(path, inputField.stillSetWhenNull, expr)
      case t if Try(DataType.fromDDL(t)).isSuccess =>
        val dataType = DataType.fromDDL(t)
        contextProviderFromDDL(inputField.stillSetWhenNull, path, expr, config, dataType)
      case _ =>
        utils.loadUnaryContextProvider(inputField.providerType, path, expr)
    }

  }

  override def resultProviderForType(resultProviderType: String, debug: Boolean, dmnConfiguration: DMNConfiguration): DMNResultProvider =
    resultProviderType match {
      case _ if resultProviderType.toUpperCase == "JSON" =>
        KogitoJSONResultProvider(debug, configMap(dmnConfiguration))
      case t if Try(DataType.fromDDL(t)).isSuccess =>
        val dataType = DataType.fromDDL(t)
        dataType match {
          case s: StructType =>
            KogitoDDLResult(debug = debug, underlyingType = s, configMap(dmnConfiguration))
          case _ => throw new DMNException(s"ResultProvider type $t is not supported, only JSON and Struct is")
        }
      case _ =>
        utils.loadResultProvider(resultProviderType, debug)
    }
}

object Types {
  type MAP = java.util.Map[String, Object]
  type OSEQ[A] = scala.collection.Seq[A]
}

case class KogitoDMNContext(ctx: org.kie.dmn.api.core.DMNContext) extends DMNContext {

  def set(path: DMNContextPath, data: Any): Unit = {
    val bits = path.asInstanceOf[KogitoDMNContextPath].path.split('.')
    val starter =
      ctx.get(bits(0)) match {
        case _ if bits.length == 1 =>
          // top level direct entries only (map or otherwise)
          ctx.set(bits.head, data)
          return
        case t: MAP =>
          t
        case _ =>
          // any other top level field must be overwritten
          val n = new util.HashMap[String, Object]()
          ctx.set(bits.head, n)
          n
      }

    val remaining =
      if (data.isInstanceOf[MAP])
        bits.drop(1)
      else
        bits.drop(1).dropRight(1)

    remaining.foldLeft(starter){
      (map, pathBit) =>
        val n =
          map.get(pathBit) match {
            case null => new util.HashMap[String, Object]()
            case t: MAP => t
          }
        map.put(pathBit, n)
        n
    }

    def updateContext(bits: Seq[String], map: MAP): MAP =
      if (bits.size == 1) {
        map.put(bits.head, data.asInstanceOf[Object])
        map
      } else
        updateContext(bits.drop(1), map.get(bits.head).asInstanceOf[MAP])

    updateContext(bits.drop(1).toVector, starter)
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

case class KogitoFeelEvent(severity: String, message: String, line: Int, column: Int, sourceException: String, offendingSymbol: String) extends Serializable

case class KogitoMessage(sourceId: String, sourceReference: String, exception: String, feelEvent: KogitoFeelEvent) extends Serializable

/**
 * Represents the DDL provider output type for debugMode
 * @param decisionId
 * @param decisionName
 * @param hasErrors
 * @param messages
 */
case class KogitoResult(decisionId: String, decisionName: String, hasErrors: Boolean, messages: OSEQ[KogitoMessage], evaluationStatus: String) extends Serializable

object Errors {
  val CONTEXT_PROVIDER_PARSE = "FieldExpression is invalid SQL"
}