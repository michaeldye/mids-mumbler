package mumbler.remote

import java.nio.file.Path
import java.nio.file.Paths
import java.net.URI
import java.util.concurrent.TimeUnit

import scala.util.{Success, Failure}
import scala.io.Source
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.Await

import akka.actor.{Actor,ActorRef}
import akka.actor.ActorLogging
import akka.actor.ActorSystem
import akka.actor.Props
import mumbler.transport.Messages._

import scala.concurrent.duration.Duration
import akka.util.Timeout

// fine b/c the only async stuff we process here is waiting on future results from delegates
import ExecutionContext.Implicits.global

/**
 * @author mdye
 */
object Listener extends App {
  ActorSystem("RemoteMumbler").actorOf(Props[Agent], name="Agent")
}


class StatsAgg() {
  var indexed: Indexed = Indexed.empty

  def add(incoming: Indexed): Unit = {
    indexed = new Indexed(
      indexed.totalProcessed + incoming.totalProcessed,
      indexed.totalIndexed + incoming.totalIndexed,
      indexed.totalIndexMillis + incoming.totalIndexMillis
    )
  }
}

class Agent extends Actor with ActorLogging {
  val dir = Paths.get(System.getenv("DATADIR"))
	val fetcher = context.actorOf(Props[Fetcher].withDispatcher("dl-dispatcher"), name="Fetcher")

  val badwordsPath = Paths.get(dir.toString(), "badwords.txt").toFile

  val badwords = {
    if (badwordsPath.exists) {
      log.info(s"Loading bad words list from ${badwordsPath}")
      Source.fromFile(badwordsPath).getLines.toList
    } else List()
  }

  var statsAggregator = new StatsAgg

  val searcher = new Searcher(badwords)

  override
  def receive = {
    case dl: Download =>
      log.info(s"Received dl '$dl'")
			fetcher ! Process(dir, dl.target, sender)

    case request: Request =>
      log.info(s"Received request '$request'")

      request.cmd match {
        case Mumble =>
          val word = request.chain.last
          log.info(s"Mumbling starting with ${word}")
          val followers: Option[Map[String, Int]] = searcher.findFollowing(dir, word)
          sender ! Response(request.cmd, request.chain, followers)

        case _ =>
          log.info(s"Unexpected command: ${request.cmd}")
      }

      case statsRequest: StatsRequest =>
        log.info(s"Received stats request")
        sender ! StatsResponse(Some(statsAggregator.indexed))

      case report: Report =>
        log.info(s"Received process report: $report")

      case stats: ProcessingStats =>
        statsAggregator.add(stats.indexingStats)
   }
}

class Fetcher extends Actor with ActorLogging {

	override
	def receive = {
		case process: Process =>
			log.info(s"Received process $process")
      // will always write index files; doesn't skip already-processed ones like before (demo feature)

      // timing is not total compute time but wall time incl. downloads and concurrent work
      val start = System.currentTimeMillis
      val indexStats = Writer.collect(process.dir, process.target)
      val end = (System.currentTimeMillis - start)

      indexStats match {
        case (totalProcessed, totalIndexed, writeOperations) => {
          sender ! ProcessingStats(s"Indexed content", Indexed(totalProcessed, totalIndexed, end))
          process.origin ! Report(s"Fetched and preprocessed content", true, process.target)

          // for now we don't react to failed writes on remotes but we could send a message to the head to have a node refetch or try again here
          writeOperations.onComplete {
            case Success(_) => log.info(s"All write operations for target ${process.target} completed successfully")
            case Failure(t) => log.error(s"Write failure", t)
          }
        }
      }
	}
}

case class ProcessingStats(msg: String, indexingStats: Indexed)

case class Process(dir: Path, target: URI, origin: ActorRef)
