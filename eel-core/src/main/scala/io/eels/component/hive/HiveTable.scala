package io.eels.component.hive

import io.eels.{Partition, PartitionKey, PartitionPart}
import io.eels.component.parquet.ParquetLogMute
import io.eels.schema.Schema
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.hive.metastore.IMetaStoreClient
import scala.collection.JavaConverters._

case class HiveTable(dbName: String,
                     tableName: String,
                     fs: FileSystem,
                     client: IMetaStoreClient) {
  ParquetLogMute()

  private val ops = new HiveOps(client)

  // returns the full underlying schema from the metastore including partitions
  def schema(): Schema = ops.schema(dbName, tableName)

  def partitionKeys(): List[PartitionKey] = {
    val keys = client.getTable(dbName, tableName).getPartitionKeys.asScala
    val parts = client.listPartitions(dbName, tableName, Short.MaxValue).asScala
    assert(keys.size == parts.size, s"Differing amount of partitionKeys (${keys.size}) to parts (${parts.size})")
    keys.zip(parts).map { case (schema, partition) =>
      val field = HiveSchemaFns.fromHiveField(schema, false).withPartition(true)
      PartitionKey(field, partition.getCreateTime * 1000L, partition.getParameters.asScala.toMap)
    }.toList
  }

  def partitions(): List[Partition] = {
    val keys = partitionKeys()
    client.listPartitions(dbName, tableName, Short.MaxValue).asScala.map { it =>
      val values = it.getValues.asScala.zipWithIndex.map { case (str, int) =>
        val key = keys(int)
        PartitionPart(key.field.name, str)
      }
      Partition(it.getCreateTime * 1000L, it.getSd, values.toList)
    }.toList
  }

  def partitionNames(): List[String] = partitions().map(_.name)

  def movePartition(partition: String, location: String): Unit = ()

  def toSource(): HiveSource = new HiveSource(dbName, tableName, fs = fs, client = client)

  override def toString: String = s"HiveTable(dbName='$dbName', tableName='$tableName')"
}