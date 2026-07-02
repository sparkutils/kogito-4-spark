package com.sparkutils.dmn.kogito.common

import com.sparkutils.dmn.kogito.BaseSparkTests
import com.sparkutils.testing.markers.ConnectSafe
import com.sparkutils.testing.{ConnectionType, UseBoth}
import org.junit.Before
import org.scalatest.FunSuite

trait SparkTests extends FunSuite with BaseSparkTests with ConnectSafe  {

  override val connectionType: ConnectionType = UseBoth

  override val runWith: ConnectionType = UseBoth

  @Before
  def setup(): Unit = {
    // no-op to force it to be created
    cleanupOutput()
  }

}