package mumbler

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

/**
 * @author mdye
 */
class Mumbler(val log: LoggingAdapter, val sender: ActorRef, val cluster: ActorRef*) {
  var responseRecorder = Map[ActorRef, Option[Response]]()

  def distribute(messages: Seq[Message]): Unit = {
    for ((message, ix) <- messages.view.zipWithIndex) {
      val clusterAssignment = (ix % cluster.size)
      log.info(s"sending dl ${message} to ${clusterAssignment}")
      cluster(clusterAssignment).tell(message, sender)
    }
  }

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

sealed trait ChainResult

case object EndChain extends ChainResult
case object NotAllNodesReported extends ChainResult
case class AddToChain(value: String) extends ChainResult
