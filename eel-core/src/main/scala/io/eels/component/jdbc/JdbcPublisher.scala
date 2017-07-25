package io.eels.component.jdbc

import java.sql.{Connection, PreparedStatement}

import com.sksamuel.exts.io.Using
import com.sksamuel.exts.metrics.Timed
import io.eels.Row
import io.eels.component.jdbc.dialect.JdbcDialect
import io.eels.datastream.{Subscription, Publisher, Subscriber}

import scala.collection.mutable.ArrayBuffer

class JdbcPublisher(connFn: () => Connection,
                    query: String,
                    bindFn: (PreparedStatement) => Unit,
                    fetchSize: Int,
                    dialect: JdbcDialect
              ) extends Publisher[Seq[Row]] with Timed with JdbcPrimitives with Using {

  override def subscribe(subscriber: Subscriber[Seq[Row]]): Unit = {
    try {
      using(connFn()) { conn =>

        logger.debug(s"Preparing query $query")
        using(conn.prepareStatement(query)) { stmt =>

          stmt.setFetchSize(fetchSize)
          bindFn(stmt)

          logger.debug(s"Executing query $query")
          using(stmt.executeQuery()) { rs =>

            var cancelled = false

            subscriber.subscribed(new Subscription {
              override def cancel(): Unit = cancelled = true
            })

            val schema = schemaFor(dialect, rs)

            val buffer = new ArrayBuffer[Row](fetchSize)
            while (rs.next && !cancelled) {
              val values = schema.fieldNames().map { name =>
                val raw = rs.getObject(name)
                dialect.sanitize(raw)
              }
              buffer append Row(schema, values)
              if (buffer.size == fetchSize) {
                subscriber.next(buffer.toVector)
                buffer.clear()
              }
            }

            if (buffer.nonEmpty)
              subscriber.next(buffer.toVector)

            subscriber.completed()
          }
        }
      }
    } catch {
      case t: Throwable => subscriber.error(t)
    }
  }
}
