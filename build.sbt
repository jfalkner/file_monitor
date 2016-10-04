name := "file_monitor"

version in ThisBuild := "0.0.8"

organization in ThisBuild := "jfalkner"

scalaVersion in ThisBuild := "2.11.8"

scalacOptions in ThisBuild := Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-feature", "-language:postfixOps")

libraryDependencies ++= Seq(
  // API needed for Csv -- default serialization for this project
  "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  "commons-io" %  "commons-io" % "2.5",
  // needed only for running the tests
  "org.specs2" % "specs2_2.11" % "2.4.1-scalaz-7.0.6" % "test"
)

lazy val file_monitor = (project in file(".")).dependsOn(file_backed_logs)

lazy val file_backed_logs = RootProject(uri("https://github.com/jfalkner/file_backed_logs.git#v0.0.8"))