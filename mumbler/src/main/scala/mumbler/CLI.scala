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

/**
 * @author mdye
 */
object CLI extends App {
  val system = ActorSystem("Mumbler")

  if (args.size < 4) {
    Console.err.println("Usage: java -jar <mids_mumbler-assembly...jar> [files max] [seed word] [chain max] [remoteaddr:port,remoteaddr:port,...]")
    System.exit(1)
  }

  val agents : Seq[(String, Int)] = args.slice(3, args.size).map { ag: String =>
    val parts = ag.split(":")
    (parts(0), parts(1).toInt)
  }

  val conductorActor = system.actorOf(Props(new Conductor(args(0).toInt, args(2).toInt, agents)), name = "Conductor")

  Console.println(s"Conductor setup begun, source files are being processed if not already present. Starting actor system and API.")
  //TODO: start API listener here, give it a common conductor reference
  conductorActor ! Control(Mumble, args(1))
}

class Conductor(val filesMax: Int, val chainMax: Int, val agentConf: Seq[(String, Int)]) extends Actor with ActorLogging {

  def remote(host: String, port: Int): ActorRef = {
    val duration = Duration.create(3, TimeUnit.SECONDS)
    implicit val timeout = Timeout(duration)
    Await.result(context.actorSelection(s"akka.tcp://RemoteMumbler@${host}:${port}/user/Agent").resolveOne(), duration)
  }

  val mumbler = new Mumbler(log, self, agentConf.map(Function.tupled(remote _)):_*)
  log.info(s"Preprocessing given agentConf: ${agentConf.mkString(" ")}")

  val last = if (filesMax > 100) 100 else filesMax
  mumbler.distribute((0 until last).map(ix => Download(new URI(s"http://storage.googleapis.com/books/ngrams/books/googlebooks-eng-us-all-2gram-20090715-${ix}.csv.zip"))))

  def receive = {
    case Control(Mumble, seed) => mumbler.all(Request(Mumble, List[String](seed)))

    case _: Control => log.info(s"Unexpected command")

    case response: Response =>
      log.debug(s"Received response from remote mumbler: ${response.result.mkString(" ")}")

      // update response
      // evaluate nodes' responses
      mumbler.mumble(sender, response) match {
        case AddToChain(word: String) =>
          log.info(s"$word")
          
          val chain: Seq[String] = response.chain ++ List(word)
          log.debug(s"chain so far: ${chain.mkString(" ")}")

          if (chain.length == chainMax) exit(s"reached requested max chain length, $chainMax", chain)
          else mumbler.all(Request(Mumble, chain))

        case EndChain => exit("no following words found", response.chain)
        case NotAllNodesReported =>  // continue
      }


    case msg: String => log.info(s"Received message: '$msg' from $sender")
  }

  def exit(reason: String, chain: Seq[String]) {
    log.info(s"Exiting b/c ${reason}")
    log.info(s"Chain: ${chain.mkString(" ")}")

    // TODO, don't want to kill whole system anymore, just return all answers. Q: need to do something with this actor to clean up?
    //context.system.terminate()
    //System.exit(1)
  }
}

object Selection {
  def randomized(following: Iterable[Int], sum: Int) = {}
}
