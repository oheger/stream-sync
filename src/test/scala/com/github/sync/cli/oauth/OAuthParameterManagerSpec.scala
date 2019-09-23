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

package com.github.sync.cli.oauth

import java.nio.file.Paths

import com.github.sync.AsyncTestHelper
import com.github.sync.cli.ParameterManager.{InputOption, Parameters}
import com.github.sync.cli.{ConsoleReader, ParameterManager}
import org.mockito.Mockito._
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object OAuthParameterManagerSpec {
  /** Test base name of an OAuth provider. */
  private val ProviderName = "testIDP"

  /** Test storage path. */
  private val StoragePath = "/my/storage/path"

  /** A test password. */
  private val Password = "tiger"

  /** A test command name. */
  private val CommandName = "check"

  /** A map with default parameter options. */
  private val DefaultOptions = createBasicParametersMap()

  /**
    * Implicit conversion from a simple map to a ''Parameters'' object. As we
    * use single values in most cases, this simplifies tests.
    *
    * @param map the map with single values
    * @return the resulting ''Parameters'' object
    */
  implicit def toParametersMap(map: Map[String, String]): Parameters =
    map map { e => (e._1, List(e._2)) }

  /**
    * Creates a map with default values for the basic command line options.
    *
    * @return the map with default option values
    */
  private def createBasicParametersMap(): Map[String, String] =
    Map(OAuthParameterManager.StoragePathOption -> StoragePath,
      OAuthParameterManager.NameOption -> ProviderName,
      OAuthParameterManager.PasswordOption -> Password,
      InputOption -> CommandName)
}

/**
  * Test class for ''OAuthParameterManager''.
  */
class OAuthParameterManagerSpec extends FlatSpec with Matchers with AsyncTestHelper with MockitoSugar {

  import OAuthParameterManagerSpec._
  import ParameterManager._

  /** The implicit console reader needed for parameter processing. */
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
    val exception = expectFailedFuture[IllegalArgumentException](future)
    msgParts foreach (part => exception.getMessage should include(part))
    exception.getMessage
  }

  "OAuthParameterManager" should "extract a valid command config" in {
    val (config, nextParams) = futureResult(OAuthParameterManager.extractCommandConfig(DefaultOptions))

    config.command should be(CommandName)
    config.storageConfig.rootDir should be(Paths.get(StoragePath))
    config.storageConfig.baseName should be(ProviderName)
    config.storageConfig.optPassword.get.secret should be(Password)
    nextParams.accessedParameters should contain only(OAuthParameterManager.StoragePathOption,
      OAuthParameterManager.PasswordOption, OAuthParameterManager.NameOption,
      OAuthParameterManager.EncryptOption, ParameterManager.InputOption)
  }

  it should "report missing mandatory parameters when creating a command config" in {
    val args = Map(OAuthParameterManager.PasswordOption -> Password)

    expectFailedFuture(OAuthParameterManager.extractCommandConfig(args),
      OAuthParameterManager.NameOption, OAuthParameterManager.StoragePathOption,
      OAuthParameterManager.CommandOption, "no command")
  }

  it should "reject a command line with multiple commands" in {
    val parameters: Parameters = DefaultOptions
    val wrongParameters = parameters.copy(parametersMap =
      parameters.parametersMap + (ParameterManager.InputOption -> List("cmd1", "cmd2")))

    expectFailedFuture(OAuthParameterManager.extractCommandConfig(wrongParameters),
      OAuthParameterManager.CommandOption, "too many")
  }

  it should "read the password for the storage config from the console if required" in {
    val ec = implicitly[ExecutionContext]
    val reader = mock[ConsoleReader]
    when(reader.readOption(OAuthParameterManager.PasswordOption, password = true))
      .thenReturn(Password)
    val args = DefaultOptions - OAuthParameterManager.PasswordOption

    val (config, _) = futureResult(OAuthParameterManager.extractCommandConfig(args)(ec = ec, consoleReader = reader))
    config.storageConfig.optPassword.get.secret should be(Password)
  }

  it should "evaluate a positive encrypt option when creating a storage configuration" in {
    val ec = implicitly[ExecutionContext]
    val reader = mock[ConsoleReader]
    when(reader.readOption(OAuthParameterManager.PasswordOption, password = true))
      .thenReturn(Password)
    val args = DefaultOptions - OAuthParameterManager.PasswordOption + (OAuthParameterManager.EncryptOption -> "true")

    val (config, _) = futureResult(OAuthParameterManager.extractCommandConfig(args)(ec = ec, consoleReader = reader))
    config.storageConfig.optPassword.get.secret should be(Password)
  }

  it should "support an undefined password for the storage configuration" in {
    val args = DefaultOptions - OAuthParameterManager.PasswordOption

    val (config, _) = futureResult(OAuthParameterManager.extractCommandConfig(args, needPassword = false))
    config.storageConfig.optPassword should be(None)
    verifyZeroInteractions(consoleReader)
  }

  it should "evaluate a negative encrypt option when creating a storage configuration" in {
    val args = DefaultOptions - OAuthParameterManager.PasswordOption + (OAuthParameterManager.EncryptOption -> "false")

    val (config, _) = futureResult(OAuthParameterManager.extractCommandConfig(args))
    config.storageConfig.optPassword should be(None)
    verifyZeroInteractions(consoleReader)
  }

  it should "detect an invalid encryption option" in {
    val args = DefaultOptions - OAuthParameterManager.PasswordOption + (OAuthParameterManager.EncryptOption -> "?")

    expectFailedFuture(OAuthParameterManager.extractCommandConfig(args),
      OAuthParameterManager.EncryptOption)
    verifyZeroInteractions(consoleReader)
  }
}
