package com.sparkutils.dmn.kogito.types

import com.sparkutils.dmn.kogito.types.ContextInterfaces.Accessor
import org.apache.spark.sql.catalyst.expressions.codegen.Block.BlockHelper
import org.apache.spark.sql.catalyst.expressions.codegen.{Block, CodegenContext, ExprCode, JavaCode}
import org.kie.dmn.api.core.DMNDecisionResult

import java.util
import java.util.Map
import scala.collection.JavaConverters.{asJavaIteratorConverter, setAsJavaSetConverter}

object Utils {
  def exprCode(clazz: Class[_], ctx: CodegenContext): ExprCode = {
    val isNull = ctx.freshName("isNull")
    val value = ctx.freshName("value")

    val expr = ExprCode(
      JavaCode.isNullVariable(isNull),
      JavaCode.variable(value, clazz)
    )
    expr
  }

  def exprCodeInterim(boxed: Class[_], ctx: CodegenContext, codeToInterim: Block, withInterim: String => Block, cast: Boolean = true): ExprCode = {
    val ev = exprCode(boxed, ctx)
    val interim = ctx.freshName("interim")
    ev.copy(code =
      code"""
          Object $interim = $codeToInterim;
          Object ${ev.value} = null;
          boolean ${ev.isNull} = ($interim == null);
          if (!${ev.isNull}) {
            ${ev.value} = ${doCast(boxed, cast)} ${withInterim(interim)};
          }
          """)
  }

  private def doCast(boxed: Class[_], cast: Boolean) = {
    val box =
      if (boxed.isArray)
        s"${boxed.componentType().getName}[]"
      else
        boxed.getName

    if (cast)
      s"($box)"
    else
      ""
  }

  def exprCodeIsNullAt(boxed: Class[_], ctx: CodegenContext, nullCheck: Block, nonNull: Block, cast: Boolean = true): ExprCode = {
    val ev = exprCode(boxed, ctx)
    ev.copy(code =
      code"""
          Object ${ev.value} = null;
          boolean ${ev.isNull} = $nullCheck;
          if (!${ev.isNull}) {
            ${ev.value} = ${doCast(boxed, cast)} $nonNull;
          }
          """)
  }

  def exprCode(boxed: Class[_], ctx: CodegenContext, code: Block, cast: Boolean = true): ExprCode = {
    val isNull = ctx.freshName("isNull")
    val value = ctx.freshName("value")

    val expr = ExprCode(
      JavaCode.isNullVariable(isNull),
      JavaCode.variable(value, classOf[Object])
    )
    expr.copy(code =
      code"""
        Object ${expr.value} = ${doCast(boxed, cast)} $code
        boolean ${expr.isNull} = (${expr.value} == null);
          """)
  }

  def optEqual[A](a: Option[A], b: Option[A])(f: (A,A) => Boolean): Boolean =
    ((a.isDefined && b.isDefined && (f(a.get, b.get))) ||
      a.isEmpty && b.isEmpty)

}

class BaseKogitoMap(path: Any, pairs: scala.collection.Map[String, (Int, Accessor[_])]) extends SimpleMap {

  override def containsKey(key: Any): Boolean =
    pairs.contains(key.toString)

  override def get(key: Any): AnyRef = {
    val (i, a) = pairs(key.toString)
    val t = a.forPath(path, i)
    if (t == null) null else t.asInstanceOf[AnyRef]
  }

  // called by Jackson serializing
  override def entrySet(): util.Set[util.Map.Entry[String, Object]] = pairs.map{case (key, (i, accessor)) => new util.Map.Entry[String, Object]{
    override def getKey: String = key

    override def getValue: Object = {
      val t = accessor.forPath(path, i)
      if (t == null) null else t.asInstanceOf[AnyRef]
    }
    // $COVERAGE-OFF$
    override def setValue(value: Object): AnyRef = ???
    // $COVERAGE-ON$
  }}.toSet.asJava

  // $COVERAGE-OFF$
  override def size(): Int = pairs.size
  // $COVERAGE-ON$
}

trait ProxyEntry[T] {
  def get(i: Int, t: T): java.util.Map.Entry[String, Object]
}

class DecisionResultFullProxyEntry() extends ProxyEntry[java.util.List[org.kie.dmn.api.core.DMNDecisionResult]] {

  private var key = ""
  private var res: Object = _
  private var entry = new Map.Entry[String, Object] {
    override def getKey: String = key

    override def getValue: AnyRef = res

    // $COVERAGE-OFF$
    override def setValue(value: Object): AnyRef = ???
    // $COVERAGE-ON$
  }

  override def get(i: Int, t: util.List[DMNDecisionResult]): Map.Entry[String, Object] = {
    val r = t.get(i)
    key = r.getDecisionName
    res = r.getResult
    entry
  }
}

class ProxyMap[T](var _size: Int, var t: T, val proxyEntry: ProxyEntry[T]) extends SimpleMap {

