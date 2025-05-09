package com.sparkutils.dmn.kogito

import com.globalmentor.apache.hadoop.fs.BareLocalFileSystem
import org.apache.spark.sql.SparkSession
import org.junit.Before

import java.util.concurrent.atomic.AtomicReference

trait TestUtils {
  val hostMode = {
    val tmp = System.getenv("DMN_SPARK_HOSTS")
    if (tmp eq null)
      "*"
    else
      tmp
  }

  lazy val sparkSession = {
    val s = registerFS(SparkSession.builder()).config("spark.master",  s"local[$hostMode]").config("spark.ui.enabled", false).getOrCreate()
    s.sparkContext.setLogLevel("ERROR") // set to debug to get actual code lines etc.
    s
  }

  import org.apache.spark.sql.internal.SQLConf
  import org.apache.spark.sql.catalyst.expressions.CodegenObjectFactoryMode


  def cleanUp(target: String): Unit = {
    import scala.reflect.io.Directory
    val outdir = new Directory(new java.io.File(target))
    outdir.deleteRecursively()
  }

  val outputDir = TestUtils.outputDir

  def cleanupOutput(): Unit =
    cleanUp(outputDir)

  @Before
  def setup(): Unit = {
    // no-op to force it to be created
    sparkSession.conf
    cleanupOutput()
  }

  /**
   * Allows bare naked to be used instead of winutils for testing / dev
   */
  def registerFS(sparkSessionBuilder: SparkSession.Builder): SparkSession.Builder =
    if (System.getProperty("os.name").startsWith("Windows"))
      sparkSessionBuilder.config("spark.hadoop.fs.file.impl",classOf[BareLocalFileSystem].getName)
    else
      sparkSessionBuilder

  // if this blows then debug on CodeGenerator 1294, 1299 and grab code.body
  def forceCodeGen[T](f: => T): T = {
    val codegenMode = CodegenObjectFactoryMode.CODEGEN_ONLY.toString

    withSQLConf(SQLConf.CODEGEN_FACTORY_MODE.key -> codegenMode) {
      f
    }
  }

  def forceInterpreted[T](f: => T): T = {
    val codegenMode = CodegenObjectFactoryMode.NO_CODEGEN.toString

    withSQLConf(SQLConf.CODEGEN_FACTORY_MODE.key -> codegenMode) {
      f
    }
  }

  /**
   * runs the same test with both eval and codegen
   * @param f
   * @tparam T
   * @return
   */
  def evalCodeGens[T](f: => T):(T,T)  =
    (forceCodeGen(f), forceInterpreted(f))

  /**
   * Sets all SQL configurations specified in `pairs`, calls `f`, and then restores all SQL
   * configurations.
   */
  protected def withSQLConf[T](pairs: (String, String)*)(f: => T): T = {
    val conf = SQLConf.get
    val (keys, values) = pairs.unzip
    val currentValues = keys.map { key =>
      if (conf.contains(key)) {
        Some(conf.getConfString(key))
      } else {
        None
      }
    }
    (keys, values).zipped.foreach { (k, v) =>
      conf.setConfString(k, v)
    }
    try f finally {
      keys.zip(currentValues).foreach {
        case (key, Some(value)) => conf.setConfString(key, value)
        case (key, None) => conf.unsetConf(key)
      }
    }
  }
}

object TestUtils {

  protected var tpath = new AtomicReference[String]("./target/testData")

  def outputDir = tpath.get

  def setPath(newPath: String) = {
    tpath.set(newPath)
  }

  def path(suffix: String) = s"${tpath.get}/$suffix"

}