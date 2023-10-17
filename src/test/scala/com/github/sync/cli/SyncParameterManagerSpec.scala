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

package com.github.sync.cli

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.Timeout
import com.github.scli.ParameterExtractor.{ExtractionContext, ParameterExtractionException}
import com.github.scli.{ConsoleReader, DummyConsoleReader, ParameterExtractor, ParameterParser}
import com.github.sync.cli.ExtractorTestHelper.{accessedKeys, toExtractionContext, toParameters}
import com.github.sync.cli.FilterManager.SyncFilterData
import com.github.sync.cli.SyncCliStreamConfig.{MirrorStreamConfig, StreamConfig}
import com.github.sync.cli.SyncParameterManager.*
import com.github.sync.cli.SyncCliStructureConfig.StructureAuthConfig
import com.github.sync.oauth.SyncNoAuth
import com.github.sync.protocol.config.{DavStructureConfig, FsStructureConfig}
import com.github.sync.stream.{IgnoreTimeDelta, Throttle}
import com.github.sync.{AsyncTestHelper, FileTestHelper}
import org.apache.logging.log4j.Level
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.time.ZoneId
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Failure

object SyncParameterManagerSpec:
  /** Test source URI. */
  private val SourceUri = "/test/source/uri"

  /** Test destination URI. */
  private val DestinationUri = "/test/destination/uri"

  /** A test timeout value (in seconds). */
  private val TimeoutValue = 44

  /** A map with test parameter values. */
  private val ArgsMap = Map(ParameterParser.InputParameter.key -> List(SourceUri, DestinationUri),
    SyncCliStreamConfig.TimeoutOption -> List(TimeoutValue.toString))

  /**
    * Runs the sync config processor on the given map with parameters and 
    * returns the result.
    *
    * @param argsMap       the map with arguments
    * @param consoleReader the object for reading from the console
    * @return a future with the extracted config and the updated context
    */
  private def extractSyncConfig(argsMap: Map[String, Iterable[String]],
                                consoleReader: ConsoleReader = DummyConsoleReader):
  Future[(SyncConfig, ExtractionContext)] =
    val context = toExtractionContext(toParameters(argsMap), consoleReader)
    Future.fromTry(ParameterExtractor.tryExtractor(syncConfigExtractor(), context))

/**
  * Test class for ''SyncParameterManager''. This class also tests
  * functionality of the generic ''ParameterManager'' class.
  */
class SyncParameterManagerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfter with Matchers with FileTestHelper with MockitoSugar
  with AsyncTestHelper :
  def this() = this(ActorSystem("SyncParameterManagerSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  after {
    tearDownTestFile()
  }

  import SyncParameterManagerSpec._

  /**
    * Expects a failed future from a parsing operation. It is checked whether
    * the future is actually failed with an ''IllegalArgumentException'' that
    * has a specific error message.
    *
    * @param future   the future to be checked
    * @param msgParts text parts to be expected in the exception message
    * @return the error message from the exception
    */
  private def expectFailedFuture(future: Future[_], msgParts: String*): String =
    val exception = expectFailedFuture[ParameterExtractionException](future)
    msgParts foreach (part => exception.getMessage should include(part))
    exception.getMessage

  "SyncParameterManager" should "extract URI parameters if they are present" in {
    val (config, params) = futureResult(extractSyncConfig(ArgsMap))
    config.srcUri should be(SourceUri)
    config.dstUri should be(DestinationUri)
    ExtractorTestHelper.accessedKeys(params) should contain allElementsOf ArgsMap.keySet
  }

  it should "reject URI parameters if there are more than 2" in {
    val argsMap = ArgsMap + (ParameterParser.InputParameter.key -> List("u1", "u2", "u3"))

    expectFailedFuture(extractSyncConfig(argsMap), "Too many input arguments")
  }

  it should "reject URI parameters if no destination URI is provided" in {
    val argsMap = ArgsMap + (ParameterParser.InputParameter.key -> List("u1"))

    expectFailedFuture(extractSyncConfig(argsMap),
      "Mandatory parameter has no value", "destinationURI")
  }

  it should "reject URI parameters if no URIs are provided" in {
    val argsMap = ArgsMap + (ParameterParser.InputParameter.key -> List.empty[String])

    expectFailedFuture(extractSyncConfig(argsMap),
      "Mandatory parameter has no value", "sourceURI", "destinationURI")
  }

  it should "reject URI parameters if no non-option parameters are provided" in {
    val argsMap = ArgsMap - ParameterParser.InputParameter.key

    expectFailedFuture(extractSyncConfig(argsMap),
      "Mandatory parameter has no value", "sourceURI", "destinationURI")
  }

  it should "construct a correct source config for the local file system" in {
    val zid = ZoneId.getAvailableZoneIds.iterator.next
    val roleType = SyncCliStructureConfig.SourceRoleType
    val argsMap = ArgsMap + (roleType.configPropertyName(SyncCliStructureConfig.PropLocalFsTimeZone) -> List(zid))

    val (config, _) = futureResult(extractSyncConfig(argsMap))
    config.srcConfig.structureConfig should be(FsStructureConfig(Some(ZoneId.of(zid))))
    config.srcConfig.authConfig should be(SyncNoAuth)
  }

  it should "construct a correct destination config for a Dav server" in {
    val ModifiedProp = "x_changed"
    val ModifiedNs = "ns_foo"
    val DavDestUri = SyncCliStructureConfig.PrefixWebDav + "https://dav.org/sync"
    val role = SyncCliStructureConfig.DestinationRoleType
    val argsMap = ArgsMap +
      (role.configPropertyName(SyncCliStructureConfig.PropDavModifiedProperty) -> List(ModifiedProp)) +
      (role.configPropertyName(SyncCliStructureConfig.PropDavModifiedNamespace) -> List(ModifiedNs)) +
      (ParameterParser.InputParameter.key -> List(SourceUri, DavDestUri))
    val ExpDavConfig = StructureAuthConfig(DavStructureConfig(Some(ModifiedProp), Some(ModifiedNs),
      deleteBeforeOverride = false), SyncNoAuth)

    val (config, _) = futureResult(extractSyncConfig(argsMap))
    config.dstConfig should be(ExpDavConfig)
  }

  it should "not return a single option value if there are multiple" in {
    val argsMap = ArgsMap + (SyncCliStreamConfig.TimeoutOption -> List("100", "200"))

    expectFailedFuture(extractSyncConfig(argsMap),
      SyncCliStreamConfig.TimeoutOption, "Single value expected")
  }

  it should "have an undefined log file option if none is specified" in {
    val (config, _) = futureResult(extractSyncConfig(ArgsMap))
    config.logConfig.logFilePath should be(None)
  }

  it should "store the path to a log file in the sync config" in {
    val logFile = Paths.get("var", "logs", "sync.log").toAbsolutePath
    val argsMap = ArgsMap + (SyncParameterManager.LogFileOption -> List(logFile.toString))

    val (config, _) = futureResult(extractSyncConfig(argsMap))
    config.logConfig.logFilePath should be(Some(logFile))
  }

  it should "handle a log file option with multiple values" in {
    val argsMap = ArgsMap + (SyncParameterManager.LogFileOption -> List("log1", "log2"))

    expectFailedFuture(extractSyncConfig(argsMap),
      SyncParameterManager.LogFileOption, "Single value expected")
  }

  it should "have an undefined error log file option if none is specified" in {
    val (config, _) = futureResult(extractSyncConfig(ArgsMap))
    config.logConfig.errorLogFilePath should be(None)
  }

  it should "store the path to the error log file in the sync config" in {
    val logFile = Paths.get("var", "logs", "error-sync.log").toAbsolutePath
    val argsMap = ArgsMap + (SyncParameterManager.ErrorLogFileOption -> List(logFile.toString))

    val (config, _) = futureResult(extractSyncConfig(argsMap))
    config.logConfig.errorLogFilePath should be(Some(logFile))
  }

  it should "handle a sync log option with multiple values" in {
    val argsMap = ArgsMap + (SyncCliStreamConfig.SyncLogOption -> List("log1", "log2"))

    expectFailedFuture(extractSyncConfig(argsMap),
      SyncCliStreamConfig.SyncLogOption, "Single value expected")
  }

  it should "return correct default options related to encryption" in {
    val DefCryptConfig = CryptConfig(srcPassword = None, dstPassword = None,
      srcCryptMode = CryptMode.None, dstCryptMode = CryptMode.None,
      cryptCacheSize = SyncParameterManager.DefaultCryptCacheSize)
    val (config, _) = futureResult(extractSyncConfig(ArgsMap))

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

    val (syncConfig, _) = futureResult(extractSyncConfig(argsMap))
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

    expectFailedFuture(extractSyncConfig(argsMap),
      SyncParameterManager.SourceCryptModeOption, SyncParameterManager.DestCryptModeOption)
  }

  it should "handle invalid integer values for the crypt cache size option" in {
    val argsMap = ArgsMap + (SyncParameterManager.CryptCacheSizeOption -> List("big"))

    expectFailedFuture(extractSyncConfig(argsMap),
      "big", SyncParameterManager.CryptCacheSizeOption)
  }

  it should "reject a crypt password if encryption is disabled" in {
    val argsMap = ArgsMap + (SyncParameterManager.SourcePasswordOption -> List("srcSecret")) +
      (SyncParameterManager.DestPasswordOption -> List("dstSecret"))

    val (_, next) = futureResult(extractSyncConfig(argsMap))
    ParameterExtractor.checkParametersConsumed(next) match
      case Failure(exception: ParameterExtractionException) =>
        exception.failures.map(_.key.key) should contain only(SyncParameterManager.SourcePasswordOption,
          SyncParameterManager.DestPasswordOption)
      case r => fail("Unexpected result: " + r)
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

    val (config, _) = futureResult(extractSyncConfig(argsMap, consoleReader = reader))
    config.cryptConfig.srcPassword should be(Some(SrcPwd))
    config.cryptConfig.dstPassword should be(Some(DstPwd))
  }

  it should "handle a crypt cache size below the allowed minimum" in {
    val argsMap = ArgsMap + (SyncParameterManager.CryptCacheSizeOption ->
      List(String.valueOf(SyncParameterManager.MinCryptCacheSize - 1)))

    expectFailedFuture(extractSyncConfig(argsMap),
      "Crypt cache size must be greater or equal " + SyncParameterManager.MinCryptCacheSize)
  }

  it should "set a default log level" in {
    val (config, _) = futureResult(extractSyncConfig(ArgsMap))

    config.logConfig.logLevel should be(Level.WARN)
  }

  it should "handle the switches determining the log level" in {
    def checkLogLevel(switch: String, expectedLevel: Level): Unit =
      val argsMap = ArgsMap + (switch -> List("true"))
      val (config, _) = futureResult(extractSyncConfig(argsMap))

      config.logConfig.logLevel should be(expectedLevel)

    checkLogLevel(SyncParameterManager.LogLevelDebug, Level.DEBUG)
    checkLogLevel(SyncParameterManager.LogLevelInfo, Level.INFO)
    checkLogLevel(SyncParameterManager.LogLevelWarn, Level.WARN)
    checkLogLevel(SyncParameterManager.LogLevelError, Level.ERROR)
  }

  it should "allow overriding the log level" in {
    val argsMap = ArgsMap + (SyncParameterManager.LogLevelError -> List("true")) +
      (SyncParameterManager.LogLevelInfo -> List("true"))

    val (config, _) = futureResult(extractSyncConfig(argsMap))
    // The order of the options in the map is not deterministic.
    config.logConfig.logLevel == Level.ERROR || config.logConfig.logLevel == Level.INFO shouldBe true
  }

  it should "generate a default name for a sync stream" in {
    val argsMap = ArgsMap + (SyncCliStreamConfig.SyncMode -> List("true"))

    val (config, _) = futureResult(extractSyncConfig(argsMap))
    config.streamConfig.modeConfig match
      case syncConf: SyncCliStreamConfig.SyncStreamConfig =>
        syncConf.streamName should be("6Bl-KvnXhq_Tqsj091YQTGdP4I0=")
      case c => fail("Unexpected stream mode config: " + c)
  }

  it should "mark all options contained in the sync config as accessed" in {
    val otherOptions = Map("foo" -> List("v1"), "bar" -> List("v2", "v3"),
      SyncParameterManager.SourceCryptModeOption -> List("files"),
      SyncParameterManager.DestCryptModeOption -> List("files"))
    val argsMap = ArgsMap ++ otherOptions +
      (SyncCliStreamConfig.IgnoreTimeDeltaOption -> List("1"))

    val (_, updCtx) = futureResult(extractSyncConfig(argsMap))
    accessedKeys(updCtx) should contain allOf(SyncCliStreamConfig.DryRunOption,
      SyncCliStreamConfig.TimeoutOption, SyncParameterManager.LogFileOption, SyncCliStreamConfig.SyncLogOption,
      SyncCliStreamConfig.IgnoreTimeDeltaOption, SyncCliStreamConfig.OpsPerUnitOption,
      SyncParameterManager.SourcePasswordOption, SyncParameterManager.DestPasswordOption,
      SyncParameterManager.SourceCryptModeOption, SyncParameterManager.DestCryptModeOption)
  }

  it should "combine multiple error messages when parsing the sync config" in {
    val argsMap = Map(ParameterParser.InputParameter.key -> List(SourceUri),
      SyncCliStreamConfig.TimeoutOption -> List("invalidTimeout"),
      SyncParameterManager.CryptCacheSizeOption -> List("invalidCacheSize"))

    expectFailedFuture(extractSyncConfig(argsMap),
      "destinationURI: Mandatory parameter", SyncCliStreamConfig.TimeoutOption,
      SyncParameterManager.CryptCacheSizeOption)
  }

  "SyncConfig" should "return a normalized instance if the switched flag is set" in {
    val orgCryptConfig = CryptConfig(srcPassword = Some("pwd-src"), srcCryptMode = CryptMode.FilesAndNames,
      dstPassword = Some("pwd-dst"), dstCryptMode = CryptMode.Files, cryptCacheSize = 55)
    val expCryptConfig = CryptConfig(dstPassword = Some("pwd-src"), dstCryptMode = CryptMode.FilesAndNames,
      srcPassword = Some("pwd-dst"), srcCryptMode = CryptMode.Files, cryptCacheSize = 55)
    val logConfig = LogConfig(logFilePath = Some(Paths get "log"), errorLogFilePath = Some(Paths get "err"),
      logLevel = Level.INFO)
    val orgMirrorStreamConfig = MirrorStreamConfig(Some(Paths get "syncLog"), switched = true)
    val streamConfig = StreamConfig(dryRun = false, timeout = 1.minute,
      ignoreTimeDelta = Some(IgnoreTimeDelta(100.seconds)),
      opsPerUnit = Some(100), throttleUnit = Throttle.TimeUnit.Minute, orgMirrorStreamConfig)
    val expStreamConfig = streamConfig.copy(modeConfig = orgMirrorStreamConfig.copy(switched = false))
    val orgConfig = SyncConfig(srcUri = "/src", dstUri = "/dst", srcConfig = mock[StructureAuthConfig],
      dstConfig = mock[StructureAuthConfig], logConfig = logConfig, cryptConfig = orgCryptConfig,
      streamConfig = streamConfig, filterData = mock[SyncFilterData])
    val expNormalized = orgConfig.copy(srcUri = orgConfig.dstUri, dstUri = orgConfig.srcUri,
      srcConfig = orgConfig.dstConfig, dstConfig = orgConfig.srcConfig, cryptConfig = expCryptConfig,
      streamConfig = expStreamConfig)

    orgConfig.normalized should be(expNormalized)
  }
