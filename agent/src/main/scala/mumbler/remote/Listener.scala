package mumbler.remote

import java.nio.file.Path
import java.nio.file.Paths
import java.net.URI
import java.util.concurrent.ForkJoinPool

import scala.io.Source
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.Await
import scala.util.{Success, Failure}

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
	val fetcher = context.actorOf(Props[Fetcher].withDispatcher("dl-dispatcher"), name="Fetcher")

  val badwordsPath = Paths.get(dir.toString(), "badwords.txt").toFile()
  val badwords = Source.fromFile(badwordsPath).getLines.toList
  val searcher = new Searcher(badwords)

  log.info(s"Bad words list loaded from ${badwordsPath}")

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

      case report: Report =>
        log.info(s"Received process report: $report")
   }
}

class Fetcher extends Actor with ActorLogging {
  implicit val ec = ExecutionContext.fromExecutorService(new ForkJoinPool())

	override
	def receive = {
		case process: Process =>
			log.info(s"Received process $process")
      // will always write index files; doesn't skip already-processed ones like before (demo feature)
      //if (Writer.collect(process.dir, process.target)) process.origin ! Report(s"Fetched and preprocessed content", true, process.target)


      // TODO: this future seems right, but it looks like we'll still get timeouts and there are way too many dl's scheduled at once
      val fut: Future[Boolean] = Writer.collect(process.dir, process.target)
      fut.onComplete({
        case Success(s) => process.origin ! Report(s"Fetched and preprocessed content", true, process.target)
        case Failure(s) => // TODO: requeue?
      });


	}
}

case class Process(dir: Path, target: URI, origin: ActorRef)
