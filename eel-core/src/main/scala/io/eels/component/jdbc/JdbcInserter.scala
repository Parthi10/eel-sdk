package io.eels.component.jdbc

import java.sql.Connection

import com.sksamuel.exts.Logging
import com.sksamuel.exts.jdbc.ResultSetIterator
import io.eels.Chunk
import io.eels.component.jdbc.dialect.JdbcDialect
import io.eels.schema.StructType

class JdbcInserter(val connFn: () => Connection,
                   val table: String,
                   val schema: StructType,
                   val autoCommit: Boolean,
                   val batchesPerCommit: Int,
                   val dialect: JdbcDialect) extends Logging {

  logger.debug("Connecting to JDBC to insert.. ..")
  private val conn = connFn()
  conn.setAutoCommit(autoCommit)
  logger.debug(s"Connected successfully; autoCommit=$autoCommit")

  private var batches = 0

  def insertBatch(batch: Chunk): Unit = {
    val stmt = conn.prepareStatement(dialect.insertQuery(schema, table))
    try {
      batch.foreach { row =>
        row.zipWithIndex.foreach { case (value, k) =>
          stmt.setObject(k + 1, value)
        }
        stmt.addBatch()
      }
      batches = batches + 1
      stmt.executeBatch()
      if (!autoCommit) conn.commit()
      else if (batches == batchesPerCommit) {
        batches = 0
        conn.commit()
      }
    } catch {
      case t: Throwable =>
        logger.error("Batch failure", t)
        if (!autoCommit)
          conn.rollback()
        throw t
    } finally {
      stmt.close()
    }
  }

  def ensureTableCreated(): Unit = {
    logger.info(s"Ensuring table [$table] is created")

    def tableExists(): Boolean = {
      logger.debug(s"Fetching list of tables to detect if $table exists")
      val tables = ResultSetIterator.strings(conn.getMetaData.getTables(null, null, null, Array("TABLE"))).toList
      val tableNames = tables.map(x => x(3).toLowerCase)
      val exists = tableNames.contains(table.toLowerCase())
      logger.debug(s"${tables.size} tables found; $table exists == $exists")
      exists
    }

    if (!tableExists()) {
      val sql = dialect.create(schema, table)
      logger.info(s"Creating table $table [$sql]")
      val stmt = conn.createStatement()
      try {
        stmt.executeUpdate(sql)
        if (!autoCommit) conn.commit()
      } catch {
        case t: Throwable =>
          logger.error("Batch failure", t)
          if (!autoCommit)
            conn.rollback()
          throw t
      } finally {
        stmt.close()
      }
    }
  }
}