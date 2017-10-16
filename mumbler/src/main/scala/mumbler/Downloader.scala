package mumbler

import java.net.URI
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.util.Timeout
import mumbler.transport.Messages.Control
import mumbler.transport.Messages.Download
import mumbler.transport.Messages.Mumble
import mumbler.transport.Messages.Request
import mumbler.transport.Messages.Response

class Downloader(val filesMax: Int, val remotes: Seq[ActorRef]) extends Actor with ActorLogging {

  log.info(s"Preprocessing ${filesMax} source data files")

  val last = if (filesMax > 100) 100 else filesMax
  (0 until last).map(ix => {
    val message = Download(new URI(s"http://storage.googleapis.com/books/ngrams/books/googlebooks-eng-us-all-2gram-20090715-${ix}.csv.zip"))

      val clusterAssignment = (ix % remotes.size)
      log.info(s"sending dl ${message} to ${clusterAssignment}")
      remotes(clusterAssignment).tell(message, self)
  })

  def receive = {
    case msg: String => log.info(s"Received message: '$msg' from $sender")
  }
}
