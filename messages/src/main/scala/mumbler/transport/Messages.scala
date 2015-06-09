package mumbler.transport

import java.net._

/**
 * @author mdye
 */
object Messages {
  trait Message
  sealed trait Cmd
  
  case object Preprocess extends Message with Cmd
  case object Mumble extends Message with Cmd
  
  case class Download(target: URI) extends Message
  case class Control(cmd: Cmd, args: Seq[String]) extends Message
  
  case class Request(cmd: Cmd, arg: String) extends Message
  case class Response(cmd: Cmd, arg: String, result: Option[Map[String, Int]]) extends Message
  
}