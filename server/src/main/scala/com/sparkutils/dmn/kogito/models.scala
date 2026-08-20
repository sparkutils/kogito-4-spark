package com.sparkutils.dmn.kogito

import com.sparkutils.dmn
import com.sparkutils.dmn.kogito.{Types => T}
import org.kie.dmn.api.{core => kogito}

import java.util

object Types {
  type MAP = java.util.Map[String, Object]
}

case class KogitoDMNResult(result: kogito.DMNResult) extends dmn.DMNResult

case class KogitoDMNContextPath(path: String) extends dmn.DMNContextPath

/**
 * Represents an executable DMN Model
 */
case class KogitoDMNModel(model: kogito.DMNModel, runtime: kogito.DMNRuntime) extends dmn.DMNModel {

  def evaluateAll(ctx: dmn.DMNContext): dmn.DMNResult =
    KogitoDMNResult(runtime.evaluateAll(model, ctx.asInstanceOf[KogitoDMNContext].ctx))

  def evaluateDecisionService(ctx: dmn.DMNContext, service: String): dmn.DMNResult =
    KogitoDMNResult(runtime.evaluateDecisionService(model, ctx.asInstanceOf[KogitoDMNContext].ctx, service))

}

case class KogitoDMNContext(ctx: kogito.DMNContext) extends dmn.DMNContext {

  def set(path: dmn.DMNContextPath, data: Any): Unit = {
    val bits = path.asInstanceOf[KogitoDMNContextPath].path.split('.')
    val starter =
      ctx.get(bits(0)) match {
        case _ if bits.length == 1 =>
          // top level direct entries only (map or otherwise)
          ctx.set(bits.head, data)
          return
        case t: T.MAP =>
          t
        case _ =>
          // any other top level field must be overwritten
          val n = new util.HashMap[String, Object]()
          ctx.set(bits.head, n)
          n
      }

    val remaining =
      if (data.isInstanceOf[T.MAP])
        bits.drop(1)
      else
        bits.drop(1).dropRight(1)

    remaining.foldLeft(starter){
      (map, pathBit) =>
        val n =
          map.get(pathBit) match {
            case null => new util.HashMap[String, Object]()
            case t: T.MAP => t
          }
        map.put(pathBit, n)
        n
    }

    def updateContext(bits: Seq[String], map: T.MAP): T.MAP =
      if (bits.size == 1) {
        map.put(bits.head, data.asInstanceOf[Object])
        map
      } else
        updateContext(bits.drop(1), map.get(bits.head).asInstanceOf[T.MAP])

    updateContext(bits.drop(1).toVector, starter)
  }
}

case class KogitoDMNRuntime(runtime: kogito.DMNRuntime) extends dmn.DMNRuntime {

  def getModel(name: String, namespace: String): dmn.DMNModel = {
    val model = runtime.getModel(namespace, name)
    if (model eq null) {
      throw new dmn.DMNException(s"Could not load model from Kogito runtime with namespace $namespace and name $name - {$namespace}$name")
    }
    KogitoDMNModel(model, runtime)
  }

  def context(): dmn.DMNContext = KogitoDMNContext(runtime.newContext())
}
