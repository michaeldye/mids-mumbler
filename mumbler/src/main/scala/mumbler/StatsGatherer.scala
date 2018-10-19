package mumbler

import scala.annotation.tailrec
import scala.util.Random
import scala.concurrent.duration._
import scala.collection.SortedSet


import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable}
import akka.stream.actor.ActorPublisher
import mumbler.transport.Messages.{StatsResult, StatsRequest, StatsResponse, Indexed}
import spray.json._

import com.typesafe.scalalogging.StrictLogging

// for ec
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * @author mdye
 */
class StatsGatherer()(implicit val remotes: Seq[ActorRef]) extends Actor with ActorPublisher[Summary] with ActorLogging {

  val stats = new StatsCache(self, remotes)
  context.system.scheduler.schedule(300.milliseconds, 10.seconds)(stats.all(StatsRequest()))

  override
  def receive = {
    case response: StatsResponse => {
      log.debug(s"Received a response from stats remote: ${response.result.mkString(" ")}")

      stats.record(sender.toString, response)

      val recentSummary = stats.summarizeRecent()
      log.info(s"Prepared stats summary: ${recentSummary}")
      onNext(recentSummary)
    }
  }

}

  // a helper class from before we made the owning ActorPublisher that does some of this work
class StatsCache(val sender: ActorRef, val cluster: Seq[ActorRef]) extends StrictLogging {

  // these responses are time-series data so we probably want to replace
  // this local RAM storage with something more durable and fit for purpose

  // an immutable data structure with a var; great for acting like clojure
  // w/r/t safe but possibly stale data among multiple users
  var store = Map[String, Seq[StatsResult]]()

  def all(request: StatsRequest): Unit = {
			cluster.foreach(_.tell(request, sender))
  }

  def record(sender: String, response: StatsResponse): Unit = {

    response.result match {
      case Some(r: StatsResult) => {
				logger.info(s"Received stats result from ${sender}: ${r}")
				val existing = store.getOrElse(sender, Seq.empty[StatsResult])
				store = store + (sender -> (existing :+ r))
			}
      case None => {
        logger.warn(s"Empty stats response from ${sender}")
      }
    }
  }

  def summarizeRecent(): SummaryOfMostRecent = {
		// end is a sequence b/c we want to include different statsresults (like Index stats, other ...)
		val summarized = store.foldLeft(Map.empty[String, Seq[StatsResult]])( (acc, rec) => {

			// want only newest of each type of StatsResult; since we only have one type now this amounts to
			// getting the last of the seq

			rec match {
				case (k: String, v: Seq[StatsResult]) => {
					val recent = v.reduceLeft( (l,r) => {
						if (l.ts > r.ts) l
						else r
					})

					logger.debug(s"recent: ${recent}")
					val existing = acc.getOrElse(k, Seq[StatsResult](recent))
					acc + (k -> existing)
				}
				case _ =>  throw new IllegalStateException(s"Not expecting rec shape: ${rec}")
			}
		})
		SummaryOfMostRecent(summarized)
  }
}

object StatsCache {
	def apply(sender: ActorRef, cluster: Seq[ActorRef]): StatsCache = {
		return new StatsCache(sender, cluster)
	}
}


trait Summary

case class SummaryOfMostRecent(remotes: Map[String, Seq[StatsResult]]) extends Summary {
  override
  def toString(): String = s"remotes: ${remotes}"
}

trait SummaryJsonSupport extends akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
  with spray.json.DefaultJsonProtocol {

	implicit val statsResultFormat: JsonFormat[StatsResult] = new JsonFormat[StatsResult] {
		override
		def write(r: StatsResult): JsValue = r match {
			case r: Indexed => JsObject(Map(
				"total_processed" -> JsNumber(r.totalProcessed),
				"total_indexed" -> JsNumber(r.totalIndexed),
				"total_index_millis" -> JsNumber(r.totalIndexMillis),
			))
			case u: Any => throw new IllegalArgumentException(s"Unknown type to serialize: ${u}")
		}

    override
    def read(json: JsValue): StatsResult = {
      throw new UnsupportedOperationException("Not yet completed")
    }
	}

	implicit val summaryFormat: JsonFormat[Summary] = new JsonFormat[Summary] {
		override
		def write(s: Summary): JsValue = s match {
			case r: SummaryOfMostRecent => r.toJson
			case u: Any => throw new IllegalArgumentException(s"Unknown type to serialize: ${u}")
		}

    override
    def read(json: JsValue): Summary = {
      throw new UnsupportedOperationException("Not yet completed")
    }
	}

  implicit val summaryOfMostRecentFormat: JsonFormat[SummaryOfMostRecent] = jsonFormat1(SummaryOfMostRecent)
}
