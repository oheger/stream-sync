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

import java.nio.file.Path

import com.github.sync.cli.ParameterManager._
import com.github.sync.cli.{ConsoleReader, ParameterManager}
import com.github.sync.crypt.Secret
import com.github.sync.webdav.oauth.{OAuthConfig, OAuthStorageConfig}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * A service responsible for parsing parameters for OAuth commands.
  *
  * The object defines command line processors for extracting the information
  * from the command line required by the commands supported.
  */
object OAuthParameterManager {
  /** Name of the option that defines the storage path for OAuth data. */
  val StoragePathOption: String = ParameterManager.OptionPrefix + "path"

  /** Name of the option that defines the (base) name of an IDP. */
  val NameOption: String = ParameterManager.OptionPrefix + "name"

  /**
    * Name of the option that defines a password for the data of an IDP. If a
    * password is provided, sensitive information is encrypted with it.
    */
  val PasswordOption: String = ParameterManager.OptionPrefix + "password"

  /**
    * Name of the boolean option that defines whether sensitive data of an IDP
    * needs to be encrypted. If this is '''true''' (which is also the default),
    * a password must be present (and is read from the console if necessary).
    */
  val EncryptOption: String = ParameterManager.OptionPrefix + "encrypt-data"

  /**
    * Name of the option that defines the authorization endpoint for an IDP.
    */
  val AuthEndpointOption: String = ParameterManager.OptionPrefix + "auth-url"

  /** Name of the option that defines the token endpoint for an IDP. */
  val TokenEndpointOption: String = ParameterManager.OptionPrefix + "token-url"

  /** Name of the option that defines the redirect URL for an IDP. */
  val RedirectUrlOption: String = ParameterManager.OptionPrefix + "redirect-url"

  /**
    * Name of the option that defines the scope values to be requested when
    * asking for a token. The value can contain multiple scope values separated
    * by either comma or whitespace.
    */
  val ScopeOption: String = ParameterManager.OptionPrefix + "scope"

  /** Name of the option that defines the client ID for an IDP. */
  val ClientIDOption: String = ParameterManager.OptionPrefix + "client-id"

  /** Name of the option that defines the client secret for an IDP. */
  val ClientSecretOption: String = ParameterManager.OptionPrefix + "client-secret"

  /**
    * A name to be displayed if there is something wrong with the command.
    * Here a different name is used than for the underlying input option of
    * ''ParameterManager''.
    */
  val CommandOption = "command"

  /**
    * A data class collecting data that is needed for the execution of an OAuth
    * command.
    *
    * Each command has a name that identifies it. Then a (sometimes limited)
    * ''OAuthStorageConfig'' is needed.
    *
    * @param command       the command name
    * @param storageConfig the storage configuration
    */
  case class CommandConfig(command: String, storageConfig: OAuthStorageConfig)

  /**
    * A data class collecting all the data required to describe an OAuth
    * identity provider.
    *
    * This is basically a combination of the (public) OAuth configuration plus
    * the client secret.
    *
    * @param oauthConfig  the OAuth configuration
    * @param clientSecret the client secret
    */
  case class IdpConfig(oauthConfig: OAuthConfig, clientSecret: Secret)

  /**
    * A ''CliProcessor'' for extracting the command passed in the
    * command line. The command determines the actions to be executed. There
    * must be exactly one command.
    */
  private val commandProcessor: CliProcessor[Try[String]] =
    ParameterManager.optionValue(ParameterManager.InputOption)
      .map {
        case h :: t if t.isEmpty =>
          Success(h)
        case _ :: _ =>
          Failure(paramException(CommandOption, "too many commands specified; only a single one is supported"))
        case _ =>
          Failure(paramException(CommandOption, "no command was specified"))
      }

  /**
    * Extracts a ''CommandConfig'' object from the parsed parameters. This
    * function is called first during command processing to determine which
    * command is to be executed and fetch a set of basic properties. The
    * boolean parameter determines whether a full ''OAuthStorageConfig'' is
    * needed by the command. If set to '''false''', a password does not need to
    * be specified. (This is used for commands that do not require access to
    * secret data.) Of course, a validation of command line arguments takes
    * place, and missing mandatory arguments are reported.
    *
    * @param parameters    the object with parsed parameters
    * @param ec            the execution context
    * @param consoleReader the console reader
    * @return a ''Future'' with the config and updated parameters
    */
  def extractCommandConfig(parameters: Parameters, needPassword: Boolean = true)
                          (implicit ec: ExecutionContext, consoleReader: ConsoleReader):
  Future[(CommandConfig, Parameters)] =
    Future.fromTry(tryProcessor(commandConfigProcessor(needPassword), parameters))

  /**
    * Returns a ''CliProcessor'' to extract the data for an IDP from the
    * command line.
    *
    * @return the ''CliProcessor'' to extract IDP-related data
    */
  def idpConfigProcessor: CliProcessor[Try[IdpConfig]] =
    for {triedAuthUrl <- mandatoryStringOption(AuthEndpointOption)
         triedTokenUrl <- mandatoryStringOption(TokenEndpointOption)
         triedScope <- scopeProcessor
         triedRedirect <- mandatoryStringOption(RedirectUrlOption)
         triedID <- mandatoryStringOption(ClientIDOption)
         triedSecret <- clientSecretProcessor
         } yield createIdpConfig(triedAuthUrl, triedTokenUrl, triedScope, triedRedirect, triedID, triedSecret)