  // should never be called
  // $COVERAGE-OFF$
  override def containsKey(key: Any): Boolean = {
    for(i <- 0 until _size) {
      val e = proxyEntry.get(i, t)
      if (e.getKey == key.toString) {
        return true
      }
    }
    false
  }
  // $COVERAGE-ON$

  // resets the underlying data - e.g. new dmn result
  def reset(size: Int, _t: T): Unit = {
    _size = size
    t = _t
    proxySet.itr.reset()
  }

  override def get(key: Any): AnyRef = {
    for(i <- 0 until _size) {
      val e = proxyEntry.get(i, t)
      if (e.getKey == key.toString) {
        return e.getValue
      }
    }
    null
  }

  val proxySet = new ProxySet[T](this)
  override def entrySet(): util.Set[util.Map.Entry[String, Object]] = proxySet

  override def size(): Int = _size
}

class ProxyIterator[E](map: ProxyMap[E]) extends util.Iterator[util.Map.Entry[String, Object]] {
  var i = 0
  def reset(): Unit = {
    i = 0
  }

  override def hasNext: Boolean =
    i < map.size

  override def next(): util.Map.Entry[String, Object] = {
    val r = map.proxyEntry.get(i, map.t)
    i += 1
    r
  }
}

// only provides iterator
class ProxySet[E](map: ProxyMap[E]) extends java.util.Set[util.Map.Entry[String, Object]] {
  // $COVERAGE-OFF$
  override def size(): Int = ???

  override def isEmpty: Boolean = ???

  override def contains(o: Any): Boolean = ???
  // $COVERAGE-ON$

  val itr = new ProxyIterator(map)

  override def iterator(): util.Iterator[util.Map.Entry[String, Object]] = itr

  // $COVERAGE-OFF$
  override def retainAll(c: util.Collection[_]): Boolean = ???

  override def removeAll(c: util.Collection[_]): Boolean = ???

  override def clear(): Unit = ???

  override def toArray: Array[AnyRef] = ???

  // ERROR in intellij is not present in actual compiler
  override def toArray[T](a: Array[T with Object]): Array[T with Object] = ???

  override def add(e: Map.Entry[String, Object]): Boolean = ???

  override def remove(o: Any): Boolean = ???

  override def containsAll(c: util.Collection[_]): Boolean = ???

  override def addAll(c: util.Collection[_ <: Map.Entry[String, Object]]): Boolean = ???
  // $COVERAGE-ON$
}

abstract class SimpleMap extends util.Map[String, Object] {
  // called by kogito
  //override def get(key: Any): AnyRef = ???

  // called by Jackson serializing, strictly it's just the iterator
  //override def entrySet(): util.Set[util.Map.Entry[String, Object]] = ???

  override def isEmpty: Boolean = size() == 0

  // is called by kogito when get returns null
  // override def containsKey(key: Any): Boolean = ??? // pairs.contains(key.toString)


  // Never called by kogito

  // $COVERAGE-OFF$
  override def keySet(): util.Set[String] = ??? //pairs.keySet.asJava

  //override def size(): Int = ??? //pairs.size

  // Never being implemented

  override def containsValue(value: Any): Boolean = ???

  override def values(): util.Collection[Object] = ???

  override def put(key: String, value: Object): AnyRef = ???

  override def remove(key: Any): AnyRef = ???

  override def putAll(m: util.Map[_ <: String, _ <: Object]): Unit = ???

  override def clear(): Unit = ???
  // $COVERAGE-ON$
}

class ArrayEntry[K, V](keys: Array[K], values: Array[V], i: Int) extends java.util.Map.Entry[K, V] {

  override def getKey: K = keys(i)

  override def getValue: V = values(i)

  // $COVERAGE-OFF$
  override def setValue(value: V): V = ???
  // $COVERAGE-ON$
}

class ArraySet[E](backed: Array[E]) extends java.util.Set[E] {
  // $COVERAGE-OFF$
  override def size(): Int = backed.length

  override def isEmpty: Boolean = backed.isEmpty

  override def contains(o: Any): Boolean =
    o match {
      case o: E =>  backed.contains(o)
      case _ => false
    }
  // $COVERAGE-ON$
  override def iterator(): util.Iterator[E] =
    backed.iterator.asJava

  // $COVERAGE-OFF$
  override def toArray: Array[AnyRef] = backed.asInstanceOf[Array[AnyRef]]

  // ERROR in intellij is not present in actual compiler
  override def toArray[T](a: Array[T with Object]): Array[T with Object] = {
    System.arraycopy(backed, 0, a, 0, backed.length)
    a
  }

  override def add(e: E): Boolean = ???

  override def remove(o: Any): Boolean = ???

  override def containsAll(c: util.Collection[_]): Boolean = ???

  override def addAll(c: util.Collection[_ <: E]): Boolean = ???

  override def retainAll(c: util.Collection[_]): Boolean = ???

  override def removeAll(c: util.Collection[_]): Boolean = ???

  override def clear(): Unit = ???

  // $COVERAGE-ON$
}
