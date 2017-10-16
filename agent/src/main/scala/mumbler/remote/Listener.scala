package mumbler.remote

import java.nio.file.Paths

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorSystem
import akka.actor.Props
import mumbler.transport.Messages.Download
import mumbler.transport.Messages.Mumble
import mumbler.transport.Messages.Request
import mumbler.transport.Messages.Response

/**
 * @author mdye
 */
object Listener extends App {
  val agent = ActorSystem("RemoteMumbler").actorOf(Props[Agent], name="Agent")
}

class Agent extends Actor with ActorLogging {
  val hostname = if (System.getenv("HOSTNAME") != null) System.getenv("HOSTNAME") else System.getProperty("akka.remote.netty.tcp.hostname")
  val dir = Paths.get(System.getenv("DATADIR"))

  override
  def receive = {
    case dl: Download =>
      log.info(s"Received dl '$dl'")

      if (Writer.preprocess(dir, dl.target)) {
        sender ! s"processed ${dl.target}"
      } else sender ! s"skipped preprocessing ${dl.target}, file already exists"
    case request: Request =>
      log.info(s"Received request '$request'")

      request.cmd match {
        case Mumble =>
          val word = request.chain.last
          log.info(s"Mumbling starting with ${word}")
          val followers: Option[Map[String, Int]] = Searcher.findFollowing(dir, word)
          sender ! Response(request.cmd, request.chain, followers)

        case _ =>
          log.info(s"Unexpected command: ${request.cmd}")
      }
   }
}
