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

package com.github.sync.cli.oauth

import java.nio.file.Paths
import com.github.scli.ParameterExtractor.{ExtractionContext, Parameters, tryExtractor}
import com.github.scli.{ConsoleReader, DummyConsoleReader, ParameterParser}
import com.github.sync.AsyncTestHelper
import com.github.sync.cli.oauth.OAuthParameterManager.{CommandConfig, InitCommandConfig, ListTokensCommandConfig, LoginCommandConfig, RemoveCommandConfig}
import com.github.sync.cli.{CliActorSystemLifeCycle, ExtractorTestHelper}
import com.github.sync.oauth.SyncOAuthStorageConfig
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future
import scala.language.implicitConversions

object OAuthParameterManagerSpec:
  /** Test base name of an OAuth provider. */
  private val ProviderName = "testIDP"

  /** Test storage path. */
  private val StoragePath = "/my/storage/path"

  /** A test password. */
  private val Password = "tiger"

  /** A test authorization URL. */
  private val AuthEndpointUrl = "https://my-test-provider.org/auth"

  /** A test token endpoint URL. */
  private val TokenEndpointUrl = "https://my-test-provider.org/tokens"

  /** A test redirect URI. */
  private val Redirect = "https://my-domain.org/redirect"

  /** A test client ID. */
  private val ClientID = "testOAuthClient"

  /** A test client secret. */
  private val ClientSecret = "testClientSecret!?"

  /** A set with test scope values. */
  private val Scopes = Set("read", "write", "draw", "pull", "exec")

  /**
    * Creates a map with default values for the basic command line options.
    *
    * @param command the name of the command
    * @return the map with default option values
    */
  private def createBasicParametersMap(command: String): Map[String, String] =
    Map(OAuthParameterManager.StoragePathOption -> StoragePath,
      OAuthParameterManager.NameOption -> ProviderName,
      OAuthParameterManager.PasswordOption -> Password,
      ParameterParser.InputParameter.key -> command)

  /**
    * Helper function to check the OAuth command config extractor. This
    * extractor is executed on the passed in arguments.
    *
    * @param parameters the object with parsed parameters
    * @param reader     an optional console reader
    * @return a ''Future'' with the config and updated extraction context
    */
  private def extractCommandConfigParams(parameters: Parameters, reader: ConsoleReader = DummyConsoleReader):
  Future[(CommandConfig, ExtractionContext)] =
    val context = ExtractorTestHelper.toExtractionContext(parameters, reader)
    Future.fromTry(tryExtractor(OAuthParameterManager.commandConfigExtractor, context))

  /**
    * Convenience function to run the OAuth command config extractor on a map
    * with single-valued parameters.
    *
    * @param paramsMap the map with parameters
    * @param reader    an optional console reader
    * @return a ''Future'' with the config and updated extraction context
    */
  private def extractCommandConfig(paramsMap: Map[String, String], reader: ConsoleReader = DummyConsoleReader):
  Future[(CommandConfig, ExtractionContext)] =
    val params = ExtractorTestHelper.toParameters(ExtractorTestHelper.toParametersMap(paramsMap))
    extractCommandConfigParams(params, reader)

  /**
    * Creates a map with command line options for an init command.
    *
    * @param initArgs the command-specific options
    * @return a map with full command line options for an init command
    */
  private def createInitParametersMap(initArgs: Map[String, String]): Map[String, String] =
    createBasicParametersMap(OAuthParameterManager.CommandInitIDP) ++ initArgs

  /**
    * Returns a string with the test scope values separated by the given
    * separator.
    *
    * @param separator the separator
    * @return the string with scope values
    */
  private def scopeString(separator: String): String = Scopes.mkString(separator)

/**
  * Test class for ''OAuthParameterManager''.
  */
