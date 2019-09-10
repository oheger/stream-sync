/*
 * Copyright 2018-2019 The Developers Team.
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

import java.nio.file.{Path, Paths}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import akka.util.Timeout
import com.github.sync.cli.ParameterManager.Parameters
import com.github.sync.cli.SyncParameterManager.{ApplyModeNone, ApplyModeTarget, CryptMode}
import com.github.sync.{AsyncTestHelper, FileTestHelper}
import org.scalatest._
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

object SyncParameterManagerSpec {
  /** Test source URI. */
  private val SourceUri = "/test/source/uri"

  /** Test destination URI. */
  private val DestinationUri = "/test/destination/uri"

  /** A test timeout value (in seconds). */
  private val TimeoutValue = 44

  /** A map with test parameter values. */
  private val ArgsMap = Map(ParameterManager.InputOption -> List(DestinationUri, SourceUri),
    SyncParameterManager.TimeoutOption -> List(TimeoutValue.toString))
}

/**
  * Test class for ''SyncParameterManager''. This class also tests
  * functionality of the generic ''ParameterManager'' class.
  */
class SyncParameterManagerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
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
    * @param future the future to be checked
    * @param msg    text to be expected in the exception message
    * @return the error message from the exception
    */
  private def expectFailedFuture(future: Future[_], msg: String): String = {
    val exception = expectFailedFuture[IllegalArgumentException](future)
    exception.getMessage should include(msg)
    exception.getMessage
  }

  /**
    * Creates a temporary file that contains the given parameter strings.
    *
    * @param args the parameters to store in the file
    * @return the path to the newly created file
    */
  private def createParameterFile(args: String*): Path =
    createDataFile(parameterFileContent(args: _*))

  /**
    * Generates the content of a parameters file from the given parameter
    * strings.
    *
    * @param args the parameters to store in the file
    * @return the content of the parameter file as string
    */
  private def parameterFileContent(args: String*): String =
    args.mkString("\r\n")

  /**
    * Adds a parameter to read the given file to a parameter list.
    *
    * @param path    the path to the file to be read
    * @param argList the original parameter list
    * @return the parameter list with the file parameter added
    */
  private def appendFileParameter(path: Path, argList: List[String]): List[String] =
    ParameterManager.FileOption :: path.toString :: argList

  /**
    * Helper method for calling the parameter manager to parse a list of
    * parameters.
    *
    * @param args the list of parameters to be parsed
    * @return the parameters map as result of the parse operation
    */
  private def parseParameters(args: Seq[String]): Parameters =
    futureResult(parseParametersFuture(args))

  /**
    * Helper method for calling the parameter manager's method to parse a list
    * of parameters and returning the future result.
    *
    * @param args the list of parameters to be parsed
    * @return the ''Future'' with the parse result
    */
  private def parseParametersFuture(args: Seq[String]): Future[Parameters] = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    ParameterManager.parseParameters(args)
  }

  "ParameterManager" should "parse an empty sequence of arguments" in {
    val params = parseParameters(Nil)

    params.parametersMap should have size 0
    params.accessedParameters should have size 0
  }

  it should "correctly parse non-option parameters" in {
    val syncUris = List("uri1", "uri2")
    val expArgMap = Map(ParameterManager.InputOption -> syncUris.reverse)

    val params = parseParameters(syncUris)
    params.parametersMap should be(expArgMap)
    params.accessedParameters should have size 0
  }

  it should "correctly parse arguments with options" in {
    val args = Array("--opt1", "opt1Val1", "--opt2", "opt2Val1", "--opt1", "opt1Val2")
    val expArgMap = Map("--opt1" -> List("opt1Val2", "opt1Val1"),
      "--opt2" -> List("opt2Val1"))

    val params = parseParameters(args)
    params.parametersMap should be(expArgMap)
  }

  it should "fail with a correct message if an option is the last argument" in {
    val undefOption = "--undefinedOption"
    val args = List("--opt1", "optValue", undefOption)

    expectFailedFuture(parseParametersFuture(args), undefOption)
  }

  it should "convert options to lower case" in {
    val args = List("--TestOption", "TestValue", "--FOO", "BAR", "testUri")
    val expArgMap = Map("--testoption" -> List("TestValue"),
      "--foo" -> List("BAR"),
      ParameterManager.InputOption -> List("testUri"))

    val params = parseParameters(args)
    params.parametersMap should be(expArgMap)
  }

  it should "validate a Parameters object with all parameters consumed" in {
    val argsMap = Map("--foo" -> List("v1"), "--bar" -> List("v2", "v3"))
    val accessed = Set("--foo", "--bar")
    val params = Parameters(argsMap, accessed)
    val result = futureResult(ParameterManager.checkParametersConsumed(params))

    result should be(params)
  }

  it should "fail the check for consumed parameters if there are remaining parameters" in {
    val argsMap = Map("foo" -> List("bar"), "bar" -> List("v"), "baz" -> List("vv"))
    val accessed = Set("baz")

    val ex = expectFailedFuture[IllegalArgumentException](
      ParameterManager.checkParametersConsumed(Parameters(argsMap, accessed)))
    ex.getMessage should include("foo")
    ex.getMessage should include("bar")
  }

  it should "add the content of parameter files to command line options" in {
    val OptionName1 = "--foo"
    val OptionName2 = "--test"
    val Opt1Val1 = "bar"
    val Opt1Val2 = "baz"
    val Opt2Val = "true"
    val uri1 = "testUri1"
    val uri2 = "testUri2"
    val args = appendFileParameter(createParameterFile(OptionName1, Opt1Val1, uri1),
      appendFileParameter(createParameterFile(OptionName2, Opt2Val),
        OptionName1 :: Opt1Val2 :: uri2 :: Nil))

    val argsMap = parseParameters(args).parametersMap
    argsMap(OptionName1) should contain only(Opt1Val1, Opt1Val2)
    argsMap(OptionName2) should contain only Opt2Val
    argsMap.keys should not contain ParameterManager.FileOption

    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    Set(config.syncUris._1, config.syncUris._2) should contain only(uri1, uri2)
  }

  it should "parse parameter files defined in another parameter file" in {
    val OptionName1 = "--top-level"
    val Option1Value = "onCommandLine"
    val OptionName2 = "--level1"
    val Option2Value = "inFirstFile"
    val OptionName3 = "--deep"
    val Option3Value = "inNestedFile"
    val nestedFile = createParameterFile(OptionName3, Option3Value)
    val args = appendFileParameter(
      createParameterFile(ParameterManager.FileOption, nestedFile.toString,
        OptionName2, Option2Value), OptionName1 :: Option1Value :: Nil)
    val expArgs = Map(OptionName1 -> List(Option1Value),
      OptionName2 -> List(Option2Value),
      OptionName3 -> List(Option3Value))

    val argsMap = parseParameters(args).parametersMap
    argsMap should be(expArgs)
  }

  it should "deal with cyclic references in parameter files" in {
    val file1 = createFileReference()
    val file3 = createParameterFile(ParameterManager.FileOption, file1.toString, "--op3", "v3")
    val file2 = createParameterFile(ParameterManager.FileOption, file3.toString, "--op2", "v2")
    writeFileContent(file1, parameterFileContent(ParameterManager.FileOption, file2.toString,
      "--op1", "v1", ParameterManager.FileOption, file2.toString))
    val args = appendFileParameter(file1, Nil)
    val expArgs = Map("--op1" -> List("v1"), "--op2" -> List("v2"), "--op3" -> List("v3"))

    val argsMap = parseParameters(args).parametersMap
    argsMap should be(expArgs)
  }

  it should "ignore empty lines in parameter files" in {
    val args = appendFileParameter(createParameterFile("--foo", "bar", "", "--foo", "baz"),
      "--test" :: "true" :: Nil)

    val argsMap = parseParameters(args).parametersMap
    argsMap.keys should contain only("--foo", "--test")
  }

  it should "extract URI parameters if they are present" in {
    val (params, config) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))
    config.syncUris._1 should be(SourceUri)
    config.syncUris._2 should be(DestinationUri)
    params.accessedParameters should contain allElementsOf ArgsMap.keySet
  }

  it should "reject URI parameters if there are more than 2" in {
    val argsMap = ArgsMap + (ParameterManager.InputOption -> List("u1", "u2", "u3"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap), "Too many sync URIs")
  }

  it should "reject URI parameters if no destination URI is provided" in {
    val argsMap = ArgsMap + (ParameterManager.InputOption -> List("u1"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Missing destination URI")
  }

  it should "reject URI parameters if no URIs are provided" in {
    val argsMap = ArgsMap + (ParameterManager.InputOption -> List.empty[String])

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Missing URIs for source and destination")
  }

  it should "reject URI parameters if no non-option parameters are provided" in {
    val argsMap = ArgsMap - ParameterManager.InputOption

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Missing URIs for source and destination")
  }

  it should "not return a single option value if there are multiple" in {
    val argsMap = ArgsMap + (SyncParameterManager.TimeoutOption -> List("bar", "baz"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      SyncParameterManager.TimeoutOption + " has multiple values")
  }

  it should "return a default apply mode" in {
    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))
    config.applyMode should be(ApplyModeTarget(DestinationUri))
  }

  it should "return a target apply mode with the specified URI" in {
    val applyUri = "/dest/apply/uri"
    val argsMap = ArgsMap + (SyncParameterManager.ApplyModeOption -> List("Target:" + applyUri))

    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.applyMode should be(ApplyModeTarget(applyUri))
  }

  it should "return the apply mode NONE" in {
    val argsMap = ArgsMap + (SyncParameterManager.ApplyModeOption -> List("none"))

    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
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

    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.timeout should be(SyncParameterManager.DefaultTimeout)
  }

  it should "return the configured timeout option value" in {
    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))
    config.timeout should be(Timeout(TimeoutValue.seconds))
  }

  it should "handle an invalid timeout value" in {
    val timeoutStr = "invalidTimeout!"
    val argsMap = ArgsMap + (SyncParameterManager.TimeoutOption -> List(timeoutStr))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Invalid timeout value: '" + timeoutStr)
  }

  it should "have an undefined log file option if none is specified" in {
    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))
    config.logFilePath should be(None)
  }

  it should "store the path to a log file in the sync config" in {
    val logFile = Paths.get("var", "logs", "sync.log").toAbsolutePath
    val argsMap = ArgsMap + (SyncParameterManager.LogFileOption -> List(logFile.toString))

    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.logFilePath should be(Some(logFile))
  }

  it should "handle a log file option with multiple values" in {
    val argsMap = ArgsMap + (SyncParameterManager.LogFileOption -> List("log1", "log2"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      SyncParameterManager.LogFileOption + ": only a single value")
  }

  it should "have an undefined sync log option if none is specified" in {
    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))
    config.syncLogPath should be(None)
  }

  it should "store the path to the sync log file in the sync config" in {
    val syncLogFile = Paths.get("data", "sync", "log", "sync.log").toAbsolutePath
    val argsMap = ArgsMap + (SyncParameterManager.SyncLogOption -> List(syncLogFile.toString))

    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.syncLogPath should be(Some(syncLogFile))
  }

  it should "handle a sync log option with multiple values" in {
    val argsMap = ArgsMap + (SyncParameterManager.SyncLogOption -> List("log1", "log2"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      SyncParameterManager.SyncLogOption + ": only a single value")
  }

  it should "handle an undefined option for the file times threshold" in {
    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))

    config.ignoreTimeDelta should be(None)
  }

  it should "evaluate the threshold for file time deltas" in {
    val Delta = 28
    val argsMap = ArgsMap + (SyncParameterManager.IgnoreTimeDeltaOption -> List(Delta.toString))

    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.ignoreTimeDelta should be(Some(Delta))
  }

  it should "handle an invalid threshold for file time deltas" in {
    val InvalidValue = "not a threshold for a time delta!"
    val argsMap = ArgsMap + (SyncParameterManager.IgnoreTimeDeltaOption -> List(InvalidValue))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Invalid threshold for file time deltas: '" + InvalidValue)
  }

  it should "handle an undefined option for the operations per second" in {
    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))

    config.opsPerSecond should be(None)
  }

  it should "evaluate the threshold for the operations per second" in {
    val OpsCount = 17
    val argsMap = ArgsMap + (SyncParameterManager.OpsPerSecondOption -> List(OpsCount.toString))

    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.opsPerSecond should be(Some(OpsCount))
  }

  it should "handle an invalid threshold for the operations per second" in {
    val InvalidValue = "not a valid number of ops per sec"
    val argsMap = ArgsMap + (SyncParameterManager.OpsPerSecondOption -> List(InvalidValue))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Invalid number of operations per second: '" + InvalidValue)
  }

  it should "return correct default options related to encryption" in {
    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(ArgsMap))

    config.srcPassword should be(None)
    config.srcCryptMode shouldBe CryptMode.None
    config.dstPassword should be(None)
    config.dstCryptMode shouldBe CryptMode.None
    config.cryptCacheSize should be(SyncParameterManager.DefaultCryptCacheSize)
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

    val (_, config) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    config.srcPassword should be(Some(SrcPwd))
    config.dstPassword should be(Some(DstPwd))
    config.srcCryptMode shouldBe CryptMode.Files
    config.dstCryptMode shouldBe CryptMode.FilesAndNames
    config.cryptCacheSize should be(CacheSize)
  }

  it should "handle invalid enum values for encryption-related flags" in {
    val argsMap = ArgsMap + (SyncParameterManager.SourceCryptModeOption -> List("of course")) +
      (SyncParameterManager.DestCryptModeOption -> List("full encryption"))

    val msg = expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      SyncParameterManager.SourceCryptModeOption)
    msg should include(SyncParameterManager.DestCryptModeOption)
  }

  it should "handle invalid integer values for the crypt cache size option" in {
    val argsMap = ArgsMap + (SyncParameterManager.CryptCacheSizeOption -> List("big"))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Invalid crypt cache size")
  }

  it should "handle a crypt cache size below the allowed minimum" in {
    val argsMap = ArgsMap + (SyncParameterManager.CryptCacheSizeOption ->
      List(String.valueOf(SyncParameterManager.MinCryptCacheSize - 1)))

    expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "Crypt cache size must be greater or equal " + SyncParameterManager.MinCryptCacheSize)
  }

  it should "mark all options contained in the sync config as accessed" in {
    val otherOptions = Map("foo" -> List("v1"), "bar" -> List("v2", "v3"))
    val argsMap = ArgsMap ++ otherOptions +
      (SyncParameterManager.IgnoreTimeDeltaOption -> List("1"))

    val (updArgs, _) = futureResult(SyncParameterManager.extractSyncConfig(argsMap))
    updArgs.accessedParameters should contain allOf(SyncParameterManager.ApplyModeOption,
      SyncParameterManager.TimeoutOption, SyncParameterManager.LogFileOption, SyncParameterManager.SyncLogOption,
      SyncParameterManager.IgnoreTimeDeltaOption, SyncParameterManager.OpsPerSecondOption,
      SyncParameterManager.SourcePasswordOption, SyncParameterManager.DestPasswordOption,
      SyncParameterManager.SourceCryptModeOption, SyncParameterManager.DestCryptModeOption)
  }

  it should "combine multiple error messages when parsing the sync config" in {
    val argsMap = Map(ParameterManager.InputOption -> List(SourceUri),
      SyncParameterManager.ApplyModeOption -> List("invalidApplyMode"),
      SyncParameterManager.TimeoutOption -> List("invalidTimeout"),
      SyncParameterManager.CryptCacheSizeOption -> List("invalidCacheSize"))

    val msg = expectFailedFuture(SyncParameterManager.extractSyncConfig(argsMap),
      "destination URI")
    msg should include("apply mode")
    msg should include("timeout value")
    msg should include("crypt cache size")
  }
}
