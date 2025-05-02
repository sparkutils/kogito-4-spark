package com.sparkutils.dmn.kogito.types

import org.apache.spark.sql.catalyst.expressions.UnsafeArrayData
import org.apache.spark.sql.catalyst.expressions.codegen.Block.BlockHelper
import org.apache.spark.sql.catalyst.expressions.codegen.{Block, CodegenContext, ExprCode, JavaCode}
import org.apache.spark.sql.catalyst.util.ArrayData
import org.apache.spark.sql.types.DataType

import scala.reflect.ClassTag

object Arrays {
  /**
   * UnsafeArrayData doesn't allow calling .array, foreach when needed and for others use array
   *
   * @param array
   * @param dataType
   * @param f
   * @return
   */
  def mapArray[T: ClassTag](array: ArrayData, dataType: DataType, f: Any => T): Array[T] =
    array match {
      case _: UnsafeArrayData =>
        val res = Array.ofDim[T](array.numElements())
        array.foreach(dataType, (i, v) => res.update(i, f(v)))
        res
      case _ => array.array.map(f)
    }
/*
  /**
   * gets an array out of UnsafeArrayData or others
   * @param array
   * @param dataType
   * @return
   */
  def toArray(array: ArrayData, dataType: DataType): Array[Any] =
    array match {
      case _: UnsafeArrayData =>
        mapArray(array, dataType, identity)
      case _ => array.array
    }
*/

  def exprCode(clazz: Class[_], ctx: CodegenContext): ExprCode = {
    val isNull = ctx.freshName("isNull")
    val value = ctx.freshName("value")

    val expr = ExprCode(
      JavaCode.isNullVariable(isNull),
      JavaCode.variable(value, clazz)
    )
    expr
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
        Object ${expr.value} = ${if (cast) s"(${boxed.getName})" else ""} $code
        boolean ${expr.isNull} = (${expr.value} == null);
          """)
  }

}