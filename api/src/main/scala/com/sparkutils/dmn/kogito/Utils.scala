package com.sparkutils.dmn.kogito

object Utils {

  /**
   * compares two options where normal equality doesn't work.
   * useful for nullability tests.  If either a or b are undefined the function f
   * won't be called.
   * @tparam A
   * @return true if both are undefined or if both are defined and f returns true
   */
  def optEqual[A](a: Option[A], b: Option[A])(f: (A,A) => Boolean): Boolean =
    ((a.isDefined && b.isDefined && (f(a.get, b.get))) ||
      a.isEmpty && b.isEmpty)


  def nullOr[A, R >: AnyRef](f: A => R): A => R =
    what =>
      if (what == null)
        null
      else
        f(what)

}
