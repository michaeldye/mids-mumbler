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

import java.util.zip.ZipInputStream

import scala.annotation.tailrec
import com.typesafe.scalalogging.StrictLogging

import org.apache.http.client.fluent.Request

/**
 * @author mdye
 */
object Writer extends StrictLogging {

  // a bit loose right now, we're probably bound by i/o before cpu with the writer's work
  val numWorkers = sys.runtime.availableProcessors
  val pool = Executors.newFixedThreadPool(numWorkers)

  val legalWord = Pattern.compile("^[A-Za-z][A-Za-z0-9]*")

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

  // not tail recursive: we expect input string to always have fewer \t's than the JVM has stack frames
  def indicesOf(str: String, sep: Char): List[Integer] = {

    def index0(str: String, ix: Integer): List[Integer] = {
      if (str.isEmpty) List[Integer]()
      else if (str.head == sep) ix :: index0(str.tail, ix + 1)
      else index0(str.tail, ix + 1)
    }

    index0(str, 0)
  }

  def readableWord(word: String): String = word.toLowerCase()

  def fromSplit(word: String, begX: Integer, endX: Integer): String = {
    word.substring(begX, endX)
  }

  // return: firstword, secondword, ct
  def lineProcess(cached: (String, Integer)): Option[(String, String, Integer)] = {

    val line = cached._1
    indicesOf(line, '\t') match {
      case gramIx :: yearIx :: countIx :: _ => {
        val gram = line.substring(0, gramIx).split(" ")

        if (gram.size != 2) None
        else {
          val one = gram(0)
          val two = gram(1)
          if (List(one,two).forall { legalWord.matcher(_).matches() }) {
            Some((readableWord(one), readableWord(two), line.substring(yearIx+1, countIx).toInt))
          }
          else None
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
        readableWord(fromSplit(top._1, 0, top._2))
      }

      val reduced = recorder.cache.flatMap(lineProcess).foldLeft(Map[String, Integer]()) {
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

      val legalLine = Pattern.compile("^[ \t\nA-Za-z0-9]*")

      val zin = new ZipInputStream(Request.Get(uri).execute.returnContent.asStream)

      Stream.continually(zin.getNextEntry).takeWhile(_ != null).foreach(entry => {
        val entryBuffer = new BufferedReader(new InputStreamReader(zin, "UTF-8"))
        Stream.continually(entryBuffer.readLine).takeWhile(_ != null).foreach(line => {

          // throw out early
          if (legalLine.matcher(line).matches()) {
            indicesOf(line, '\t') match {
              case Nil => logger.debug(s"Malformed line in input w/r/t stats: ${line}")
              case ix1 :: _ => {
                val grams = line.substring(0, ix1)

                // it's a legitimate line w/r/t stats, separate the grams now
                indicesOf(grams, ' ') match {
                  case Nil => {} // logger.error(s"Malformed line in input w/r/t grams: ${grams}")
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
          } else {
            logger.debug(s"Rejected line: ${line}")
          }
        })
      })

      // handle the last line case, it may not have triggered a recorder match
      if (! recorder.cache.isEmpty) appendReduction()

      val results = Future.sequence(cacheReductions)

      // TODO: could use some untangling
      results.onComplete {
        case Success(result) => {
          result.foreach { c: CacheReduction =>
            // TODO: send stats back; also combine with below
            // logger.debug(s"Cache reduction success; processed line count: ${c.processed}, indexed: ${c.indexed}")
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
          logger.error("Write cache failure", error)
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

