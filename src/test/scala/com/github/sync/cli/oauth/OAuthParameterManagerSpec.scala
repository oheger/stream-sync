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

package com.github.sync.cli.oauth

import java.nio.file.Paths

import com.github.sync.AsyncTestHelper
import com.github.sync.cli.ParameterExtractor.Parameters
import com.github.sync.cli.oauth.OAuthParameterManager.{InitCommandConfig, LoginCommandConfig, RemoveCommandConfig}
import com.github.sync.cli.{ConsoleReader, ParameterExtractor, ParameterParser}
import com.github.sync.http.OAuthStorageConfig
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
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
    * @param command the name of the command
    * @return the map with default option values
    */
  private def createBasicParametersMap(command: String): Map[String, String] =
    Map(OAuthParameterManager.StoragePathOption -> StoragePath,
      OAuthParameterManager.NameOption -> ProviderName,
      OAuthParameterManager.PasswordOption -> Password,
      ParameterParser.InputOption -> command)

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
}

/**
  * Test class for ''OAuthParameterManager''.
  */
class OAuthParameterManagerSpec extends AnyFlatSpec with Matchers with AsyncTestHelper with MockitoSugar {

  import OAuthParameterManagerSpec._
  import ParameterExtractor._

  /** The implicit console reader needed for parameter processing. */
  private implicit val consoleReader: ConsoleReader = mock[ConsoleReader]

  /**
    * Expects a failed future from a parsing operation. It is checked whether
    * the future is actually failed with an ''ParameterExtractionException''
    * that has a specific error message.
    *
    * @param future   the future to be checked
    * @param msgParts text parts to be expected in the exception message
    * @return the error message from the exception
    */
  private def expectFailedFuture(future: Future[_], msgParts: String*): String = {
    val exception = expectFailedFuture[ParameterExtractionException](future)
    val message = exception.failures.mkString(" ")
    msgParts foreach (part => message should include(part))
    message
  }

  /**
    * Checks whether the correct options for the storage configuration have
    * been extracted.
    *
    * @param storageConfig the configuration to be checked
    */
  private def checkStorageConfig(storageConfig: OAuthStorageConfig): Unit = {
    storageConfig.rootDir should be(Paths.get(StoragePath))
    storageConfig.baseName should be(ProviderName)
    storageConfig.optPassword.get.secret should be(Password)
  }

  "OAuthParameterManager" should "extract a valid remove command config" in {
    val params = createBasicParametersMap(OAuthParameterManager.CommandRemoveIDP)
    val (config, nextCtx) = futureResult(OAuthParameterManager.extractCommandConfig(params))

    config match {
      case RemoveCommandConfig(storageConfig) =>
        checkStorageConfig(storageConfig)
      case c =>
        fail("Unexpected result: " + c)
    }
    nextCtx.parameters.accessedParameters should contain only(OAuthParameterManager.StoragePathOption,
      OAuthParameterManager.PasswordOption, OAuthParameterManager.NameOption,
      OAuthParameterManager.EncryptOption, ParameterParser.InputOption)
  }

  it should "extract a valid login command config" in {
    val params = createBasicParametersMap(OAuthParameterManager.CommandLoginIDP)
    val (config, nextCtx) = futureResult(OAuthParameterManager.extractCommandConfig(params))

    config match {
      case LoginCommandConfig(storageConfig) =>
        checkStorageConfig(storageConfig)
      case c =>
        fail("Unexpected result: " + c)
    }
    nextCtx.parameters.accessedParameters should contain only(OAuthParameterManager.StoragePathOption,
      OAuthParameterManager.PasswordOption, OAuthParameterManager.NameOption,
      OAuthParameterManager.EncryptOption, ParameterParser.InputOption)
  }

  it should "report missing mandatory parameters when creating a storage config" in {
    val args = Map(OAuthParameterManager.PasswordOption -> Password,
      ParameterParser.InputOption -> OAuthParameterManager.CommandLoginIDP)

    expectFailedFuture(OAuthParameterManager.extractCommandConfig(args),
      OAuthParameterManager.NameOption, OAuthParameterManager.StoragePathOption)
  }

  it should "report a missing command" in {
    val args = Map(OAuthParameterManager.PasswordOption -> Password)

    val output = expectFailedFuture(OAuthParameterManager.extractCommandConfig(args),
      OAuthParameterManager.CommandOption)
    output should not include OAuthParameterManager.NameOption
  }

  it should "reject a command line with multiple commands" in {
    val parameters: Parameters = createBasicParametersMap(OAuthParameterManager.CommandLoginIDP)
    val wrongParameters = parameters.copy(parametersMap =
      parameters.parametersMap + (ParameterParser.InputOption ->
        List(OAuthParameterManager.CommandLoginIDP, OAuthParameterManager.CommandRemoveIDP)))

    expectFailedFuture(OAuthParameterManager.extractCommandConfig(wrongParameters),
      "Too many input arguments")
  }

  it should "read the password for the storage config from the console if required" in {
    val ec = implicitly[ExecutionContext]
    val reader = mock[ConsoleReader]
    when(reader.readOption(OAuthParameterManager.PasswordOption, password = true))
      .thenReturn(Password)
    val args = createBasicParametersMap(OAuthParameterManager.CommandLoginIDP) - OAuthParameterManager.PasswordOption

    val (config, _) = futureResult(OAuthParameterManager.extractCommandConfig(args)(ec = ec, consoleReader = reader))
    config.storageConfig.optPassword.get.secret should be(Password)
  }