  /**
    * Returns a ''CliProcessor'' for extracting a ''CommandConfig'' object.
    *
    * @param needPassword flag whether a password is needed by the storage
    *                     configuration
    * @return the ''CliProcessor'' for a ''CommandConfig''
    */
  private def commandConfigProcessor(needPassword: Boolean): CliProcessor[Try[CommandConfig]] =
    for {triedCmd <- commandProcessor
         triedConfig <- storageConfigProcessor(needPassword)
         } yield createCommandConfig(triedCmd, triedConfig)

  /**
    * Creates a ''CommandConfig'' object from the given components.
    *
    * @param triedCommand       the command name component
    * @param triedStorageConfig the storage config component
    * @return a ''Try'' with the resulting ''CommandConfig''
    */
  private def createCommandConfig(triedCommand: Try[String], triedStorageConfig: Try[OAuthStorageConfig]):
  Try[CommandConfig] =
    createRepresentation(triedCommand, triedStorageConfig) {
      CommandConfig(triedCommand.get, triedStorageConfig.get)
    }

  /**
    * Returns a ''CliProcessor'' for extracting an ''OAuthStorageConfig''
    * object. Whether a password is required or not is determined by the given
    * boolean parameter. If it is required, but not provided, it is read from
    * the console.
    *
    * @param needPassword flag whether a password is required
    * @return the ''CliProcessor'' for the ''OAuthStorageConfig''
    */
  private def storageConfigProcessor(needPassword: Boolean): CliProcessor[Try[OAuthStorageConfig]] =
    for {name <- mandatoryStringOption(NameOption)
         path <- asMandatory(StoragePathOption, asPathOptionValue(StoragePathOption, singleOptionValue(StoragePathOption)))
         pwd <- storagePasswordProcessor(needPassword)
         crypt <- booleanOptionValue(EncryptOption)
         } yield createStorageConfig(name, path, pwd, crypt)

  /**
    * Returns a ''CliProcessor'' for extracting the password of the storage
    * configuration. If a password is required (as indicated by the boolean
    * parameter), it is read from the console if it has not been specified on
    * the command line.
    *
    * @param needPassword flag whether a password is required
    * @return the ''CliProcessor'' for the storage password
    */
  private def storagePasswordProcessor(needPassword: Boolean): CliProcessor[SingleOptionValue[String]] = {
    val condProc = asMandatory(EncryptOption, booleanOptionValue(EncryptOption, Some(needPassword)))
    asSingleOptionValue(PasswordOption,
      withFallback(optionValue(PasswordOption),
        conditionalValue(condProc, consoleReaderValue(PasswordOption, password = true))))
  }

  /**
    * Returns a ''CliProcessor'' for extracting the scope. For scope different
    * separators are allowed. This is handled here.
    *
    * @return the ''CliProcessor'' for scope
    */
  private def scopeProcessor: CliProcessor[Try[String]] =
    asMandatory(ScopeOption, mapped(ScopeOption, singleOptionValue(ScopeOption))(_.replace(',', ' ')))

  /**
    * Returns a ''CliProcessor'' for extracting the client secret of an IDP.
    * If the secret has not been provided as command line argument, it has to
    * be read from the console.
    *
    * @return the ''CliProcessor'' for the client secret
    */
  private def clientSecretProcessor: CliProcessor[Try[Secret]] = {
    asMandatory(ClientSecretOption,
      mapped(ClientSecretOption,
        asSingleOptionValue(ClientSecretOption,
          withFallback(optionValue(ClientSecretOption),
            consoleReaderValue(ClientSecretOption, password = true))))(pwd => Secret(pwd)))
  }

  /**
    * Creates an ''OAuthStorageConfig'' from the given components. Failures are
    * accumulated.
    *
    * @param triedName  the (base) name component
    * @param triedPath  the storage path component
    * @param triedPwd   the password component
    * @param triedCrypt the encryption component
    * @return a ''Try'' with the generated storage configuration
    */
  private def createStorageConfig(triedName: Try[String], triedPath: Try[Path], triedPwd: Try[Option[String]],
                                  triedCrypt: Try[Option[Boolean]]): Try[OAuthStorageConfig] =
    createRepresentation(triedName, triedPath, triedPwd, triedCrypt) {
      OAuthStorageConfig(baseName = triedName.get, rootDir = triedPath.get,
        optPassword = triedPwd.get.map(Secret(_)))
    }

  /**
    * Tries to create an ''IdpConfig'' from the given components.
    *
    * @param triedAuthUrl  the authorization URL component
    * @param triedTokenUrl the token URL component
    * @param triedScope    the scope component
    * @param triedRedirect the redirect URL component
    * @param triedID       the client ID component
    * @param triedSecret   the client secret component
    * @return a ''Try'' with the generated IDP configuration
    */
  private def createIdpConfig(triedAuthUrl: Try[String], triedTokenUrl: Try[String], triedScope: Try[String],
                              triedRedirect: Try[String], triedID: Try[String], triedSecret: Try[Secret]):
  Try[IdpConfig] = createRepresentation(triedAuthUrl, triedTokenUrl, triedScope, triedRedirect,
    triedID, triedSecret) {
    val oauthConfig = OAuthConfig(authorizationEndpoint = triedAuthUrl.get, tokenEndpoint = triedTokenUrl.get,
      scope = triedScope.get, redirectUri = triedRedirect.get, clientID = triedID.get)
    IdpConfig(oauthConfig, triedSecret.get)
  }

  /**
    * Convenience function for the frequent use case to create a
    * ''CliProcessor'' for a mandatory string value.
    *
    * @param key the key of the option
    * @return the processor to extract this option
    */
  private def mandatoryStringOption(key: String): CliProcessor[Try[String]] =
    asMandatory(key, singleOptionValue(key))
}
