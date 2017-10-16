lazy val common = Seq(
  organization := "com.tehlulz",
  version := "0.1.0",
  scalaVersion := "2.12.3",
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http" % "10.0.10",
    "com.typesafe.akka" %% "akka-actor" % "2.5.6",
    "com.typesafe.akka" %% "akka-remote" % "2.5.6",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2"
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
    mainClass in (Compile, run) := Some("mumbler.Launch")
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
