/*
 * Copyright 2018-2021 The Developers Team.
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
lazy val AkkaVersion = "2.6.17"
lazy val AkkaHttpVersion = "10.2.6"
lazy val VersionScala = "3.1.0"
lazy val VersionCloudFiles = "0.3"
lazy val VersionScli = "1.1.0"
lazy val VersionLog4j = "2.14.1"
lazy val VersionDisruptor = "3.4.4"
lazy val VersionScalaTest = "3.2.10"
lazy val VersionWireMock = "2.31.0"
lazy val VersionScalaTestMockito = "3.2.10.0"

scalacOptions ++=
  Seq(
    "-feature",
    "-unchecked",
    "-Xfatal-warnings",
    "-new-syntax"
  )

lazy val ITest = config("integrationTest") extend Test

lazy val akkaDependencies = Seq(
  ("com.typesafe.akka" %% "akka-actor" % AkkaVersion).cross(CrossVersion.for3Use2_13),
  ("com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion).cross(CrossVersion.for3Use2_13),
  ("com.typesafe.akka" %% "akka-stream" % AkkaVersion).cross(CrossVersion.for3Use2_13),
  ("com.typesafe.akka" %% "akka-http" % AkkaHttpVersion).cross(CrossVersion.for3Use2_13),
  ("com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion).cross(CrossVersion.for3Use2_13)
)

lazy val cloudFilesDependencies = Seq(
  ("com.github.oheger" %% "cloud-files-core" % VersionCloudFiles).cross(CrossVersion.for3Use2_13),
  ("com.github.oheger" %% "cloud-files-crypt" % VersionCloudFiles).cross(CrossVersion.for3Use2_13),
  ("com.github.oheger" %% "cloud-files-cryptalg-aes" % VersionCloudFiles).cross(CrossVersion.for3Use2_13),
  ("com.github.oheger" %% "cloud-files-localfs" % VersionCloudFiles).cross(CrossVersion.for3Use2_13),
  ("com.github.oheger" %% "cloud-files-webdav" % VersionCloudFiles).cross(CrossVersion.for3Use2_13),
  ("com.github.oheger" %% "cloud-files-onedrive" % VersionCloudFiles).cross(CrossVersion.for3Use2_13),
  ("com.github.oheger" %% "cloud-files-googledrive" % VersionCloudFiles).cross(CrossVersion.for3Use2_13)
)

lazy val loggingDependencies = Seq(
  "org.apache.logging.log4j" % "log4j-api" % VersionLog4j,
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % VersionLog4j,
  "com.lmax" % "disruptor" % VersionDisruptor
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % VersionScalaTest % Test exclude("org.scala-lang.modules", "scala-xml_3"),
  "org.scalatestplus" %% "mockito-3-12" % VersionScalaTestMockito % Test exclude("org.scala-lang.modules", "scala-xml_3"),
  ("com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test).cross(CrossVersion.for3Use2_13),
  ("com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test).cross(CrossVersion.for3Use2_13),
  "com.github.tomakehurst" % "wiremock-jre8" % VersionWireMock % Test
)

lazy val StreamSync = (project in file("."))
  .configs(ITest)
  .settings(inConfig(ITest)(Defaults.testSettings): _*)
  .settings(
    version := "0.14",
    scalaVersion := VersionScala,
    libraryDependencies ++= akkaDependencies,
    libraryDependencies += ("com.github.oheger" %% "scli" % VersionScli).cross(CrossVersion.for3Use2_13),
    libraryDependencies ++= cloudFilesDependencies,
    libraryDependencies ++= loggingDependencies,
    libraryDependencies ++= testDependencies,
    resolvers += Resolver.mavenLocal,
    name := "stream-sync",
    assembly / mainClass := Some("com.github.sync.cli.Sync")
  )
