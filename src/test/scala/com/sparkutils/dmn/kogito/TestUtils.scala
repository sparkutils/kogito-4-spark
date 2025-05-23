package com.sparkutils.dmn.kogito

import com.globalmentor.apache.hadoop.fs.BareLocalFileSystem
import frameless.TypedEncoder
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.shim.StaticInvoke4
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types.{DataType, DateType, ObjectType, TimestampNTZType, TimestampType}
import org.junit.Before

import java.time.{LocalDate, LocalDateTime}
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

  def inCodegen: Boolean = {
    sparkSession.conf.get(SQLConf.CODEGEN_FACTORY_MODE.key) ==
      CodegenObjectFactoryMode.CODEGEN_ONLY.toString
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

  implicit val sqlDate: TypedEncoder[LocalDate] = new TypedEncoder[LocalDate] {
    def nullable: Boolean = false

    def jvmRepr: DataType = ObjectType(classOf[LocalDate])
    def catalystRepr: DataType = DateType

    def toCatalyst(path: Expression): Expression =
      StaticInvoke4(
        DateTimeUtils.getClass,
        DateType,
        "localDateToDays",
        path :: Nil,
        returnNullable = false)

    def fromCatalyst(path: Expression): Expression =
      StaticInvoke4(
        DateTimeUtils.getClass,
        ObjectType(classOf[java.time.LocalDate]),
        "daysToLocalDate",
        path :: Nil,
        returnNullable = false)
  }

  implicit val timestampEncoder: TypedEncoder[LocalDateTime] =
    new TypedEncoder[LocalDateTime] {
      def nullable: Boolean = false

      def jvmRepr: DataType = ObjectType(classOf[LocalDateTime])
      def catalystRepr: DataType = TimestampType

      def toCatalyst(path: Expression): Expression =
        StaticInvoke4(
          DateTimeUtils.getClass,
          TimestampNTZType,
          "localDateTimeToMicros",
          path :: Nil,
          returnNullable = false)

      def fromCatalyst(path: Expression): Expression =
        StaticInvoke4(
          DateTimeUtils.getClass,
          ObjectType(classOf[java.time.LocalDateTime]),
          "microsToLocalDateTime",
          path :: Nil,
          returnNullable = false)

      override def toString: String = "timestampEncoder"
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