package com.sparkutils.dmn.kogito.types

import com.sparkutils.dmn.DMNConfiguration
import org.apache.spark.sql.catalyst.expressions.UnsafeArrayData
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

}