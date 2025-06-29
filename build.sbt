/*
 * Copyright 2018-2025 The Developers Team.
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
lazy val VersionStreamSync = "0.20-SNAPSHOT"

/** Definition of versions for compile-time dependencies. */
lazy val VersionCloudFiles = "0.10"
lazy val VersionDisruptor = "4.0.0"
lazy val VersionLog4j = "2.25.0"
lazy val VersionPekko = "1.1.4"
lazy val VersionPekkoHttp = "1.2.0"
lazy val VersionScala = "3.7.1"
lazy val VersionScli = "1.1.0"
lazy val VersionSprayJson = "1.3.6"

/** Definition of versions for test dependencies. */
lazy val VersionScalaTest = "3.2.19"
lazy val VersionScalaTestMockito = "3.2.19.0"
lazy val VersionWireMock = "3.13.1"

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
  "org.apache.pekko" %% "pekko-actor" % VersionPekko,
  "org.apache.pekko" %% "pekko-actor-typed" % VersionPekko,
  "org.apache.pekko" %% "pekko-stream" % VersionPekko,
  "org.apache.pekko" %% "pekko-http" % VersionPekkoHttp,
  "org.apache.pekko" %% "pekko-http-spray-json" % VersionPekkoHttp
)

lazy val cloudFilesDependencies = Seq(
  "com.github.oheger" %% "cloud-files-core" % VersionCloudFiles,
  "com.github.oheger" %% "cloud-files-crypt" % VersionCloudFiles,
  "com.github.oheger" %% "cloud-files-cryptalg-aes" % VersionCloudFiles,
  "com.github.oheger" %% "cloud-files-localfs" % VersionCloudFiles,
  "com.github.oheger" %% "cloud-files-webdav" % VersionCloudFiles,
  "com.github.oheger" %% "cloud-files-onedrive" % VersionCloudFiles,
  "com.github.oheger" %% "cloud-files-googledrive" % VersionCloudFiles
)

lazy val loggingDependencies = Seq(
  "org.apache.logging.log4j" % "log4j-api" % VersionLog4j,
  "org.apache.logging.log4j" % "log4j-core" % VersionLog4j,
  "org.apache.logging.log4j" % "log4j-slf4j2-impl" % VersionLog4j,
  "com.lmax" % "disruptor" % VersionDisruptor
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % VersionScalaTest % Test,
  "org.scalatestplus" %% "mockito-5-12" % VersionScalaTestMockito % Test,
  "org.apache.pekko" %% "pekko-testkit" % VersionPekko % Test,
  "org.apache.pekko" %% "pekko-actor-testkit-typed" % VersionPekko % Test,
  "org.wiremock" % "wiremock" % VersionWireMock % Test
)

lazy val StreamSync = (project in file("."))
  .configs(ITest)
  .settings(inConfig(ITest)(Defaults.testSettings) *)
  .settings(
    version := VersionStreamSync,
    scalaVersion := VersionScala,
    libraryDependencies ++= akkaDependencies,
    libraryDependencies += ("com.github.oheger" %% "scli" % VersionScli).cross(CrossVersion.for3Use2_13),
    libraryDependencies += "io.spray" %%  "spray-json" % VersionSprayJson,
    libraryDependencies ++= cloudFilesDependencies,
    libraryDependencies ++= loggingDependencies,
    libraryDependencies ++= testDependencies,
    resolvers += Resolver.mavenLocal,
    name := "stream-sync",
    assembly / mainClass := Some("com.github.sync.cli.Sync"),
    assembly / assemblyMergeStrategy := {
      case "module-info.class" => MergeStrategy.discard
      case x =>
        val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
