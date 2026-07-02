package com.sparkutils.dmn.kogito

import com.sparkutils.dmn.DMN4SparkExtension
import com.sparkutils.testing.SparkTestUtils._
import com.sparkutils.testing.sessionStrategies.{GlobalSession, SharedSessions}
import com.sparkutils.testing.{SessionsStateHolder, SparkTestSuite}
import org.scalatest.Matchers

trait BaseSparkTests extends SparkTestSuite with SharedSessions with TestEncoders with Matchers {

  private val hostMode = {
    val tmp = System.getenv("DMN_SPARK_HOSTS")
    if (tmp eq null)
      "*"
    else
      tmp
  }

  private val extensions = Seq(classOf[DMN4SparkExtension]).map(_.getName).reduce(_ + "," + _)

  override val currentSessionsHolder: SessionsStateHolder = GlobalSession

  override val sparkClassicConfig: Map[String, String] =
    super.sparkClassicConfig() + // useDebugConnectLogs +
      scoverageClassPathsConfig +
      fullClassPathConfig +
      testClassesPathsConfig +
      connectMemory("4g") +
      ("spark.master" ->  s"local[$hostMode]") +
      ("spark.sql.extensions" -> extensions)

  override val sparkConnectServerConfig: Map[String, String] =
    super.sparkConnectServerConfig() + // useDebugConnectLogs +
      scoverageClassPathsConfig +
      fullClassPathConfig +
      connectMemory("4g") +
      ("spark.sql.extensions" -> extensions)
}