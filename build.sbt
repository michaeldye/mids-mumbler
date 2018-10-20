lazy val common = Seq(
  organization := "com.tehlulz",
  version := "0.5.0-SNAPSHOT",
  scalaVersion := "2.12.3",
  compileOrder := CompileOrder.JavaThenScala,
	javaOptions in Test += s"-Dconfig.file=${sourceDirectory.value}/test/resources/application.conf",
  maxErrors := 5,
  triggeredMessage in ThisBuild := Watched.clearWhenTriggered,
	fork in Test := true,
  cancelable in Global := true,
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-http" % "10.1.5",
    "com.typesafe.akka" %% "akka-actor" % "2.5.6",
    "com.typesafe.akka" %% "akka-remote" % "2.5.6",
    "com.typesafe.akka" %% "akka-slf4j" % "2.5.6",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
    "org.scalatest" %% "scalatest" % "3.0.5" % "test"
  ),
  PB.targets in Compile := Seq(
    scalapb.gen() -> (sourceManaged in Compile).value
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
    mainClass in (Compile, run) := Some("mumbler.Launch"),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.5",
			"com.typesafe.akka" %% "akka-testkit" % "2.5.6" % Test
    )
  ).
  dependsOn(messages)

lazy val agent = (project in file("agent")).
  settings(common: _*).
  settings(
    name := "mids_mumbler_agent",
    mainClass in (Compile, run) := Some("mumbler.remote.Listener"),
    libraryDependencies ++= Seq(
      "org.apache.httpcomponents" % "httpclient" % "4.5.6",
      "org.apache.httpcomponents" % "fluent-hc" % "4.5.6",
      "org.slf4j" % "slf4j-api" % "1.7.25"
    )
  ).
  dependsOn(messages)


