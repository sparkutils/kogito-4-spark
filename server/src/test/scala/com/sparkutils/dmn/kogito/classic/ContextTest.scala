package com.sparkutils.dmn.kogito.classic

import com.sparkutils.dmn._
import com.sparkutils.dmn.kogito.common.SparkTests
import com.sparkutils.dmn.kogito.infra.{Others => O}
import com.sparkutils.dmn.kogito.{KogitoDMNContext, KogitoDMNContextPath, KogitoDMNRepository, Types => T}

import java.util

// uses internal dmn 'server' types not api as such it's ClassicOnly
class ContextTest extends SparkTests {

  val ons = "onetoone"

  val odmnFiles = scala.collection.immutable.Seq(
    DMNFile("onetoone.dmn",
      this.getClass.getClassLoader.getResourceAsStream("onetoone.dmn").readAllBytes()
    )
  )
  val odmnModel = DMNModelService(ons, ons, None, s"struct<evaluate: ${O.ddl}>")

  lazy val runtime = new KogitoDMNRepository().dmnRuntimeFor(odmnFiles, DMNConfiguration.empty)

  def get(ctx: KogitoDMNContext, path: String): Any = {
    val bits = path.split('.')
    if (bits.isEmpty) {
      return null
    }
    if (bits.length == 1) {
      return ctx.ctx.get(bits.head)
    }

    val mapStarter = ctx.ctx.get(bits.head).asInstanceOf[T.MAP]
    val lastMap = bits.drop(1).foldLeft(mapStarter){
      case (l, bit) =>
        val n = l.get(bit)
        n match {
          case t: T.MAP => t
          case _ => l
        }
    }

    lastMap.get(bits.last)
  }

  test("nested maps") {
    val ctx = runtime.context().asInstanceOf[KogitoDMNContext]
    ctx.set(KogitoDMNContextPath("root"), new util.HashMap())
    ctx.set(KogitoDMNContextPath("root.nested"), new util.HashMap())
    ctx.set(KogitoDMNContextPath("root.nested.more"), new util.HashMap())
    ctx.set(KogitoDMNContextPath("root.nested.more.entry"), "value")

    get(ctx, "root.nested.more.entry") shouldBe "value"

    // wipe out the previous
    ctx.set(KogitoDMNContextPath("root.nested.more"), new util.HashMap())

    get(ctx, "root.nested.more.entry") shouldBe (null: String)

    ctx.set(KogitoDMNContextPath("root.nested.more.entry"), "another")

    get(ctx, "root.nested.more.entry") shouldBe "another"

    // overwrite should work
    ctx.set(KogitoDMNContextPath("root.nested.more.entry"), "yet another")

    get(ctx, "root.nested.more.entry") shouldBe "yet another"
  }

  test("top level fields") {
    val ctx = runtime.context().asInstanceOf[KogitoDMNContext]
    ctx.set(KogitoDMNContextPath("entry"), "value")

    get(ctx, "entry") shouldBe "value"

    ctx.set(KogitoDMNContextPath("entry"), "another")

    get(ctx, "entry") shouldBe "another"
  }

  test("top level fields but nested set") {
    val ctx = runtime.context().asInstanceOf[KogitoDMNContext]
    ctx.set(KogitoDMNContextPath("entry"), "value")

    get(ctx, "entry") shouldBe "value"

    // should wipe it out
    ctx.set(KogitoDMNContextPath("entry.nested"), "another")

    get(ctx, "entry.nested") shouldBe "another"
  }

}
