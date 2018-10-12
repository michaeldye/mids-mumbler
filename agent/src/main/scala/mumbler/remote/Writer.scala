package mumbler.remote

import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.io.BufferedWriter
import java.io.BufferedInputStream
import java.io.PrintWriter
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

import scala.util.Success
import scala.util.Failure
import scala.concurrent._
import scala.concurrent.duration.DurationInt
import java.util.concurrent.Executors

import scala.annotation.tailrec
import com.typesafe.scalalogging.StrictLogging

import org.apache.http.HttpEntity;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
/**
 * @author mdye
 */
object Writer extends StrictLogging {

  // a bit loose right now, we're probably bound by i/o before cpu with the writer's work
  val numWorkers = sys.runtime.availableProcessors
  val pool = Executors.newFixedThreadPool(numWorkers)

  implicit val ec = ExecutionContext.fromExecutorService(pool)

  def writeStats(dir: Path, word: String, follow: Map[String, Integer]): Unit = {
    val out: File = Paths.get(dir.toAbsolutePath().toString(), word).toFile
    out.getParentFile.mkdirs

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

  def fromSplit(word: String, begX: Integer, endX: Integer) = word.substring(begX, endX).split("_")(0)

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

  def reduceCache(recorder: Recorder): Future[CacheReduction] = {
    Future {

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

      if (!reduced.isEmpty) logger.debug(s"For word '${first}', would write: ${reduced}")
      CacheReduction(recorder.cache.size, reduced.size, first, reduced)
    }
  }

  def collect(dir: Path, uri: URI): Boolean = {
      var cacheReductions = List[Future[CacheReduction]]()

      // we'll have one collect method per dl actor (which are pooled), then
      // that will use a singleton recorder instance that has its own futures -
      // based reducers. The final reduction will be single-threaded here again
      // and we'll reuse the reducer pool to do concurrent writes.
      var recorder = new Recorder()

      // used for side effects, a little ugly
      def appendReduction(): Unit = {
        cacheReductions = reduceCache(recorder) :: cacheReductions
      }

      // generalized for all gram sizes

		  val response = HttpClients.createDefault().execute(new HttpGet(uri.toURL().toString()))
      val entity = response.getEntity()
      val cLength = entity.getContentLength()
      val instream = entity.getContent()

      try {
      val inputStream = new BufferedReader(new InputStreamReader(instream, "UTF-8"))
      Stream.continually(inputStream.readLine).takeWhile(_ != null).foreach(line => {
          // We're dealing with indices in the line string not words b/c I gamble that it's more performant;
          // we'll leave the chopping out of substrings to the parallelized functions later

          indicesOf(line, '\t') match {
            case Nil => logger.error(s"Malformed line in input w/r/t stats: ${line}")
            case ix1 :: _ => {
              val grams = line.substring(0, ix1)

              // it's a legitimate line w/r/t stats, separate the grams now
              indicesOf(grams, ' ') match {
                case Nil => logger.error(s"Malformed line in input w/r/t grams: ${grams}")
                case wx1 :: _ => {
                  recorder.record(line, wx1) match {
                    case Some(_) => {
                      appendReduction()
                      recorder = new Recorder(recorder.prev)
                    }
                    case _ => {}
                  }
                }
              }
            }
          }
      })

      } catch {
        case ex: Exception => logger.error("Error reading and processing stream", ex)
        case th: Throwable => logger.info("Thrown while processing", th)

      } finally {
        response.close();
      }

      // handle the last line case, it may not have triggered a recorder match
      if (! recorder.cache.isEmpty) appendReduction()

      val results = Future.sequence(cacheReductions)

      // TODO: could use some untangling
      results.onComplete {
        case Success(result) => {
          result.foreach { c: CacheReduction =>
            // TODO: send stats back; also combine with below
            logger.info(s"Cache reduction success; processed line count: ${c.processed}, indexed: ${c.indexed}")
          }

          // do the last, group reduction
          result.foldLeft(Map[String, Map[String, Integer]]()) {
            case (ms: Map[String, Map[String, Integer]], cr: CacheReduction) => {
              if (cr.second.isEmpty) ms
              else {
                val storedSecond: Map[String, Integer] = ms.getOrElse(cr.seed, Map())
                ms + (cr.seed -> (storedSecond ++ cr.second))
              }
            }
          }.foreach({
            case (seed, follow) => {
              Future {
                writeStats(dir, seed, follow)
              }
            }
            case _ => {}
          })
        }
        case Failure(error) => {
          logger.info(s"Write cache failure: ${error}")
        }
      }

      // after demo, can replace this with existing index check
      true
  }
}

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

