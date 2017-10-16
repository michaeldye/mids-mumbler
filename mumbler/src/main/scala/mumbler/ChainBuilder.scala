package mumbler

import akka.stream.actor.ActorPublisher
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import mumbler.transport.Messages._
import java.net.URI
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorLogging
import akka.actor.ActorRef
import scala.collection.mutable.ListBuffer
import akka.actor.ActorSelection
import java.util.concurrent.TimeUnit
import akka.util.Timeout
import scala.collection.mutable.ListBuffer
import scala.util.Random
import scala.collection.immutable.ListMap
import akka.actor.ActorContext
import akka.event.LoggingAdapter
import scala.annotation.tailrec
import akka.stream.actor.ActorPublisherMessage.Cancel
import scala.collection.mutable

/**
 * @author mdye
 */
class ChainBuilder(val max: Int, val word: String)(implicit val remotes: Seq[ActorRef]) extends Actor with ActorPublisher[String] with ActorLogging {
  
  val mum = new Mumbler(self, remotes: _*)
  
  // start work
  mum.all(Request(Mumble, Seq(word)))

  override
  def receive = {
    case response: Response =>
      log.debug(s"Received response from remote mumbler: ${response.result.mkString(" ")}")

      // update response
      // evaluate nodes' responses
      mum.mumble(sender, response) match {
        case AddToChain(word: String) =>
          log.info(s"$word")

          val chain: Seq[String] = response.chain ++ Seq(word)
          log.debug(s"chain so far: ${chain.mkString(" ")}")

          // publish to consumers
          onNext(word)

          if (chain.length == max) endChain(s"reached requested max chain length, $max", chain)
          else mum.all(Request(Mumble, chain))

        // TODO: fix this so that it sends complete or whatever
        case EndChain => {
          endChain("no following words found", response.chain)
          onComplete()
        }
        case NotAllNodesReported => // continue
      }
  }

  def endChain(reason: String, chain: Seq[String]) {
    log.info(s"Exiting b/c ${reason}")
    log.info(s"Chain: ${chain.mkString(" ")}")
  }

  // a helper class from before we made the owning ActorPublisher that does some of this work
  class Mumbler(val sender: ActorRef, val cluster: ActorRef*) {
    var responseRecorder = Map[ActorRef, Option[Response]]()

    def all(request: Request): Unit = {
      // side-effect: hoses whatever old response recording there was

      responseRecorder = cluster.map(node => {
        node.tell(request, sender) // side effect: sends message to each node
        (node -> None)
      }).toMap
    }

    def mumble(sender: ActorRef, response: Response): ChainResult = {
      if (responseRecorder.contains(sender)) responseRecorder = responseRecorder.updated(sender, Some(response))

      val cmap = responseRecorder.values

      // only continue
      if (cmap.flatten.size == cmap.size) {
        // collect all nodes' response.result Option[Map[String, Int]] maps (like "'word' count") into a single occurrences map and select one to add to the chain

        val occurrences = cmap.collect { case Some(Response(_, response.chain, Some(m))) => m }

        if (!occurrences.isEmpty) {
          val selected = select(occurrences.reduce { (result, map) =>
            map.foldLeft(result) { (r, entry) =>
              val (key, value) = entry
              r.updated(key, r.getOrElse(key, 0) + value)
            }
          })

          AddToChain(selected)

        } else EndChain // signal termination

      } else NotAllNodesReported
    }

    def select(occurrences: Map[String, Int]): String = {
      val sum = occurrences.values.reduce(_ + _)

      @tailrec
      def find(i: BigInt, seq: Seq[(String, Int)]): String = {
        val (word, count) = seq.head

        if (i < 0 || seq.tail.isEmpty) word
        else find(i - count, seq.tail)
      }

      val random = BigInt(Random.nextInt(sum))
      val sorted = occurrences.toSeq.sortWith(_._2 < _._2)
      find(random, sorted)
    }
  }
}

sealed trait ChainResult

case object EndChain extends ChainResult
case object NotAllNodesReported extends ChainResult
case class AddToChain(value: String) extends ChainResult
