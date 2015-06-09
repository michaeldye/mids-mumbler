package mumbler.remote

import java.nio.file.Paths
import akka.actor.Actor
import mumbler.transport.Messages._
import akka.actor.Props
import akka.actor.ActorSystem
import java.net.URI
import akka.event.Logging
import org.slf4j.LoggerFactory
import akka.actor.ActorLogging

/**
 * @author mdye
 */
object Listener extends App {
  val agent = ActorSystem("RemoteMumbler").actorOf(Props[Agent], name="Agent")
}

class Agent extends Actor with ActorLogging {
  val hostname = if (System.getenv("HOSTNAME") != null) System.getenv("HOSTNAME") else System.getProperty("akka.remote.netty.tcp.hostname")
  val dir = Paths.get("/gpfs", "gpfsfpo", hostname)
 
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
          log.info(s"Mumbling starting with ${request.arg}")
          val followers: Option[Map[String, Int]] = Searcher.findFollowing(dir, request.arg)
          sender ! Response(request.cmd, request.arg, followers)
        
        case _ =>
          log.info(s"Unexpected command: ${request.cmd}")
      }
   }
}