lazy val common = Seq(
  organization := "com.tehlulz",
  version := "0.1.0",
  scalaVersion := "2.11.4",
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor" % "2.4-M1",
    "com.typesafe.akka" %% "akka-remote" % "2.4-M1",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0"
  )
)

lazy val messages = (project in file("messages")).
  settings(common: _*).
  settings(
    name := "mids_mumbler_messages"
  )

lazy val mumbler = (project in file("mumbler")).
  settings(common: _*).
  settings(
    name := "mids_mumbler",
    mainClass in (Compile, run) := Some("mumbler.CLI")
  ).
  dependsOn(messages)

lazy val agent = (project in file("agent")).
  settings(common: _*).
  settings(
    name := "mids_mumbler_agent",
    mainClass in (Compile, run) := Some("mumbler.remote.Listener"),
    libraryDependencies ++= Seq(
      "org.apache.httpcomponents" % "httpclient" % "4.4.1",
      "org.apache.httpcomponents" % "fluent-hc" % "4.4.1"
    )
  ).
  dependsOn(messages)
