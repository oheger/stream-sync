/*
 * Copyright 2018-2026 The Developers Team.
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

package com.github.sync.cli.credentials

import com.github.scli.ParameterExtractor.{ExtractionContext, ParameterExtractionException, Parameters, tryExtractor}
import com.github.scli.{ConsoleReader, DummyConsoleReader, ParameterParser}
import com.github.sync.cli.{CliActorSystemLifeCycle, ExtractorTestHelper}
import org.mockito.Mockito.when
import org.scalatest.Assertion
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

object CredentialsParameterManagerSpec:
  /** The test path to the credentials file. */
  private val CredentialsFilePath = "/my/credentials/file.crypt"

  /** The test secret to encrypt the credentials file. */
  private val TestSecret = "my-credentials-secret"

  /**
    * Creates a map with default values for the basic command line options.
    *
    * @param command the name of the command
    * @return the map with default option values
    */
  private def createBasicParametersMap(command: String): Map[String, String] =
    Map(CredentialsParameterManager.CredentialsFileOption -> CredentialsFilePath,
      CredentialsParameterManager.SecretOption -> TestSecret,
      ParameterParser.InputParameter.key -> command)

  /**
    * Helper function to check the credentials command config extractor. This
    * extractor is executed on the passed in arguments.
    *
    * @param parameters the object with parsed parameters
    * @param reader     an optional console reader
    * @return a [[Future]] with the config and updated extraction context
    */
  private def extractCommandConfigParams(parameters: Parameters, reader: ConsoleReader = DummyConsoleReader):
  Future[(CredentialsParameterManager.CommandConfig, ExtractionContext)] =
    val context = ExtractorTestHelper.toExtractionContext(parameters, reader)
    Future.fromTry(tryExtractor(CredentialsParameterManager.commandConfigExtractor, context))

  /**
    * Convenience function to run the credentials command config extractor on a
    * map with single-valued parameters.
    *
    * @param paramsMap the map with parameters
    * @param reader    an optional console reader
    * @return a [[Future]] with the config and updated extraction context
    */
  private def extractCommandConfig(paramsMap: Map[String, String], reader: ConsoleReader = DummyConsoleReader):
  Future[(CredentialsParameterManager.CommandConfig, ExtractionContext)] =
    val params = ExtractorTestHelper.toParameters(ExtractorTestHelper.toParametersMap(paramsMap))
    extractCommandConfigParams(params, reader)
end CredentialsParameterManagerSpec

/**
  * Test class for [[CredentialsParameterManager]].
  */
class CredentialsParameterManagerSpec extends AsyncFlatSpec with Matchers with MockitoSugar:

  import CredentialsParameterManagerSpec.*

  /**
    * Expects that the given [[Future]] fails with a
    * [[ParameterExtractionException]] whose message contains all the provided
    * message parts.
    *
    * @param msgParts the expected message parts
    * @param future   the [[Future]] to check
    * @return the result of the evaluation
    */
  private def recoverToSucceededIfParamsException(msgParts: String*)(future: Future[Any]): Future[Assertion] =
    recoverToExceptionIf[ParameterExtractionException](future) map : exception =>
      val message = exception.failures.mkString(" ")
      forEvery(msgParts):
        message should include(_)

  "CredentialsParameterManager" should "extract a valid config for the list command" in :
    val params = createBasicParametersMap(CredentialsParameterManager.CommandListCredentials)

    extractCommandConfig(params) map : (config, ctx) =>
      ExtractorTestHelper.accessedKeys(ctx) should contain only(
        CredentialsParameterManager.CredentialsFileOption,
        CredentialsParameterManager.SecretOption,
        ParameterParser.InputParameter.key,
        CliActorSystemLifeCycle.FileOption
      )
      config match
        case c: CredentialsParameterManager.ListCommandConfig =>
          c.credentialsFilePath.toString should be(CredentialsFilePath)
          c.secret.secret should be(TestSecret)
        case c => fail("Unexpected configuration: " + c)

  it should "report missing mandatory basic options" in :
    val args = Map(ParameterParser.InputParameter.key -> CredentialsParameterManager.CommandListCredentials)

    recoverToSucceededIfParamsException(CredentialsParameterManager.CredentialsFileOption):
      extractCommandConfig(args)

  it should "report a missing command" in :
    val args = createBasicParametersMap("foo") - ParameterParser.InputParameter.key
    recoverToSucceededIfParamsException(CredentialsParameterManager.CommandOption):
      extractCommandConfig(args)

  it should "read the secret for the credentials file from the console if required" in :
    val reader = mock[ConsoleReader]
    when(reader.readOption(CredentialsParameterManager.SecretOption, password = true))
      .thenReturn(TestSecret)
    val args = createBasicParametersMap(CredentialsParameterManager.CommandListCredentials) -
      CredentialsParameterManager.SecretOption

    extractCommandConfig(args, reader) map : (config, _) =>
      config.secret.secret should be(TestSecret)

  it should "extract a valid configuration for the add command" in :
    val CredentialsKey = "my-new-credential"
    val CredentialsValue = "s3cretVa!lue"
    val params = createBasicParametersMap(CredentialsParameterManager.CommandAddCredential) +
      (CredentialsParameterManager.KeyOption -> CredentialsKey) +
      (CredentialsParameterManager.ValueOption -> CredentialsValue)

    extractCommandConfig(params) map : (config, _) =>
      config match
        case c: CredentialsParameterManager.AddCommandConfig =>
          c.credentialsFilePath.toString should be(CredentialsFilePath)
          c.secret.secret should be(TestSecret)
          c.key should be(CredentialsKey)
          c.value.secret should be(CredentialsValue)
        case c => fail("Unexpected configuration: " + c)

  it should "read the secret value of a credential from the console if required" in :
    val CredentialsKey = "my-new-credential"
    val CredentialsValue = "s3cretVa!lueFromCons0le"
    val reader = mock[ConsoleReader]
    when(reader.readOption(CredentialsParameterManager.ValueOption, password = true))
      .thenReturn(CredentialsValue)
    val params = createBasicParametersMap(CredentialsParameterManager.CommandAddCredential) +
      (CredentialsParameterManager.KeyOption -> CredentialsKey)

    extractCommandConfig(params, reader = reader) map : (config, _) =>
      config match
        case c: CredentialsParameterManager.AddCommandConfig =>
          c.value.secret should be(CredentialsValue)
        case c => fail("Unexpected configuration: " + c)

  it should "extract a valid configuration for the get command" in :
    val CredentialKey = "theOneIamInterestedIn"
    val params = createBasicParametersMap(CredentialsParameterManager.CommandGetCredential) +
      (CredentialsParameterManager.KeyOption -> CredentialKey)

    extractCommandConfig(params) map : (config, _) =>
      config match
        case c: CredentialsParameterManager.GetCommandConfig =>
          c.credentialsFilePath.toString should be(CredentialsFilePath)
          c.secret.secret should be(TestSecret)
          c.key should be(CredentialKey)
        case c => fail("Unexpected configuration: " + c)
