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
lazy val AkkaVersion = "2.5.16"
lazy val AkkaHttpVersion = "10.1.4"
lazy val VersionScala = "2.12.6"
lazy val VersionScalaTest = "3.0.5"

lazy val ITest = config("integrationTest") extend Test

lazy val akkaDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "org.scala-lang" % "scala-reflect" % VersionScala
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % VersionScalaTest % "test"
)

lazy val logDependencies = Seq(
  "org.slf4j" % "slf4j-api" % "1.7.10",
  "org.slf4j" % "slf4j-simple" % "1.7.10" % "test"
)

lazy val StreamSync = (project in file("."))
  .configs(ITest)
  .settings(inConfig(ITest)(Defaults.testSettings): _*)
  .settings(
    version := "0.3",
    scalaVersion := VersionScala,
    libraryDependencies ++= akkaDependencies,
    libraryDependencies ++= testDependencies,
    name := "stream-sync"
  )
