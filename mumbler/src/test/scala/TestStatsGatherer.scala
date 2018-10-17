import collection.mutable.Stack
import org.scalatest._
import java.net.URI
import java.nio.file.Paths

import akka.actor.{ Actor, ActorRef, Props, ActorSystem }
import akka.testkit.{ ImplicitSender, TestKit, TestActorRef, TestProbe }

import scala.io.Source

import mumbler.transport.Messages.{StatsResponse, Indexed}
import mumbler.StatsCache

class StatsGathererSpec(_system: ActorSystem)
  extends TestKit(_system)
  with Matchers
  with FlatSpecLike
  with BeforeAndAfterAll {

  def this() = this(ActorSystem("StatsGathererSpec"))

  override def afterAll: Unit = {
    shutdown(system)
  }

  "summarize" should "provide most recent totals" in {
    val actorOne = system.actorOf(Props.empty)
    val actorTwo = system.actorOf(Props.empty)

    // the first arg's value is arbitrary
    val stats = StatsCache(actorOne, Seq[ActorRef](actorOne, actorTwo))

    for(i <- List(Indexed(1000, 10, 159015), Indexed(1200, 20, 165895), Indexed(1100, 15, 161222))) {
      stats.record(actorOne.toString, StatsResponse(Some(i)))
    }

    for(i <- List(Indexed(900, 12, 156017), Indexed(1500, 33, 162201), Indexed(700, 5, 151010))) {
      stats.record(actorTwo.toString, StatsResponse(Some(i)))
    }

    val summary: mumbler.SummaryOfMostRecent = stats.summarizeRecent()
    val flattened = summary.remotes.values.flatten.toList
    assert(flattened.size == 2)

    // important that the most recently added records are summarized and other values do not determine ordering
    assert(flattened.contains(Indexed(700, 5, 151010)))
    assert(flattened.contains(Indexed(1100, 15, 161222)))
  }
}
