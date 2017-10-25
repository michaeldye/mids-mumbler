package mumbler

import java.net.URI



import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import mumbler.transport.Messages._

class Downloader(val filesMax: Int, val apiFn: (Int) => Unit, val remotes: Seq[ActorRef]) extends Actor with ActorLogging {

  log.info(s"Preprocessing ${filesMax} source data files")

  val last = if (filesMax > 100) 100 else filesMax

  var fileSuccessCount = 0

  (0 until last).map(ix => {
    val message = Download(new URI(s"http://storage.googleapis.com/books/ngrams/books/googlebooks-eng-us-all-2gram-20090715-${ix}.csv.zip"))

      val clusterAssignment = (ix % remotes.size)
      log.info(s"sending dl ${message} to ${clusterAssignment}")
      remotes(clusterAssignment).tell(message, self)
  })

  def receive = {
    case report: Report => {
      log.info(s"Received report $report from agent: $sender")

      if (report.success) {
        fileSuccessCount+=1

        apiFn(fileSuccessCount)

      } else {
        // TODO: do a requeue here eventually
        log.error(s"Failure to download ${report.target}")
      }
    }
    case msg: String => log.info(s"Received message: '$msg' from $sender")
  }
}
