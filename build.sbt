/*
 * Copyright 2018 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/** Definition of versions. */
lazy val AkkaVersion = "2.5.17"
lazy val AkkaHttpVersion = "10.1.5"
lazy val VersionScala = "2.12.6"
lazy val VersionScalaXml = "1.1.0"
lazy val VersionScalaTest = "3.0.5"
lazy val VersionWireMock = "2.18.0"

scalacOptions ++= Seq("-deprecation", "-feature")

lazy val ITest = config("integrationTest") extend Test

lazy val akkaDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
  "org.scala-lang" % "scala-reflect" % VersionScala
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % VersionScalaTest % "test",
  "com.github.tomakehurst" % "wiremock" % VersionWireMock % "test",
  "org.mockito" % "mockito-core" % "1.9.5" % "test"
)

lazy val logDependencies = Seq(
  "org.slf4j" % "slf4j-api" % "1.7.10",
  "org.slf4j" % "slf4j-simple" % "1.7.10" % "test"
)

lazy val StreamSync = (project in file("."))
  .configs(ITest)
  .settings(inConfig(ITest)(Defaults.testSettings): _*)
  .settings(
    version := "0.7-SNAPSHOT",
    scalaVersion := VersionScala,
    libraryDependencies ++= akkaDependencies,
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % VersionScalaXml,
    libraryDependencies ++= testDependencies,
    name := "stream-sync",
    IntegrationTest / parallelExecution := false,
    mainClass in assembly := Some("com.github.sync.cli.Sync")
  )
