package io.eels.component.hive

import java.io.File

import io.eels.datastream.DataStream
import io.eels.schema.{Field, StringType, StructType}
import org.scalatest.{FunSuite, Matchers}

class MetastoreSchemaHandlerTest extends FunSuite with Matchers {

  import HiveConfig._

  val dbname = HiveTestUtils.createTestDatabase
  private val table = "evolution_test_" + System.currentTimeMillis()

  test("Evolution handler should allow columns to be added to a hive table") {
    assume(new File(s"$basePath/core-site.xml").exists)

    val schema1 = StructType(Field("a", StringType))
    DataStream.fromValues(schema1, Seq(Seq("a"))).to(HiveSink(dbname, table).withCreateTable(true))

    val schema2 = StructType(Field("a", StringType), Field("b", StringType))
    DataStream.fromValues(schema2, Seq(Seq("a", "b"))).to(HiveSink(dbname, table).withMetastoreSchemaHandler(EvolutionMetastoreSchemaHandler))

    HiveSource(dbname, table).schema shouldBe schema2
  }
}