  it should "support an undefined password for the storage configuration" in {
    val args = createBasicParametersMap(OAuthParameterManager.CommandRemoveIDP) - OAuthParameterManager.PasswordOption

    val (config, _) = futureResult(OAuthParameterManager.extractCommandConfig(args))
    config.storageConfig.optPassword should be(None)
    verifyZeroInteractions(consoleReader)
  }

  it should "evaluate a negative encrypt option when creating a storage configuration" in {
    val args = createBasicParametersMap(OAuthParameterManager.CommandLoginIDP) -
      OAuthParameterManager.PasswordOption + (OAuthParameterManager.EncryptOption -> "false")

    val (config, _) = futureResult(OAuthParameterManager.extractCommandConfig(args))
    config.storageConfig.optPassword should be(None)
    verifyZeroInteractions(consoleReader)
  }

  it should "detect an invalid encryption option" in {
    val args = createBasicParametersMap(OAuthParameterManager.CommandLoginIDP) -
      OAuthParameterManager.PasswordOption + (OAuthParameterManager.EncryptOption -> "?")

    expectFailedFuture(OAuthParameterManager.extractCommandConfig(args), OAuthParameterManager.EncryptOption)
    verifyZeroInteractions(consoleReader)
  }

  /**
    * Helper function to check whether an init configuration can be extracted.
    *
    * @param scopeSeparator the separator for scope values
    */
  private def checkExtractValidIdpConfiguration(scopeSeparator: String): Unit = {
    val initArgs = Map(OAuthParameterManager.AuthEndpointOption -> AuthEndpointUrl,
      OAuthParameterManager.TokenEndpointOption -> TokenEndpointUrl,
      OAuthParameterManager.RedirectUrlOption -> Redirect,
      OAuthParameterManager.ScopeOption -> scopeString(scopeSeparator),
      OAuthParameterManager.ClientIDOption -> ClientID,
      OAuthParameterManager.ClientSecretOption -> ClientSecret)
    val args = createInitParametersMap(initArgs)

    val (config, next) = futureResult(OAuthParameterManager.extractCommandConfig(args))
    next.parameters.accessedParameters should contain allOf(OAuthParameterManager.AuthEndpointOption,
      OAuthParameterManager.TokenEndpointOption, OAuthParameterManager.RedirectUrlOption,
      OAuthParameterManager.ScopeOption, OAuthParameterManager.ClientIDOption,
      OAuthParameterManager.ClientSecretOption)
    config match {
      case InitCommandConfig(oauthConfig, clientSecret, storageConfig) =>
        oauthConfig.authorizationEndpoint should be(AuthEndpointUrl)
        oauthConfig.tokenEndpoint should be(TokenEndpointUrl)
        oauthConfig.scope should be(scopeString(" "))
        oauthConfig.redirectUri should be(Redirect)
        oauthConfig.clientID should be(ClientID)
        clientSecret.secret should be(ClientSecret)
        checkStorageConfig(storageConfig)
      case c =>
        fail("Unexpected configuration: " + c)
    }
  }

  it should "extract a valid IDP configuration" in {
    checkExtractValidIdpConfiguration(" ")
  }

  it should "support comma as separator for scope values" in {
    checkExtractValidIdpConfiguration(",")
  }

  it should "read the client secret from the console if necessary" in {
    val ec = implicitly[ExecutionContext]
    val reader = mock[ConsoleReader]
    when(reader.readOption(OAuthParameterManager.ClientSecretOption, password = true))
      .thenReturn(ClientSecret)
    val initArgs = Map(OAuthParameterManager.AuthEndpointOption -> AuthEndpointUrl,
      OAuthParameterManager.TokenEndpointOption -> TokenEndpointUrl,
      OAuthParameterManager.RedirectUrlOption -> Redirect,
      OAuthParameterManager.ScopeOption -> scopeString(" "),
      OAuthParameterManager.ClientIDOption -> ClientID)
    val args = createInitParametersMap(initArgs)

    val (config, next) = futureResult(OAuthParameterManager.extractCommandConfig(args)(ec, reader))
    next.parameters.accessedParameters should contain(OAuthParameterManager.ClientSecretOption)
    config match {
      case InitCommandConfig(oauthConfig, clientSecret, storageConfig) =>
        oauthConfig.clientID should be(ClientID)
        clientSecret.secret should be(ClientSecret)
        checkStorageConfig(storageConfig)
      case c =>
        fail("Unexpected configuration:" + c)
    }
  }

  it should "report errors for missing mandatory properties of an init command config" in {
    val args = createBasicParametersMap(OAuthParameterManager.CommandInitIDP) ++
      Map(OAuthParameterManager.ClientSecretOption -> ClientSecret)

    expectFailedFuture(OAuthParameterManager.extractCommandConfig(args),
      OAuthParameterManager.AuthEndpointOption, OAuthParameterManager.TokenEndpointOption,
      OAuthParameterManager.RedirectUrlOption, OAuthParameterManager.ScopeOption,
      OAuthParameterManager.ClientIDOption)
  }
}
