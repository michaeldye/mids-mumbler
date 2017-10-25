package mumbler

import java.util.concurrent.TimeUnit
import java.nio.file.Paths

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.util.Success

import com.typesafe.scalalogging.StrictLogging

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import akka.stream.Graph
import akka.stream.SourceShape
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Timeout

object Launch extends App with StrictLogging {
  implicit val system = ActorSystem("Mumbler")

  if (args.size < 3) {
    Console.err.println("Usage: java -jar <mids_mumbler-assembly...jar> files_max wslistenaddr:wsport remoteaddr:port [[remoteaddr2:port2]...]")
    System.exit(1)
  }

  // process incoming args into actorRefs
  implicit val remotes: Seq[ActorRef] = args.slice(2, args.size).map { ag: String =>
    def resolveRemote(host: String, port: Int): ActorRef = {
      val duration = Duration.create(30, TimeUnit.SECONDS)
      implicit val timeout = Timeout(duration)
      val remote = Await.result(system.actorSelection(s"akka.tcp://RemoteMumbler@${host}:${port}/user/Agent").resolveOne(), duration)
      Console.println(s"Remote: ${remote}")
      remote
    }

    val parts = ag.split(":")
    resolveRemote(parts(0), parts(1).toInt)
  }

  val filesMax = args(0).toInt

  def callback(downloaded: Int): Unit = {
    Console.println(s"Downloaded: ${downloaded}/${}")
    if (downloaded == filesMax) {
      val ws = args(1).split(":")
      val api = new API(ws(0), ws(1).toInt)
      logger.info(s"API listening on ${ws(0)}:${ws(1)}")
    }
  }

  // upon construction will send messages to all remotes to download source files
  system.actorOf(Props(new Downloader(filesMax, callback, remotes)), name = "Downloader")

}

class API(val bindAddress: String, val port: Int)(implicit val system: ActorSystem, implicit val remotes: Seq[ActorRef]) extends Directives with StrictLogging {

  implicit val materializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit val executionContext = system.dispatcher

  val route =
    path("chain" / IntNumber / "seed" / """\w+""".r) { (chainMax, word) =>
      get {
        extractUpgradeToWebSocket { upgrade =>

          // do this before all words are collected b/c we want to publish them on the socket as they arrive
          complete(upgrade.handleMessagesWithSinkSource(Sink.ignore, chainSource(system, remotes, chainMax, word)))
        }
      }
    } ~
    pathPrefix("ui") {
      pathEndOrSingleSlash {
        getFromFile(Paths.get(sys.env("MARKOV_UI"), "index.html").toString())
      } ~
      getFromDirectory(sys.env("MARKOV_UI"))
    }

  val bindingFuture = Http().bindAndHandle(route, bindAddress, port)

  bindingFuture.onComplete {
    case Success(binding) ⇒
      logger.info(s"API server binding complete")
    case Failure(ex) ⇒
      logger.error(s"Binding failed with ${ex.getMessage}")
      throw new IllegalStateException("Failure to bind to configured interface and port", ex)
  }

  def chainSource(system: ActorSystem, remotes: Seq[ActorRef], max: Int, word: String): Graph[SourceShape[Message], Any] = {

    GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val feedSource = Source.actorPublisher(Props(new ChainBuilder(max, word)(remotes)))
      val messager = Flow[String].map(TextMessage(_))
      val stream = feedSource ~> messager

      SourceShape(stream.outlet)
    }
  }
}
