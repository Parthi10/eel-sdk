package io.eels.component.parquet

import java.nio.file.Paths

import io.eels.component.parquet.avro.AvroParquetSource
import io.eels.component.parquet.util.ParquetLogMute
import io.eels.schema._
import org.apache.avro.SchemaBuilder
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.parquet.avro.AvroParquetWriter
import org.scalatest.{Matchers, WordSpec}

class AvroParquetSourceTest extends WordSpec with Matchers {
  ParquetLogMute()

  private implicit val conf = new Configuration()
  private implicit val fs = FileSystem.get(conf)

  private val personFile = Paths.get(getClass.getResource("/io/eels/component/parquet/person.avro.pq").toURI)
  private val resourcesDir = personFile.getParent

  "AvroParquetSource" should {
    "read schema" in {
      val people = AvroParquetSource(personFile)
      people.schema shouldBe StructType(
        Field("name", StringType, nullable = false),
        Field("job", StringType, nullable = false),
        Field("location", StringType, nullable = false)
      )
    }
    "read parquet files" in {
      val people = AvroParquetSource(personFile.toAbsolutePath()).toDataStream().toSet.map(_.values)
      people shouldBe Set(
        Vector("clint eastwood", "actor", "carmel"),
        Vector("elton john", "musician", "pinner")
      )
    }
    "read multiple parquet files using file expansion" in {
      import io.eels.FilePattern._
      val people = AvroParquetSource(s"${resourcesDir.toUri.toString}/*.pq").toDataStream().toSet.map(_.values)
      people shouldBe Set(
        Vector("clint eastwood", "actor", "carmel"),
        Vector("elton john", "musician", "pinner"),
        Vector("clint eastwood", "actor", "carmel"),
        Vector("elton john", "musician", "pinner")
      )
    }
    // todo add merge to parquet source
    "merge schemas" ignore {

      try {
        fs.delete(new Path("merge1.pq"), false)
      } catch {
        case t: Throwable =>
      }
      try {
        fs.delete(new Path("merge2.pq"), false)
      } catch {
        case t: Throwable =>
      }

      val schema1 = SchemaBuilder.builder().record("schema1").fields().requiredString("a").requiredDouble("b").endRecord()
      val schema2 = SchemaBuilder.builder().record("schema2").fields().requiredInt("a").requiredBoolean("c").endRecord()

      val writer1 = AvroParquetWriter.builder[GenericRecord](new Path("merge1.pq")).withSchema(schema1).build()
      val record1 = new GenericData.Record(schema1)
      record1.put("a", "aaaaa")
      record1.put("b", 124.3)
      writer1.write(record1)
      writer1.close()

      val writer2 = AvroParquetWriter.builder[GenericRecord](new Path("merge2.pq")).withSchema(schema2).build()
      val record2 = new GenericData.Record(schema2)
      record2.put("a", 111)
      record2.put("c", true)
      writer2.write(record2)
      writer2.close()

      ParquetSource(new Path("merge*")).schema shouldBe
        StructType(
          Field("a", StringType, nullable = false),
          Field("b", DoubleType, nullable = false),
          Field("c", BooleanType, nullable = false)
        )

      fs.delete(new Path(".merge1.pq.crc"), false)
      fs.delete(new Path(".merge2.pq.crc"), false)
      fs.delete(new Path("merge1.pq"), false)
      fs.delete(new Path("merge2.pq"), false)
    }
  }
}

