package mumbler.remote

import java.nio.file.Path
import java.nio.file.Paths
import java.net.URI
import java.util.concurrent.TimeUnit

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

/**
 * @author mdye
 */
object Listener extends App {
  ActorSystem("RemoteMumbler").actorOf(Props[Agent], name="Agent")
}

class Agent extends Actor with ActorLogging {
  val dir = Paths.get(System.getenv("DATADIR"))

  // TODO: raise exception if missing
  // val apiHost = System.getenv("API_HOST")
  // val apiPort = System.getenv("API_PORT")

  // val duration = Duration.create(30, TimeUnit.SECONDS)
  // implicit val timeout = Timeout(duration)
  // val api = Await.result(context.actorSelection(s"akka.tcp://Mumbler@${apiHost}:${apiPort}/user/Downloader").resolveOne(), duration)

	val fetcher = context.actorOf(Props[Fetcher].withDispatcher("dl-dispatcher"), name="Fetcher")

  val badwordsPath = Paths.get(dir.toString(), "badwords.txt").toFile()
  val badwords = Source.fromFile(badwordsPath).getLines.toList
  val searcher = new Searcher(badwords)

  log.info(s"Bad words list loaded from ${badwordsPath}")

  override
  def receive = {
    case dl: Download =>
      log.info(s"Received dl '$dl'")

      // TODO: need to make a pool of actors on this remote that can each take a DL filename and do the fetch,
      // write to disk, and then send a message back to this supervisor to have it processed. Want to avoid
      // processing arbitrary sizes of downloaded data b/c then we can't be sure we are gathering all matching
      // words; it's better if the whole file can be downloaded and written to disk (max network IO this way)

      // TODO: upon completion of indexing, delete the original file and send a message with some stats back
      // to the API actor for presentation on the page

//      if (Writer.preprocess(dir, dl.target)) sender ! Report(s"Fetched and preprocessed content", true, dl.target)
//      else sender ! Report(s"Skipped preprocessing, file already exists", true, dl.target)

			fetcher ! Process(dir, dl.target)

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

      case report: Report =>
        log.info(s"Received process report: $report")
   }
}

class Fetcher extends Actor with ActorLogging {

  // implicit val executionContext = context.system.dispatchers.lookup("dl-dispatcher")

	override
	def receive = {
		case process: Process =>
			log.info(s"Received process $process")
      Thread.sleep(5000)
      // context.system.scheduler.scheduleOnce(new FiniteDuration(1, TimeUnit.SECONDS), sender, Report(s"Fetched and preprocessed content", true, process.target))
      sender ! Report(s"Fetched and preprocessed content", true, process.target)
	}
}

case class Process(dir: Path, target: URI)
