/*
 * Copyright 2018-2023 The Developers Team.
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

/** The version of this project. */
lazy val VersionStreamSync = "0.16-SNAPSHOT"

/** Definition of versions for compile-time dependencies. */
lazy val VersionAkka = "2.6.20"
lazy val VersionAkkaHttp = "10.2.10"
lazy val VersionCloudFiles = "0.5"
lazy val VersionDisruptor = "3.4.4"
lazy val VersionLog4j = "2.19.0"
lazy val VersionScala = "3.2.0"
lazy val VersionScli = "1.1.0"

/** Definition of versions for test dependencies. */
lazy val VersionScalaTest = "3.2.14"
lazy val VersionScalaTestMockito = "3.2.14.0"
lazy val VersionWireMock = "2.34.0"

scalacOptions ++=
  Seq(
    "-feature",
    "-deprecation",
    "-unchecked",
    "-Xfatal-warnings",
    "-new-syntax"
  )

lazy val ITest = config("integrationTest") extend Test
ITest / parallelExecution := false

lazy val akkaDependencies = Seq(
  ("com.typesafe.akka" %% "akka-actor" % VersionAkka).cross(CrossVersion.for3Use2_13),
  ("com.typesafe.akka" %% "akka-actor-typed" % VersionAkka).cross(CrossVersion.for3Use2_13),
  ("com.typesafe.akka" %% "akka-stream" % VersionAkka).cross(CrossVersion.for3Use2_13),
  ("com.typesafe.akka" %% "akka-http" % VersionAkkaHttp).cross(CrossVersion.for3Use2_13),
  ("com.typesafe.akka" %% "akka-http-spray-json" % VersionAkkaHttp).cross(CrossVersion.for3Use2_13)
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
  "org.apache.logging.log4j" % "log4j-core" % VersionLog4j,
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % VersionLog4j % "compile,runtime",
  "org.apache.logging.log4j" % "log4j-slf4j2-impl" % VersionLog4j % Test,
  "com.lmax" % "disruptor" % VersionDisruptor
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % VersionScalaTest % Test exclude("org.scala-lang.modules", "scala-xml_3"),
  "org.scalatestplus" %% "mockito-4-6" % VersionScalaTestMockito % Test exclude("org.scala-lang.modules", "scala-xml_3"),
  ("com.typesafe.akka" %% "akka-testkit" % VersionAkka % Test).cross(CrossVersion.for3Use2_13),
  ("com.typesafe.akka" %% "akka-actor-testkit-typed" % VersionAkka % Test).cross(CrossVersion.for3Use2_13),
  "com.github.tomakehurst" % "wiremock-jre8" % VersionWireMock % Test
)

lazy val StreamSync = (project in file("."))
  .configs(ITest)
  .settings(inConfig(ITest)(Defaults.testSettings): _*)
  .settings(
    version := VersionStreamSync,
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
