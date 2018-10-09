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

import org.apache.http.client.fluent.Request

/**
 * @author mdye
 */
object Writer extends StrictLogging {

  // a bit loose right now, we're probably bound by i/o before cpu with the writer's work
  val numWorkers = sys.runtime.availableProcessors
  val pool = Executors.newFixedThreadPool(numWorkers)

  implicit val ec = ExecutionContext.fromExecutorService(pool)

  def writeStats(dir: Path, word: String, follow: Map[String, Int]): Unit = {
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

  /**
   * TODO: make this function take a (firstWord: String, secondWordPlusMeta: Map[String, String]) which should
   * open the output writer first and then call another function that does the reduce (using lots of the code
   * below), returns lines to write and this function calls the printwriter.
   *
   * TODO: Make sure this can be handled by a worker in a thread pool. Don't bother with actors at first
   * b/c we don't want to split the inputs up into reasonable message sizes
   */


//  def collapseEntries(lines: List[String]): Map[String, Int] = {
//    lines.map(_.split("\\t")).foldLeft(scala.collection.mutable.Map.empty[String,Int])((collected, el) => {
//      val two = el.slice(0, 2)
//      if (! two.forall { Pattern.matches("^[A-Za-z0-9][A-Za-z0-9_]*", _) }) collected
//      else {
//        val first = readableWord(two.head)
//        val add = el(4).toInt
//
//        val second = readableWord(two.last)
//        collected += collected get second match {
//          case Some(num) => (second -> num + add)
//          case None => (second -> add)
//        }
//      }
//    }).toMap
//  }

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

  def collect(dir: Path, uri: URI): Unit = {
    // TODO: add pool here and execution context so we can fire off write process w/ future
      var cacheReductions = List[Future[CacheReduction]]()

      // we'll have one collect method per dl actor (which are pooled), then
      // that will use a singleton recorder instance that has its own futures -
      // based writers
      var recorder = new Recorder()

      // used for side effects, a little ugly
      def appendReduction(): Unit = {
        cacheReductions = reduceCache(recorder) :: cacheReductions
      }

      // generalized for all gram sizes
      val inputStream = new BufferedReader(new InputStreamReader(uri.toURL().openStream(), "UTF-8"))
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

      // handle the last line case, it may not have triggered a recorder match
      if (! recorder.cache.isEmpty) appendReduction()

      val results = Future.sequence(cacheReductions)

      Await.ready(results, 10.seconds)
      // all should be done now

      results.onComplete {
        case Success(result) => {
          result.foreach { c: CacheReduction =>
            logger.info(s"Cache reduction success; processed line count: ${c.processed}, indexed: ${c.indexed}")
          }

          // TODO: need to do last reduce step and then send each reduced cache to a writer

        }
        case Failure(error) => {
          logger.info(s"Write cache failure: ${error}")
        }
      }

  }

  /**
   * TODO: make this function open the file stream then do the split and take the first two words
   * initially, comparing that (maybe taking just the length and matching subsequent
   * lines that way?) with later lines. While the substring is the same, gather rest of the line in iterable.
   * When the substring no longer matches, slap that iterable in a map with the second word as the key and
   * continue to process just first word matches, gathering up the same second words. Every time the
   * first word changes, send that in a queue to a separate thread that can: 1) reduce the counts, 2) write the
   * file named for the first word (collapseEntries is the start)
   *
   */
//  def preprocess(dir: Path, uri: URI): Boolean = {
//    val to_dl = new File(dir.toFile, uri.getPath.split('/').last)
//
//    if (to_dl.exists) false
//    else {
//
//      var word = ""
//      var follow = Map[String, Int]()
//
//      val inputStream = new BufferedReader(new InputStreamReader(uri.toURL().openStream(), "UTF-8"))
//
//      Stream.continually(inputStream.readLine).takeWhile(_ != null).
//        map { _.split("\\t") }.
//        foreach(words => {
//          logger.debug(s"Words: ${words}")
//
//          val two = words.slice(0, 2)
//          // process only if both first two words match pattern
//          if (two.forall { Pattern.matches("^[A-Za-z0-9][A-Za-z0-9_]*", _) }) {
//            logger.debug(s"Processing words that match filter: ${two}")
//
//            val first = readableWord(words(0))
//
//            if (first != word) {
//              // new first word
//
//              if (word != "")
//                writeStats(dir, word, follow)
//
//              word = first
//              follow = Map[String, Int]()
//            } else {
//              // N.B. the index in "words" picks out the count, which for
//              // 3-gram (<word> <word2> <word3> <year> <ct> ...) or index 4
//
//              logger.debug(s"Count: ${words(4)}")
//
//              val add = words(4).toInt + 1
//
//              val second = readableWord(words(1))
//              val ct = follow get second match {
//                case Some(num) => num + add
//                case None => add
//              }
//              follow = follow + (second -> ct)
//            }
//
//          } else {
//            logger.debug(s"Rejecting ${words}")
//          }
//        })
//
//      // touch the downloaded file name to signal we have processed it
//      to_dl.createNewFile
//
//      // TODO: replace this
//      // writeStats(dir, word, follow)
//      true
//    }
//  }
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

