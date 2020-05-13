/*
 * Copyright 2018-2020 The Developers Team.
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

package com.github.sync.cli

import java.nio.file.Paths
import java.time.ZoneId

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.Timeout
import com.github.sync.cli.ParameterExtractor.ParameterExtractionException
import com.github.sync.cli.SyncParameterManager.{ApplyModeNone, ApplyModeTarget, CryptConfig, CryptMode}
import com.github.sync.cli.SyncStructureConfig.{DavStructureConfig, FsStructureConfig}
import com.github.sync.http.NoAuth
import com.github.sync.{AsyncTestHelper, FileTestHelper}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure

object SyncParameterManagerSpec {
  /** Test source URI. */
  private val SourceUri = "/test/source/uri"

  /** Test destination URI. */
  private val DestinationUri = "/test/destination/uri"

  /** A test timeout value (in seconds). */
  private val TimeoutValue = 44

  /** A map with test parameter values. */
  private val ArgsMap = Map(ParameterParser.InputOption -> List(SourceUri, DestinationUri),
    SyncParameterManager.TimeoutOption -> List(TimeoutValue.toString))
}

/**
  * Test class for ''SyncParameterManager''. This class also tests
  * functionality of the generic ''ParameterManager'' class.
  */
class SyncParameterManagerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfter with Matchers with FileTestHelper with MockitoSugar
  with AsyncTestHelper {
  def this() = this(ActorSystem("SyncParameterManagerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  after {
    tearDownTestFile()
  }

  import SyncParameterManagerSpec._

  private implicit val consoleReader: ConsoleReader = mock[ConsoleReader]

  /**
    * Expects a failed future from a parsing operation. It is checked whether
    * the future is actually failed with an ''IllegalArgumentException'' that
    * has a specific error message.
    *
    * @param future   the future to be checked
    * @param msgParts text parts to be expected in the exception message
    * @return the error message from the exception
    */
  private def expectFailedFuture(future: Future[_], msgParts: String*): String = {
    val exception = expectFailedFuture[ParameterExtractionException](future)
    msgParts foreach (part => exception.getMessage should include(part))
    exception.getMessage
  }

  "SyncParameterManager" should "extract URI parameters if they are present" in {
    val (config, params) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))
    config.srcUri should be(SourceUri)
    config.dstUri should be(DestinationUri)
    params.parameters.accessedParameters should contain allElementsOf ArgsMap.keySet
  }

  it should "reject URI parameters if there are more than 2" in {
    val argsMap = ArgsMap + (ParameterParser.InputOption -> List("u1", "u2", "u3"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap), "Too many input arguments")
  }

  it should "reject URI parameters if no destination URI is provided" in {
    val argsMap = ArgsMap + (ParameterParser.InputOption -> List("u1"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Too few input arguments", "destinationURI")
  }

  it should "reject URI parameters if no URIs are provided" in {
    val argsMap = ArgsMap + (ParameterParser.InputOption -> List.empty[String])

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Too few input arguments", "'sourceURI'", "'destinationURI'")
  }

  it should "reject URI parameters if no non-option parameters are provided" in {
    val argsMap = ArgsMap - ParameterParser.InputOption

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Too few input arguments", "'sourceURI'", "'destinationURI'")
  }

  it should "construct a correct source config for the local file system" in {
    val zid = ZoneId.getAvailableZoneIds.iterator.next
    val roleType = SyncStructureConfig.SourceRoleType
    val argsMap = ArgsMap + (roleType.configPropertyName(SyncStructureConfig.PropLocalFsTimeZone) -> List(zid))

    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.srcConfig should be(FsStructureConfig(Some(ZoneId.of(zid))))
  }

  it should "construct a correct destination config for a Dav server" in {
    val ModifiedProp = "x_changed"
    val ModifiedNs = "ns_foo"
    val DavDestUri = SyncStructureConfig.PrefixWebDav + "https://dav.org/sync"
    val role = SyncStructureConfig.DestinationRoleType
    val argsMap = ArgsMap +
      (role.configPropertyName(SyncStructureConfig.PropDavModifiedProperty) -> List(ModifiedProp)) +
      (role.configPropertyName(SyncStructureConfig.PropDavModifiedNamespace) -> List(ModifiedNs)) +
      (ParameterParser.InputOption -> List(SourceUri, DavDestUri))
    val ExpDavConfig = DavStructureConfig(Some(ModifiedProp), Some(ModifiedNs),
      authConfig = NoAuth, deleteBeforeOverride = false)

    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.dstConfig should be(ExpDavConfig)
  }

  it should "not return a single option value if there are multiple" in {
    val argsMap = ArgsMap + (SyncParameterManager.TimeoutOption -> List("100", "200"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      SyncParameterManager.TimeoutOption, "has multiple values")
  }

  it should "return a default apply mode" in {
    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))
    config.applyMode should be(ApplyModeTarget(DestinationUri))
  }

  it should "return a target apply mode with the specified URI" in {
    val applyUri = "/dest/apply/uri"
    val argsMap = ArgsMap + (SyncParameterManager.ApplyModeOption -> List("Target:" + applyUri))

    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.applyMode should be(ApplyModeTarget(applyUri))
  }

  it should "return the apply mode NONE" in {
    val argsMap = ArgsMap + (SyncParameterManager.ApplyModeOption -> List("none"))

    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.applyMode should be(ApplyModeNone)
  }

  it should "handle an invalid apply mode" in {
    val Mode = "unknown:foo"
    val argsMap = ArgsMap + (SyncParameterManager.ApplyModeOption -> List(Mode))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Invalid apply mode: '" + Mode)
  }

  it should "return a default timeout if no timeout option is provided" in {
    val argsMap = ArgsMap - SyncParameterManager.TimeoutOption

    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.timeout should be(SyncParameterManager.DefaultTimeout)
  }

  it should "return the configured timeout option value" in {
    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))
    config.timeout should be(Timeout(TimeoutValue.seconds))
  }

  it should "handle an invalid timeout value" in {
    val timeoutStr = "invalidTimeout!"
    val argsMap = ArgsMap + (SyncParameterManager.TimeoutOption -> List(timeoutStr))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      SyncParameterManager.TimeoutOption, timeoutStr)
  }

  it should "have an undefined log file option if none is specified" in {
    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))
    config.logFilePath should be(None)
  }

  it should "store the path to a log file in the sync config" in {
    val logFile = Paths.get("var", "logs", "sync.log").toAbsolutePath
    val argsMap = ArgsMap + (SyncParameterManager.LogFileOption -> List(logFile.toString))

    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.logFilePath should be(Some(logFile))
  }

  it should "handle a log file option with multiple values" in {
    val argsMap = ArgsMap + (SyncParameterManager.LogFileOption -> List("log1", "log2"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      SyncParameterManager.LogFileOption, "have a single value")
  }

  it should "have an undefined sync log option if none is specified" in {
    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))
    config.syncLogPath should be(None)
  }

  it should "store the path to the sync log file in the sync config" in {
    val syncLogFile = Paths.get("data", "sync", "log", "sync.log").toAbsolutePath
    val argsMap = ArgsMap + (SyncParameterManager.SyncLogOption -> List(syncLogFile.toString))

    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.syncLogPath should be(Some(syncLogFile))
  }

  it should "handle a sync log option with multiple values" in {
    val argsMap = ArgsMap + (SyncParameterManager.SyncLogOption -> List("log1", "log2"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      SyncParameterManager.SyncLogOption, "should have a single value")
  }

  it should "handle an undefined option for the file times threshold" in {
    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))

    config.ignoreTimeDelta should be(None)
  }

  it should "evaluate the threshold for file time deltas" in {
    val Delta = 28
    val argsMap = ArgsMap + (SyncParameterManager.IgnoreTimeDeltaOption -> List(Delta.toString))

    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.ignoreTimeDelta should be(Some(Delta))
  }

  it should "handle an invalid threshold for file time deltas" in {
    val InvalidValue = "not a threshold for a time delta!"
    val argsMap = ArgsMap + (SyncParameterManager.IgnoreTimeDeltaOption -> List(InvalidValue))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      InvalidValue, SyncParameterManager.IgnoreTimeDeltaOption)
  }

  it should "handle an undefined option for the operations per second" in {
    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))

    config.opsPerSecond should be(None)
  }

  it should "evaluate the threshold for the operations per second" in {
    val OpsCount = 17
    val argsMap = ArgsMap + (SyncParameterManager.OpsPerSecondOption -> List(OpsCount.toString))

    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.opsPerSecond should be(Some(OpsCount))
  }

  it should "handle an invalid threshold for the operations per second" in {
    val InvalidValue = "not a valid number of ops per sec"
    val argsMap = ArgsMap + (SyncParameterManager.OpsPerSecondOption -> List(InvalidValue))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      InvalidValue, SyncParameterManager.OpsPerSecondOption)
  }

  it should "return correct default options related to encryption" in {
    val DefCryptConfig = CryptConfig(srcPassword = None, dstPassword = None,
      srcCryptMode = CryptMode.None, dstCryptMode = CryptMode.None,
      cryptCacheSize = SyncParameterManager.DefaultCryptCacheSize)
    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))

    config.cryptConfig should be(DefCryptConfig)
  }

  it should "handle options related to encryption" in {
    val SrcPwd = "secretSource!"
    val DstPwd = "!secretDest"
    val CacheSize = 555
    val argsMap = ArgsMap + (SyncParameterManager.SourcePasswordOption -> List(SrcPwd)) +
      (SyncParameterManager.DestPasswordOption -> List(DstPwd)) +
      (SyncParameterManager.SourceCryptModeOption -> List("files")) +
      (SyncParameterManager.DestCryptModeOption -> List("FilesAndNAMEs")) +
      (SyncParameterManager.CryptCacheSizeOption -> List(CacheSize.toString))

    val (syncConfig, _) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    val config = syncConfig.cryptConfig
    config.srcPassword should be(Some(SrcPwd))
    config.dstPassword should be(Some(DstPwd))
    config.srcCryptMode shouldBe CryptMode.Files
    config.dstCryptMode shouldBe CryptMode.FilesAndNames
    config.cryptCacheSize should be(CacheSize)
  }

  it should "handle invalid enum values for encryption-related flags" in {
    val argsMap = ArgsMap + (SyncParameterManager.SourceCryptModeOption -> List("of course")) +
      (SyncParameterManager.DestCryptModeOption -> List("full encryption"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      SyncParameterManager.SourceCryptModeOption, SyncParameterManager.DestCryptModeOption)
  }

  it should "handle invalid integer values for the crypt cache size option" in {
    val argsMap = ArgsMap + (SyncParameterManager.CryptCacheSizeOption -> List("big"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "big", SyncParameterManager.CryptCacheSizeOption)
  }

  it should "reject a crypt password if encryption is disabled" in {
    val argsMap = ArgsMap + (SyncParameterManager.SourcePasswordOption -> List("srcSecret")) +
      (SyncParameterManager.DestPasswordOption -> List("dstSecret"))

    val (_, next) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    ParameterExtractor.checkParametersConsumed(next) match {
      case Failure(exception: ParameterExtractionException) =>
        exception.failures.map(_.key) should contain only(SyncParameterManager.SourcePasswordOption,
          SyncParameterManager.DestPasswordOption)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "read the crypt passwords from the console if necessary" in {
    val SrcPwd = "secretSource!"
    val DstPwd = "!secretDest"
    val argsMap = ArgsMap + (SyncParameterManager.SourceCryptModeOption -> List("files")) +
      (SyncParameterManager.DestCryptModeOption -> List("FilesAndNAMEs"))
    val reader = mock[ConsoleReader]
    when(reader.readOption(SyncParameterManager.SourcePasswordOption, password = true))
      .thenReturn(SrcPwd)
    when(reader.readOption(SyncParameterManager.DestPasswordOption, password = true))
      .thenReturn(DstPwd)

    val (config, _) = futureResult(SyncParameterManager.extractSyncConfig(argsMap)(consoleReader = reader,
      ec = system.dispatcher))
    config.cryptConfig.srcPassword should be(Some(SrcPwd))
    config.cryptConfig.dstPassword should be(Some(DstPwd))
  }

  it should "handle a crypt cache size below the allowed minimum" in {
    val argsMap = ArgsMap + (SyncParameterManager.CryptCacheSizeOption ->
      List(String.valueOf(SyncParameterManager.MinCryptCacheSize - 1)))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Crypt cache size must be greater or equal " + SyncParameterManager.MinCryptCacheSize)
  }

  it should "mark all options contained in the sync config as accessed" in {
    val otherOptions = Map("foo" -> List("v1"), "bar" -> List("v2", "v3"),
      SyncParameterManager.SourceCryptModeOption -> List("files"),
      SyncParameterManager.DestCryptModeOption -> List("files"))
    val argsMap = ArgsMap ++ otherOptions +
      (SyncParameterManager.IgnoreTimeDeltaOption -> List("1"))

    val (_, updArgs) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    updArgs.parameters.accessedParameters should contain allOf(SyncParameterManager.ApplyModeOption,
      SyncParameterManager.TimeoutOption, SyncParameterManager.LogFileOption, SyncParameterManager.SyncLogOption,
      SyncParameterManager.IgnoreTimeDeltaOption, SyncParameterManager.OpsPerSecondOption,
      SyncParameterManager.SourcePasswordOption, SyncParameterManager.DestPasswordOption,
      SyncParameterManager.SourceCryptModeOption, SyncParameterManager.DestCryptModeOption)
  }

  it should "combine multiple error messages when parsing the sync config" in {
    val argsMap = Map(ParameterParser.InputOption -> List(SourceUri),
      SyncParameterManager.ApplyModeOption -> List("invalidApplyMode"),
      SyncParameterManager.TimeoutOption -> List("invalidTimeout"),
      SyncParameterManager.CryptCacheSizeOption -> List("invalidCacheSize"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "undefined argument 'destinationURI'", "apply mode", SyncParameterManager.TimeoutOption,
      SyncParameterManager.CryptCacheSizeOption)
  }

  it should "parse a command line correctly" in {
    val fileOptions = Map("fileOpt1" -> "found", "fileOpt2" -> "foundAsWell")
    val fileContent = fileOptions.toList
      .flatMap(t => List("--" + t._1, t._2))
      .mkString(CliHelpGenerator.CR)
    val paramFile = createDataFile(fileContent)
    val args = Array("--opt1", "value1", SourceUri, "--Opt2", "otherValue",
      "--" + SyncParameterManager.FileOption, paramFile.toString)

    val paramMap = futureResult(SyncParameterManager.parseParameters(args))
    paramMap should not contain SyncParameterManager.FileOption
    fileOptions foreach { entry =>
      paramMap(toLowerCase(entry._1)) should contain only entry._2
    }
    paramMap("opt2") should contain only "otherValue"
    paramMap(ParameterParser.InputOption) should contain only SourceUri
  }
}
