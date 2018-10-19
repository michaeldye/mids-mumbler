package mumbler.transport

import java.net._

import scala.reflect.runtime.universe._

/**
 * @author mdye
 */
object Messages {
  trait Message
  sealed trait Cmd

  case object Mumble extends Message with Cmd

  case class Download(target: URI) extends Message
  case class Report(msg: String, success: Boolean, target: URI) extends Message

  case class Control(cmd: Cmd, seed: String) extends Message

  case class Request(cmd: Cmd, chain: Seq[String]) extends Message
  case class Response(cmd: Cmd, chain: Seq[String], result: Option[Map[String, Int]]) extends Message

  abstract class StatsResult() {
    val ts = System.currentTimeMillis
  }

  case class Indexed(totalProcessed: Int, totalIndexed: Int, totalIndexMillis: Long) extends StatsResult

  object Indexed {
    def empty(): Indexed = {
      return new Indexed(0, 0, 0)
    }

  }

  case class StatsRequest() extends Message
  case class StatsResponse(result: Option[StatsResult]) extends Message
}
