package mumbler.remote

import java.io._
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.ByteBuffer
import java.util.regex.Pattern

import java.util.concurrent.{ForkJoinPool, ThreadFactory}
import java.util.concurrent.atomic.AtomicLong

import scala.util.Success
import scala.util.Failure
import scala.concurrent._
import scala.concurrent.duration.Duration
import java.util.concurrent.Executors

import scala.annotation.tailrec
import com.typesafe.scalalogging.StrictLogging

import org.apache.http.HttpEntity
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicNameValuePair
import org.apache.http.util.EntityUtils
/**
 * @author mdye
 */
object Writer extends StrictLogging {

	// dangerous b/c it can eat the box. That's also what we want to do in the demo
	//val processingThreadPool = Executors.newCachedThreadPool(
	//	new ThreadFactory {
	//	  private val counter = new AtomicLong(0L)

	//	  def newThread(r: Runnable) = {
	//	    val th = new Thread(r)
	//	    th.setName("ngram-proc-thread-" +
	//	    counter.getAndIncrement.toString)
	//	    th.setDaemon(true)
	//	    th
	//	  }
	//	}
	//)

  val preProcessingThreadPool = Executors.newFixedThreadPool(2)
  val streamingDownloadThreadPool = Executors.newFixedThreadPool(1)

  // separate from the processing thread, this one downloads
	implicit val ec = ExecutionContext.fromExecutor(new ForkJoinPool())

  def writeStats(dir: Path, word: String, follow: Map[String, Integer]): Unit = {
    val out: File = Paths.get(dir.toAbsolutePath().toString(), word).toFile
    out.getParentFile.mkdirs

    logger.debug(s"Writing index for word ${word} to ${out.getAbsolutePath()}")

    val writer = new PrintWriter(new BufferedWriter(new FileWriter(out)))

    follow.foreach { entry =>
      writer.println(s"${entry._1} ${entry._2}")
    }

    writer.close()
  }

  def readableWord(word: String): String = {
    val cleaner = if (word.toUpperCase().equals(word)) word.toLowerCase() else word
    return cleaner.split("_")(0)
  }

  // not tail recursive: we expect input string to always have fewer \t's than the JVM has stack frames
  def indicesOf(str: String, sep: Char): List[Integer] = {

    def index0(str: String, ix: Integer): List[Integer] = {
      if (str.isEmpty) List[Integer]()
      else if (str.head == sep) ix :: index0(str.tail, ix + 1)
      else index0(str.tail, ix + 1)
    }

    index0(str, 0)
  }

  def fromSplit(word: String, begX: Integer, endX: Integer): String = {
    val sub = word.substring(begX, endX)
    if (sub.contains("_")) {
      var split = sub.split("_")
      if (!split.isEmpty) return split(0)
    }
    sub
  }

  // return: firstword, secondword, ct
  def lineProcess(cached: (String, Integer)): Option[(String, String, Integer)] = {

    val line = cached._1
    indicesOf(line, '\t') match {
      case gramIx :: yearIx :: countIx :: _ => {
        val gram = line.substring(0, gramIx)

        indicesOf(gram, ' ') match {
          case oneIx :: twoIx :: _ => {
            val one = fromSplit(gram, 0, oneIx)
            val two = fromSplit(gram, oneIx+1, twoIx)

            if (! List(one,two).forall { Pattern.matches("^[A-Za-z0-9][A-Za-z0-9.]+", _) }) None
            else Some((one, two, line.substring(yearIx+1, countIx).toInt))
          }
          case _ => None
        }
      }
      case _ => None
    }
  }

  def reduceCache(recorder: Recorder): CacheReduction = {
    try {
      if (recorder.cache.isEmpty) throw new IllegalStateException("Got recorder that was empty and shouldn't have been")

      val first = {
        val top = recorder.cache.head
        fromSplit(top._1, 0, top._2)
      }

      val reduced = recorder.cache.map(lineProcess).flatten.foldLeft(Map[String, Integer]()) {
        case (m: Map[String, Integer], t: (String, String, Integer)) => {
          val recorded: Integer = m.getOrElse(t._2, 0)
          val ct = t._3 + recorded
          m + (t._2 -> ct)
        }
      }
      // very noisy
      // if (!reduced.isEmpty) logger.debug(s"Word '${first}', reduced index: ${reduced}")
      CacheReduction(recorder.cache.size, reduced.size, first, reduced)
    } catch {
      case exc: Exception => {
        exc.printStackTrace
        throw new RuntimeException("Error processing distributed reduction", exc)
      }
    }
  }

