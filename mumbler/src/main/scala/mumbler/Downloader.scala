package mumbler

import java.net.URI



import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import mumbler.transport.Messages._

// filesCt needs to be the quantity of files to-be-downloaded; it's expected they're sequential and numbered from 0
class Downloader(val filesCt: Int, val apiFn: (Int) => Unit, val remotes: Seq[ActorRef]) extends Actor with ActorLogging {

  log.info(s"Preprocessing ${filesCt} source data files")

  var fileSuccessCount = 0
  val uriBase = sys.env("FETCH_PRI_URIBASE")

  (0 until filesCt).map(ix => {
    // TODO: fix path composition
    val message = Download(new URI(s"${uriBase}/googlebooks-eng-us-all-2gram-20090715-${ix}.csv.zip"))

      val clusterAssignment = (ix % remotes.size)
      // log.info(s"sending dl ${message} to ${clusterAssignment}")
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
