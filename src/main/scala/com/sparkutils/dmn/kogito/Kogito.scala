package com.sparkutils.dmn.kogito

import com.sparkutils.dmn._
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder
import org.kie.internal.io.ResourceFactory

import scala.collection.JavaConverters._

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
object KogitoDMNRepository extends DMNRepository {
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
}

case class KogitoDMNContext(ctx: org.kie.dmn.api.core.DMNContext) extends DMNContext {
  def set(path: DMNContextPath, data: Any): Unit =
    ctx.set(path.asInstanceOf[KogitoDMNContextPath].path, data)
}

case class KogitoDMNRuntime(runtime: org.kie.dmn.api.core.DMNRuntime) extends DMNRuntime {

  def getModel(name: String, namespace: String): DMNModel = KogitoDMNModel(runtime.getModel(name, namespace), runtime)

  def context(): DMNContext = KogitoDMNContext(runtime.newContext())
}
