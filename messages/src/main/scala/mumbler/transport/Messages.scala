package mumbler.transport

import java.net._

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

}
