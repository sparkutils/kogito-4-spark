package com.sparkutils.dmn.kogito

import com.sparkutils.dmn
import com.sparkutils.dmn.{impl => dmnimpl}
import com.sparkutils.dmn.impl.implicits.DMNInputFieldExt
import com.sparkutils.dmn.kogito.ContextProviders.contextProviderFromDDL
import com.sparkutils.dmn.kogito.{Errors => E}
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.types.{DataType, StringType, StructType}
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder
import org.kie.internal.io.ResourceFactory

import scala.collection.JavaConverters._
import scala.util.Try

/**
 * Represents a repository of DMN, this is the actual root provider
 */
class KogitoDMNRepository() extends dmn.DMNRepository {
  /**
   * Throws DMNException if it can't be constructed
   * @param dmnFiles
   * @return
   */
  def dmnRuntimeFor(dmnFiles: Seq[dmn.DMNFile], dmnConfiguration: dmn.DMNConfiguration): dmn.DMNRuntime = {

    val resources = dmnFiles.map{ f =>
      val r = ResourceFactory.newByteArrayResource(f.bytes)
      f.locationURI -> r.setSourcePath(f.locationURI)
    }.toMap

    KogitoDMNRuntime(
      DMNRuntimeBuilder.fromDefaults()
        .setRelativeImportResolver((_,_, locationURI) => resources(locationURI).getReader)
        .buildConfiguration()
        .fromResources(resources.values.asJavaCollection)
        .getOrElseThrow(p => dmn.DMNException("Could not create Kogito DMNRuntime", p))
    )
  }

  override def supportsDecisionService: Boolean = true

  override def providerForType(inputField: dmn.DMNInputField, debug: Boolean, dmnConfiguration: dmn.DMNConfiguration): dmn.DMNContextProvider[_] = {
    val (path, expr) = try {
      (KogitoDMNContextPath(inputField.contextPath), inputField.defaultExpr)
    } catch {
      case p: ParseException => throw dmn.DMNException(s"${E.CONTEXT_PROVIDER_PARSE} : ${inputField.fieldExpression}", p)
    }

    val config = dmnimpl.utils.configMap(dmnConfiguration)

    inputField.providerType match {
      case "" => ContextProviderProxy(path, inputField.stillSetWhenNull, expr, config, providedType = None)
      case "JSON" => KogitoJSONContextProvider(path, inputField.stillSetWhenNull, expr, providedType = Some(StringType))
      case t if Try(DataType.fromDDL(t)).isSuccess =>
        val dataType = DataType.fromDDL(t)
        contextProviderFromDDL(inputField.stillSetWhenNull, path, expr, config, dataType)
      case _ =>
        dmnimpl.utils.loadUnaryContextProvider(inputField.providerType, path, expr)
    }

  }

  override def resultProviderForType(resultProviderType: String, debug: Boolean, dmnConfiguration: dmn.DMNConfiguration): dmn.DMNResultProvider =
    resultProviderType match {
      case _ if resultProviderType.toUpperCase == "JSON" =>
        KogitoJSONResultProvider(debug, dmnimpl.utils.configMap(dmnConfiguration))
      case t if Try(DataType.fromDDL(t)).isSuccess =>
        val dataType = DataType.fromDDL(t)
        dataType match {
          case s: StructType =>
            KogitoDDLResult(debug = debug, underlyingType = s, dmnimpl.utils.configMap(dmnConfiguration))
          case _ => throw new dmn.DMNException(s"ResultProvider type $t is not supported, only JSON and Struct is")
        }
      case _ =>
        dmnimpl.utils.loadResultProvider(resultProviderType, debug)
    }
}