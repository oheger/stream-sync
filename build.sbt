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
lazy val AkkaVersion = "2.6.15"
lazy val AkkaHttpVersion = "10.2.4"
lazy val VersionScala = "2.13.6"
lazy val VersionScalaXml = "1.3.0"
lazy val VersionCloudFiles = "0.2"
lazy val VersionLog4j = "2.14.1"
lazy val VersionLog4jScala = "12.0"
lazy val VersionDisruptor = "3.4.4"
lazy val VersionScalaTest = "3.2.9"
lazy val VersionWireMock = "2.29.0"
lazy val VersionMockito = "1.9.5"
lazy val VersionScalaTestMockito = "1.0.0-M2"
lazy val VersionJunit = "4.13"  // needed by mockito

scalacOptions ++= Seq("-deprecation", "-feature")

lazy val ITest = config("integrationTest") extend Test

lazy val akkaDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
  "org.scala-lang" % "scala-reflect" % VersionScala
)

lazy val cloudFilesDependencies = Seq(
  "com.github.oheger" %% "cloud-files-core" % VersionCloudFiles,
  "com.github.oheger" %% "cloud-files-crypt" % VersionCloudFiles,
  "com.github.oheger" %% "cloud-files-cryptalg-aes" % VersionCloudFiles,
  "com.github.oheger" %% "cloud-files-localfs" % VersionCloudFiles,
  "com.github.oheger" %% "cloud-files-webdav" % VersionCloudFiles,
  "com.github.oheger" %% "cloud-files-onedrive" % VersionCloudFiles
)

lazy val loggingDependencies = Seq(
  "org.apache.logging.log4j" %% "log4j-api-scala" % VersionLog4jScala,
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % VersionLog4j,
  "com.lmax" % "disruptor" % VersionDisruptor
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % VersionScalaTest % Test,
  "org.scalatestplus" %% "scalatestplus-mockito" % VersionScalaTestMockito % Test,
  "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
  "com.github.tomakehurst" % "wiremock-jre8" % VersionWireMock % Test,
  "org.mockito" % "mockito-core" % VersionMockito % Test,
  "junit" % "junit" % VersionJunit % Test
)

lazy val StreamSync = (project in file("."))
  .configs(ITest)
  .settings(inConfig(ITest)(Defaults.testSettings): _*)
  .settings(
    version := "0.13-SNAPSHOT",
    scalaVersion := VersionScala,
    libraryDependencies ++= akkaDependencies,
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % VersionScalaXml,
    libraryDependencies += "com.github.oheger" %% "scli" % "1.0.0",
    libraryDependencies ++= cloudFilesDependencies,
    libraryDependencies ++= loggingDependencies,
    libraryDependencies ++= testDependencies,
    resolvers += Resolver.mavenLocal,
    name := "stream-sync",
    mainClass in assembly := Some("com.github.sync.cli.Sync")
  )
