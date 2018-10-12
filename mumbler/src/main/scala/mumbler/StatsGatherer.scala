package mumbler

import scala.annotation.tailrec
import scala.util.Random
import scala.concurrent.duration._

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable}
import akka.stream.actor.ActorPublisher
import mumbler.transport.Messages.StatsRequest
import mumbler.transport.Messages.StatsResponse

// for ec
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * @author mdye
 */
class StatsGatherer()(implicit val remotes: Seq[ActorRef]) extends Actor with ActorPublisher[String] with ActorLogging {

  val pull = new Pull(self, remotes: _*)
  val pullPoll: Cancellable = context.system.scheduler.schedule(300.milliseconds, 10.seconds)(pull.all(StatsRequest()))

  override
  def receive = {
    case response: StatsResponse => {
      log.debug(s"Received response from stats remote: ${response.result.mkString(" ")}")

      // evaluate nodes' responses
      pull.gather(sender, response) match {
        case sm: Seq[_] => {
          log.info(s"stats responses: $sm")
          onNext(sm.mkString)
        }
      }
    }
  }

  // a helper class from before we made the owning ActorPublisher that does some of this work
  class Pull(val sender: ActorRef, val cluster: ActorRef*) {
    var responseRecorder = Map[ActorRef, Option[StatsResponse]]()

    def all(request: StatsRequest): Unit = {
      // side-effect: hoses whatever old response recording there was

      responseRecorder = cluster.map(node => {
        node.tell(request, sender) // side effect: sends message to each node
        (node -> None)
      }).toMap
    }

    def gather(sender: ActorRef, response: StatsResponse): Iterable[Map[String, Int]] = {
      if (responseRecorder.contains(sender)) responseRecorder = responseRecorder.updated(sender, Some(response))

      val cmap = responseRecorder.values

      if (cmap.flatten.size != cmap.size) log.info(s"Did not receive stats response from all remote nodes")

      cmap.collect { case Some(StatsResponse(Some(m))) => m }
    }
  }
}
