package com.sparkutils.dmn.kogito.classic

import com.sparkutils.dmn.kogito.BaseSparkTests
import com.sparkutils.testing.markers.DontRunOnPureConnect
import com.sparkutils.testing.{ClassicOnly, ConnectionType}
import org.junit.Before
import org.scalatest.FunSuite

trait SparkTests extends FunSuite with BaseSparkTests with DontRunOnPureConnect {

  override val connectionType: ConnectionType = ClassicOnly

  override val runWith: ConnectionType = ClassicOnly

  @Before
  def setup(): Unit = {
    // no-op to force it to be created
//    sparkSession.conf
    cleanupOutput()
  }

}