class OAuthParameterManagerSpec extends AnyFlatSpec with Matchers with AsyncTestHelper with MockitoSugar:

  import OAuthParameterManagerSpec._
  import com.github.scli.ParameterExtractor._

  /**
    * Expects a failed future from a parsing operation. It is checked whether
    * the future is actually failed with an ''ParameterExtractionException''
    * that has a specific error message.
    *
    * @param future   the future to be checked
    * @param msgParts text parts to be expected in the exception message
    * @return the error message from the exception
    */
  private def expectFailedFuture(future: Future[_], msgParts: String*): String =
    val exception = expectFailedFuture[ParameterExtractionException](future)
    val message = exception.failures.mkString(" ")
    msgParts foreach (part => message should include(part))
    message

  /**
    * Checks whether the correct options for the storage configuration have
    * been extracted.
    *
    * @param storageConfig the configuration to be checked
    * @param withPwd       flag whether a password is expected in the configuration
    */
  private def checkStorageConfig(storageConfig: SyncOAuthStorageConfig, withPwd: Boolean = true): Unit =
    storageConfig.rootDir should be(Paths.get(StoragePath))
    storageConfig.baseName should be(ProviderName)
    if withPwd then
      storageConfig.optPassword.get.secret should be(Password)
    else
      storageConfig.optPassword should be(None)

  "OAuthParameterManager" should "extract a valid remove command config" in {
    val params = createBasicParametersMap(OAuthParameterManager.CommandRemoveIDP) -
      OAuthParameterManager.PasswordOption
    val (config, nextCtx) = futureResult(extractCommandConfig(params))

    config match
      case RemoveCommandConfig(storageConfig) =>
        checkStorageConfig(storageConfig, withPwd = false)
      case c =>
        fail("Unexpected result: " + c)
    ExtractorTestHelper.accessedKeys(nextCtx) should contain only(OAuthParameterManager.StoragePathOption,
      OAuthParameterManager.NameOption, OAuthParameterManager.EncryptOption,
      ParameterParser.InputParameter.key, CliActorSystemLifeCycle.FileOption)
  }

  it should "extract a valid login command config" in {
    val params = createBasicParametersMap(OAuthParameterManager.CommandLoginIDP)
    val (config, nextCtx) = futureResult(extractCommandConfig(params))

    config match
      case LoginCommandConfig(storageConfig) =>
        checkStorageConfig(storageConfig)
      case c =>
        fail("Unexpected result: " + c)
    ExtractorTestHelper.accessedKeys(nextCtx) should contain only(OAuthParameterManager.StoragePathOption,
      OAuthParameterManager.PasswordOption, OAuthParameterManager.NameOption,
      OAuthParameterManager.EncryptOption, ParameterParser.InputParameter.key, CliActorSystemLifeCycle.FileOption)
  }
  
  it should "extract a valid list tokens command config" in:
    val params = createBasicParametersMap(OAuthParameterManager.CommandListTokens)
    val (config, nextCtx) = futureResult(extractCommandConfig(params))

    config match
      case ListTokensCommandConfig(storageConfig) =>
        checkStorageConfig(storageConfig)
      case c =>
        fail("Unexpected result: " + c)
    ExtractorTestHelper.accessedKeys(nextCtx) should contain only(
      OAuthParameterManager.StoragePathOption,
      OAuthParameterManager.PasswordOption,
      OAuthParameterManager.NameOption,
      OAuthParameterManager.EncryptOption,
      ParameterParser.InputParameter.key,
      CliActorSystemLifeCycle.FileOption
    )

  it should "report missing mandatory parameters when creating a storage config" in {
    val args = Map(OAuthParameterManager.PasswordOption -> Password,
      ParameterParser.InputParameter.key -> OAuthParameterManager.CommandLoginIDP)

    expectFailedFuture(extractCommandConfig(args),
      OAuthParameterManager.NameOption, OAuthParameterManager.StoragePathOption)
  }

  it should "report a missing command" in {
    val args = Map(OAuthParameterManager.PasswordOption -> Password)

    val output = expectFailedFuture(extractCommandConfig(args),
      OAuthParameterManager.CommandOption)
    output should not include OAuthParameterManager.NameOption
  }

  it should "reject a command line with multiple commands" in {
    val parameters = createBasicParametersMap(OAuthParameterManager.CommandLoginIDP)
    val wrongParameters = ExtractorTestHelper.toParametersMap(parameters) + (ParameterParser.InputParameter.key ->
      List(OAuthParameterManager.CommandLoginIDP, OAuthParameterManager.CommandRemoveIDP))

    expectFailedFuture(extractCommandConfigParams(ExtractorTestHelper.toParameters(wrongParameters)),
      "Too many input arguments")
  }

  it should "read the password for the storage config from the console if required" in {
    val reader = mock[ConsoleReader]
    when(reader.readOption(OAuthParameterManager.PasswordOption, password = true))
      .thenReturn(Password)
    val args = createBasicParametersMap(OAuthParameterManager.CommandLoginIDP) - OAuthParameterManager.PasswordOption

    val (config, _) = futureResult(extractCommandConfig(args, reader))
    config.storageConfig.optPassword.get.secret should be(Password)
  }

  it should "support an undefined password for the storage configuration" in {
    val consoleReader = mock[ConsoleReader]
    val args = createBasicParametersMap(OAuthParameterManager.CommandRemoveIDP) - OAuthParameterManager.PasswordOption

    val (config, _) = futureResult(extractCommandConfig(args, consoleReader))
    config.storageConfig.optPassword should be(None)
    verifyNoInteractions(consoleReader)
  }

  it should "evaluate a negative encrypt option when creating a storage configuration" in {
    val consoleReader = mock[ConsoleReader]
    val args = createBasicParametersMap(OAuthParameterManager.CommandLoginIDP) -
      OAuthParameterManager.PasswordOption + (OAuthParameterManager.EncryptOption -> "false")

    val (config, _) = futureResult(extractCommandConfig(args, consoleReader))
    config.storageConfig.optPassword should be(None)
    verifyNoInteractions(consoleReader)
  }

  it should "detect an invalid encryption option" in {
    val consoleReader = mock[ConsoleReader]
    val args = createBasicParametersMap(OAuthParameterManager.CommandLoginIDP) -
      OAuthParameterManager.PasswordOption + (OAuthParameterManager.EncryptOption -> "?")

    expectFailedFuture(extractCommandConfig(args, consoleReader), OAuthParameterManager.EncryptOption)
    verifyNoInteractions(consoleReader)
  }

  /**
    * Helper function to check whether an init configuration can be extracted.
    *
    * @param scopeSeparator the separator for scope values
    */
  private def checkExtractValidIdpConfiguration(scopeSeparator: String): Unit =
    val initArgs = Map(OAuthParameterManager.AuthEndpointOption -> AuthEndpointUrl,
      OAuthParameterManager.TokenEndpointOption -> TokenEndpointUrl,
      OAuthParameterManager.RedirectUrlOption -> Redirect,
      OAuthParameterManager.ScopeOption -> scopeString(scopeSeparator),
      OAuthParameterManager.ClientIDOption -> ClientID,
      OAuthParameterManager.ClientSecretOption -> ClientSecret)
    val args = createInitParametersMap(initArgs)

    val (config, next) = futureResult(extractCommandConfig(args))
    ExtractorTestHelper.accessedKeys(next) should contain allOf(OAuthParameterManager.AuthEndpointOption,
      OAuthParameterManager.TokenEndpointOption, OAuthParameterManager.RedirectUrlOption,
      OAuthParameterManager.ScopeOption, OAuthParameterManager.ClientIDOption,
      OAuthParameterManager.ClientSecretOption)
    config match
      case InitCommandConfig(idpConfig, storageConfig) =>
        idpConfig.authorizationEndpoint should be(AuthEndpointUrl)
        idpConfig.oauthConfig.tokenEndpoint should be(TokenEndpointUrl)
        idpConfig.scope should be(scopeString(" "))
        idpConfig.oauthConfig.redirectUri should be(Redirect)
        idpConfig.oauthConfig.clientID should be(ClientID)
        idpConfig.oauthConfig.clientSecret.secret should be(ClientSecret)
        checkStorageConfig(storageConfig)
      case c =>
        fail("Unexpected configuration: " + c)

  it should "extract a valid IDP configuration" in {
    checkExtractValidIdpConfiguration(" ")
  }

  it should "support comma as separator for scope values" in {
    checkExtractValidIdpConfiguration(",")
  }

  it should "read the client secret from the console if necessary" in {
    val reader = mock[ConsoleReader]
    when(reader.readOption(OAuthParameterManager.ClientSecretOption, password = true))
      .thenReturn(ClientSecret)
    val initArgs = Map(OAuthParameterManager.AuthEndpointOption -> AuthEndpointUrl,
      OAuthParameterManager.TokenEndpointOption -> TokenEndpointUrl,
      OAuthParameterManager.RedirectUrlOption -> Redirect,
      OAuthParameterManager.ScopeOption -> scopeString(" "),
      OAuthParameterManager.ClientIDOption -> ClientID)
    val args = createInitParametersMap(initArgs)

    val (config, next) = futureResult(extractCommandConfig(args, reader))
    ExtractorTestHelper.accessedKeys(next) should contain(OAuthParameterManager.ClientSecretOption)
    config match
      case InitCommandConfig(idpConfig, storageConfig) =>
        idpConfig.oauthConfig.clientID should be(ClientID)
        idpConfig.oauthConfig.clientSecret.secret should be(ClientSecret)
        checkStorageConfig(storageConfig)
      case c =>
        fail("Unexpected configuration:" + c)
  }

  it should "report errors for missing mandatory properties of an init command config" in {
    val args = createBasicParametersMap(OAuthParameterManager.CommandInitIDP) ++
      Map(OAuthParameterManager.ClientSecretOption -> ClientSecret)

    expectFailedFuture(extractCommandConfig(args),
      OAuthParameterManager.AuthEndpointOption, OAuthParameterManager.TokenEndpointOption,
      OAuthParameterManager.RedirectUrlOption, OAuthParameterManager.ScopeOption,
      OAuthParameterManager.ClientIDOption)
  }
