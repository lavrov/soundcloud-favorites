scalaVersion := "2.11.5"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream-experimental" % "1.0-M3",
  "com.typesafe.play" %% "play-json" % "2.3.6",
  "com.typesafe.play" %% "play-ws" % "2.3.6",
  "org.scalatest" %% "scalatest" % "2.2.3" % "test"
)