  def collect(dir: Path, uri: URI): Boolean = {
      var cacheReductions = List[CacheReduction]()

      // we'll have one collect method per dl actor (which are pooled), then
      // that will use a singleton recorder instance that has its own futures -
      // based reducers. The final reduction will be single-threaded here again
      // and we'll reuse the reducer pool to do concurrent writes.
      var recorder = new Recorder()

      // used for side effects, a little ugly
      def appendReduction(): Unit = {
        cacheReductions = reduceCache(recorder) :: cacheReductions
      }

			logger.info(s"Issuing request to ${uri.toString}")

      val outStr = new PipedOutputStream()
      val queue = new java.util.concurrent.ConcurrentLinkedQueue[java.lang.String]()

			val bufSize = 9100

			val response = HttpClients.createDefault().execute(new HttpGet(uri.toURL.toString))
			val entity = response.getEntity
			val instream = entity.getContent
			val cLength = entity.getContentLength

			preProcessingThreadPool.execute(new PreProcessor(outStr, uri, bufSize, cLength, queue))

      streamingDownloadThreadPool.execute(new Runnable {
    	  val buffer = new Array[Byte](bufSize)

        def run() {
          try {
			      logger.info(s"Locally caching data from ${uri.toString}")
			      var xferred: Long = 0
			      while (xferred < cLength) {
              val read = instream.read(buffer)

			      	if (xferred % (1024*1024*512) == 0) logger.info(s"Read from HTTP stream ${xferred} bytes so far of ${cLength} for ${uri.toString}")
              outStr.write(buffer, 0, read)
              xferred += read
			      }

          } catch {
			    	case e: Exception => logger.error(e.toString)
			    } finally {

			      instream.close()
			      outStr.close()
			      response.close()
			    }
        }
      })

      val reduce = Future {
        logger.info(s"Beginning line stream consumption of preprocessed data from ${uri.toString}")

        // Stream.continually(queue.take()).foreach(line => {  })

        logger.info(s"Finished reduction of data from ${uri.toString}")

        true
      }

      Await.result(reduce, Duration.Inf)
	}
}


//					Stream.continually(instream.readLine).takeWhile(_ != null).foreach(line => {
          // We're dealing with indices in the line string not words b/c I gamble that it's more performant;
          // we'll leave the chopping out of substrings to the parallelized functions later

						//indicesOf(line, '\t') match {
          	//  case Nil => logger.error(s"Malformed line in input w/r/t stats: ${line}")
          	//  case ix1 :: _ => {
          	//    val grams = line.substring(0, ix1)

          	//    // it's a legitimate line w/r/t stats, separate the grams now
          	//    indicesOf(grams, ' ') match {
          	//      case Nil => logger.error(s"Malformed line in input w/r/t grams: ${grams}")
          	//      case wx1 :: _ => {
          	//        recorder.record(line, wx1) match {
          	//          case Some(_) => {
          	//            appendReduction()
          	//            recorder = new Recorder(recorder.prev)
          	//          }
          	//          case _ => {}
          	//        }
          	//      }
          	//    }
          	//  }
          	//}
 //     		})

  //    		// handle the last line case, it may not have triggered a recorder match
  //    		if (! recorder.cache.isEmpty) appendReduction()

  //    		val results = Future.sequence(cacheReductions)

  //    		Await.ready(results, Duration.Inf)

  //    		// TODO: could use some untangling
  //    		results.onComplete {
  //    		  case Success(result) => {
  //    		    result.foreach { c: CacheReduction =>
  //    		      // TODO: send stats back; also combine with below
  //    		      logger.debug(s"Cache reduction success; processed line count: ${c.processed}, indexed: ${c.indexed}")
  //    		    }

  //    		    // do the last, group reduction
  //    		    result.foldLeft(Map[String, Map[String, Integer]]()) {
  //    		      case (ms: Map[String, Map[String, Integer]], cr: CacheReduction) => {
  //    		        if (cr.second.isEmpty) ms
  //    		        else {
  //    		          val storedSecond: Map[String, Integer] = ms.getOrElse(cr.seed, Map())
  //    		          ms + (cr.seed -> (storedSecond ++ cr.second))
  //    		        }
  //    		      }
  //    		    }.foreach({
  //    		      case (seed, follow) => {
  //    		        Future {
  //    		          writeStats(dir, seed, follow)
  //    		        }
  //    		      }
  //    		      case _ => {}
  //    		    })
  //    		  }
  //    		  case Failure(error) => {
  //    		    logger.error(s"Write cache failure", error)
  //    		  }
  //    		}
//      // generalized for all gram sizes
//      val inputStream = new BufferedReader(new InputStreamReader(uri.toURL().openStream(), "UTF-8"))
//      Stream.continually(inputStream.readLine).takeWhile(_ != null).foreach(line => {
//
//      // after demo, can replace this with existing index check

class Recorder(var prev: Option[(String, Integer)], var cache: List[(String, Integer)], var exhausted: Boolean) extends StrictLogging {

  def this() {
    this(None, List[(String, Integer)](), false)
  }

  def this(prev: Option[(String, Integer)]) {
    this()
    this.prev = prev

    prev match {
      case Some((line, wix)) => {
        cache = (line, wix) :: cache
      }
      case None => {
        throw new IllegalStateException("Constructor requires a value for prev")
      }
    }
  }

  def record(line: String, ix: Integer): Option[List[(String, Integer)]] = {
    if (exhausted) throw new IllegalStateException("Recorder already exhausted")

    val oprev = prev
    prev = Some((line, ix))

    oprev match {
      case Some((pline, px)) => {
        if ((px != ix) || (pline.substring(0, px) != line.substring(0, ix))) {
          // first word must have changed: send line cache to executor for printing and reset state
          exhausted = true
          return Some(cache)
        }
      }
      case _ => {}
    }

    cache = (line, ix) :: cache
    return None
  }
}

case class CacheReduction(processed: Integer, indexed: Integer, seed: String, second: Map[String, Integer])

