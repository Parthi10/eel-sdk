package io.eels

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{CountDownLatch, Executors, LinkedBlockingQueue, TimeUnit}

import com.sksamuel.exts.Logging
import com.sksamuel.exts.collection.BlockingQueueConcurrentIterator
import com.sksamuel.exts.concurrent.ExecutorImplicits._
import com.sksamuel.exts.io.Using
import com.typesafe.config.ConfigFactory
import io.eels.schema.Schema
import rx.lang.scala.{Observable, Observer, Subscriber}

import scala.util.control.NonFatal

class FrameSource(ioThreads: Int,
                  source: Source,
                  observer: Observer[Row] = NoopObserver) extends Frame with Logging with Using {

  private val config = ConfigFactory.load()
  private val bufferSize = config.getInt("eel.source.defaultBufferSize")
  logger.info(s"FrameSource is configured with bufferSize=$bufferSize")

  override def schema(): Schema = source.schema()

  // this method may be invoked multiple times, each time generating a new "load action" and a new
  // resultant rows from it.
  override def rows(): Observable[Row] = {

    // the number of ioThreads here will determine how we parallelize the loading of data
    // into the rows. Each thread will read from a single part, so the more threads
    // the more parts will be read concurrently.
    //
    // Each thread will read its part into an intermediate queue, which we use in order to eagerly
    // buffer values, and the queue is read from by the rows to produce values.
    //
    val executor = Executors.newFixedThreadPool(ioThreads)
    logger.info(s"Source will read using a FixedThreadPool [ioThreads=$ioThreads]")

    val parts = source.parts()
    logger.info(s"Source has ${parts.size} part(s)")

    // faster to use a LinkedBlockingQueue than an array one
    // because a LinkedBlockingQueue has a lock for both head and tail so
    // allows us to push and pop at same time
    // very useful for our pipeline
    val queue = new LinkedBlockingQueue[Row](bufferSize)
    val count = new AtomicLong(0)
    val latch = new CountDownLatch(parts.size)
    var error: Throwable = null

    for (part <- parts) {
      // we create a task per part, and each task just reads from that part putting the data
      // into the shared buffer. The more threads we allocate to this the more tasks (parts)
      // we can process concurrently.
      executor.submit {
        val id = count.incrementAndGet()
        try {

          part.data().subscribe(new Subscriber[Row]() {

            override def onStart() {
              logger.info(s"Starting part #$id")
            }

            override def onNext(row: Row) {
              queue.put(row)
              observer.onNext(row)
              if (error != null) {
                this.unsubscribe()
                latch.countDown()
                logger.warn(s"Error detected, shutting down part #$id")
              }
            }

            override def onError(e: Throwable) {
              logger.error(s"Error while reading part #$id; the remaining rows will be skipped", e)
              error = e
              this.unsubscribe()
              latch.countDown()
              observer.onError(e)
            }

            override def onCompleted() {
              logger.info(s"Completed part #$id")
              latch.countDown()
              observer.onCompleted()
            }
          })
        } catch {
          case NonFatal(e) =>
            logger.error(s"Error while reading part #$id; the remaining rows will be skipped", e)
            error = e
            observer.onError(e)
        } finally {
          latch.countDown()
        }
      }
    }

    executor.submit {
      // we use a latch so that we can wait for all the parts to be completed
      latch.await(1, TimeUnit.DAYS)
      logger.info("All source parts completed; latch released")
      try {
        // we add a sentinel so that our BlockingQueueConcurrentIterator knows when the queue has finished.
        // since it is blocking, without a sentinel or an interupt it would block forever
        logger.debug("Sending sentinel to source row queue")
        queue.put(Row.Sentinel)
      } catch {
        case NonFatal(e) =>
          logger.error("Error adding Sentinel", e)
          if (error == null)
            error = e
      }
    }
    executor.shutdown()

    Observable { sub =>
      sub.onStart()
      BlockingQueueConcurrentIterator(queue, Row.Sentinel).takeWhile(_ => !sub.isUnsubscribed).foreach { row =>
        logger.debug(row.toString)
        sub.onNext(row)
      }
      logger.debug("Blocking queue has emptied")
      if (error == null)
        sub.onCompleted()
      else
        sub.onError(error)
    }
  }
}