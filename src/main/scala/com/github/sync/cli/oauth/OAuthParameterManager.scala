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

import java.nio.file.Path

import com.github.sync.cli.ParameterManager._
import com.github.sync.cli.{ConsoleReader, ParameterManager, ParameterParser}
import com.github.sync.crypt.Secret
import com.github.sync.http.OAuthStorageConfig
import com.github.sync.http.oauth.OAuthConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * A service responsible for parsing parameters for OAuth commands.
  *
  * The object defines command line processors for extracting the information
  * from the command line required by the commands supported.
  */
object OAuthParameterManager {
  /** The command to initialize an IDP. */
  final val CommandInitIDP = "init"

  /** The command to remove all the data of an IDP. */
  final val CommandRemoveIDP = "remove"

  /** The command to perform a login into an IDP. */
  final val CommandLoginIDP = "login"

  /** Name of the option that defines the storage path for OAuth data. */
  final val StoragePathOptionName = "idp-storage-path"

  /** The option that defines the storage path for OAuth data. */
  final val StoragePathOption: String = ParameterParser.OptionPrefix + StoragePathOptionName

  /** Help text for the storage path option. */
  final val HelpStoragePathOption =
    """Determines a path (relative or absolute) where data about identity providers is stored. \
      |The init command stores the data about the new IDP in this path; other commands retrieve \
      |the data from there.""".stripMargin

  /** Name of the option that defines the (base) name of an IDP. */
  final val NameOptionName = "idp-name"

  /** The option that defines the (base) name of an IDP. */
  final val NameOption: String = ParameterParser.OptionPrefix + NameOptionName

  /** Help text for the IDP name option. */
  final val HelpNameOption =
    """Defines the name of the identity provider affected by this command. The data about this IDP is \
      |stored in the folder defined by the storage path option in a couple of files whose names are \
      |derived from the IDP name.""".stripMargin

  /** Name of the option that defines a password for the data of an IDP. */
  final val PasswordOptionName = "idp-password"

  /**
    * The option that defines a password for the data of an IDP. If a
    * password is provided, sensitive information is encrypted with it.
    */
  final val PasswordOption: String = ParameterParser.OptionPrefix + PasswordOptionName

  /** Help test for the password option. */
  final val HelpPasswordOption =
    """Sets a password for the identity provider affected by this command. The password is used \
      |to encrypt sensitive data about the IDP in the storage folder.""".stripMargin

  /** Name of the option that defines whether encryption is used. */
  final val EncryptOptionName = "encrypt-idp-data"

  /**
    * The boolean option that defines whether sensitive data of an IDP needs
    * to be encrypted. If this is '''true''' (which is also the default), a
    * password must be present (and is read from the console if necessary).
    */
  final val EncryptOption: String = ParameterParser.OptionPrefix + EncryptOptionName

  /** Help text for the encrypt IDP option. */
  final val HelpEncryptOption =
    """A flag that controls whether sensitive data about the identity provider affected by this \
      |command needs to be encrypted by a password. If set to true, a password is obtained either \
      |from the corresponding command line option or read from the console. When the data files \
      |for the IDP are read or written this password is used for encryption.""".stripMargin

  /**
    * Name of the option that defines the authorization endpoint for an IDP.
    */
  final val AuthEndpointOption: String = ParameterParser.OptionPrefix + "auth-url"

  /** Help text for the authorization endpoint option. */
  final val HelpAuthEndpointOption =
    """The URL used by the identity provider for authorization code requests."""

  /** Name of the option that defines the token endpoint for an IDP. */
  final val TokenEndpointOption: String = ParameterParser.OptionPrefix + "token-url"

  /** Help text for the token endpoint option. */
  final val HelpTokenEndpointOption =
    """The URL used by the identity provider for requests for access tokens. Requests to this URL \
      |are sent to trade an authorization code against an access/refresh token pair and to refresh \
      |expired access tokens.""".stripMargin

  /** Name of the option that defines the redirect URL for an IDP. */
  final val RedirectUrlOption: String = ParameterParser.OptionPrefix + "redirect-url"

  /** Help text for the redirect URL option. */
  final val HelpRedirectUrlOption =
    """Defines the redirect URL. After the user has logged in into the identity provider, her user agent is \
      |redirected to this URL, and the authorization code is appended as parameter.""".stripMargin

  /**
    * Name of the option that defines the scope values to be requested when
    * asking for a token. The value can contain multiple scope values separated
    * by either comma or whitespace.
    */
  final val ScopeOption: String = ParameterParser.OptionPrefix + "scope"

  /** Help text for the scope option. */
  final val HelpScopeOption =
    """Defines the scope to be requested when querying an access token."""

  /** Name of the option that defines the client ID for an IDP. */
  final val ClientIDOption: String = ParameterParser.OptionPrefix + "client-id"

  /** Help text for the client ID option. */
  final val HelpClientIDOption =
    """Sets the ID of this OAuth client. This is needed to authenticate this application against the \
      |identity provider.""".stripMargin

  /** Name of the option that defines the client secret for an IDP. */
  final val ClientSecretOption: String = ParameterParser.OptionPrefix + "client-secret"

  /** Help text for the client secret option. */
  final val HelpClientSecretOption =
    """Sets the secret of this OAuth client. This is needed to authenticate this application against \
      |the identity provider. If the secret is not passed in via the command line, it is read from \
      |the console.""".stripMargin

  /**
    * A name to be displayed if there is something wrong with the command.
    * Here a different name is used than for the underlying input option of
    * ''ParameterManager''.
    */
  final val CommandOption = "command"

  /** Help text for the command option. */
  final val HelpCommandOption =
    s"""The command to be executed. This defines the operation to be performed by this \\
       |application. Supported commands are the following (case does not matter):
       |$CommandInitIDP: Adds a new OAuth Identity Provider (IDP) with its options.
       |$CommandLoginIDP: Starts an authorization code grant flow with an IDP.
       |$CommandRemoveIDP: Removes the data about a specific IDP.
       |Pass in a command name without any further options to see the parameters that are \\
       |supported by this specific command.""".stripMargin

  /**
    * Definition of a function type that is used to determine whether for a
    * specific command a storage password is needed. The function is passed
    * the command name and returns a boolean flag indicating whether the
    * password is needed. If the function yields '''true''' and no password has
    * been provided on the command line, it is read from the console. Note that
    * an implementation must be able to handle unknown commands.
    */
  type CommandPasswordFunc = String => Boolean

  /**
    * A base trait for configurations for command classes.
    *
    * For each command supported by the OAuth CLI a concrete command
    * configuration class exists that extends this trait. As a common property,
    * each command has a (sometimes limited) ''OAuthStorageConfig''.
    */
  sealed trait CommandConfig {
    /**
      * Returns the ''OAuthStorageConfig'' for the command.
      *
      * @return the ''OAuthStorageConfig''
      */
    def storageConfig: OAuthStorageConfig
  }

  /**
    * A data class collecting all the data required by the command to
    * initialize an OAuth identity provider.
    *
    * This is basically a combination of the (public) OAuth configuration plus
    * the client secret.
    *
    * @param oauthConfig   the OAuth configuration
    * @param clientSecret  the client secret
    * @param storageConfig the ''OAuthStorageConfig''
    */
  case class InitCommandConfig(oauthConfig: OAuthConfig,
                               clientSecret: Secret,
                               override val storageConfig: OAuthStorageConfig) extends CommandConfig

  /**
    * A data class collecting all the data required by the command to remove an
    * OAuth identity provider.
    *
    * The remove command requires no additional configuration.
    *
    * @param storageConfig the ''OAuthStorageConfig''
    */
  case class RemoveCommandConfig(override val storageConfig: OAuthStorageConfig) extends CommandConfig

  /**
    * A data class collecting all the data required by the command to login
    * into an OAuth identity provider.
    *
    * The login command requires no additional configuration.
    *
    * @param storageConfig the ''OAuthStorageConfig''
    */
  case class LoginCommandConfig(override val storageConfig: OAuthStorageConfig) extends CommandConfig

  /**
    * A ''CliProcessor'' for extracting the command passed in the
    * command line. The command determines the actions to be executed. There
    * must be exactly one command.
    */
  final val commandProcessor: CliProcessor[Try[String]] =
    ParameterManager.inputValue(0, optKey = Some(CommandOption), optHelp = Some(HelpCommandOption), last = true)
      .toLower
      .single
      .mandatory

  /**
    * Extracts a ''CommandConfig'' object from the parsed parameters. This
    * function is called first during command processing to determine which
    * command is to be executed and fetch a set of its properties.
    *
    * @param parameters    the object with parsed parameters
    * @param ec            the execution context
    * @param consoleReader the console reader
    * @return a ''Future'' with the config and updated parameters
    */
  def extractCommandConfig(parameters: Parameters)
                          (implicit ec: ExecutionContext, consoleReader: ConsoleReader):
  Future[(CommandConfig, ParameterContext)] =
    Future.fromTry(tryProcessor(commandConfigProcessor, parameters))

  /**
    * Returns a ''CliProcessor'' for extracting a ''CommandConfig'' object.
    * This processor extracts the command name from the first input argument.
    * Then a conditional group is applied to extract the specific arguments for
    * this command.
    *
    * @return the ''CliProcessor'' for a ''CommandConfig''
    */
  def commandConfigProcessor: CliProcessor[Try[CommandConfig]] = {
    val groupMap = Map(CommandInitIDP -> commandInitProcessor,
      CommandLoginIDP -> commandLoginProcessor,
      CommandRemoveIDP -> commandRemoveProcessor)
    conditionalGroupValue(commandProcessor, groupMap)
  }

  /**
    * Returns a ''CliProcessor'' for extracting an ''OAuthStorageConfig''
    * object. Whether a password is required or not is determined by the given
    * boolean parameter. If it is required, but not provided, it is read from
    * the console. As the storage configuration can be used in multiple
    * contexts, a prefix for option names can be specified.
    *
    * @param needPassword flag whether a password is required
    * @param prefix       a prefix that is added to all option names
    * @return the ''CliProcessor'' for the ''OAuthStorageConfig''
    */
  def storageConfigProcessor(needPassword: Boolean, prefix: String = ParameterParser.OptionPrefix):
  CliProcessor[Try[OAuthStorageConfig]] = {
    val procPath = optionValue(prefix + StoragePathOptionName, help = Some(HelpStoragePathOption))
      .toPath
      .single
      .mandatory
    val procName = optionValue(prefix + NameOptionName, help = Some(HelpNameOption))
      .single
      .mandatory

    for {name <- procName
         path <- procPath
         pwd <- storagePasswordProcessor(needPassword, prefix + EncryptOptionName, prefix + PasswordOptionName)
         crypt <- cryptFlagProcessor(prefix + EncryptOptionName, needPassword)
         } yield createStorageConfig(name, path, pwd, crypt)
  }

  /**
    * Returns a ''CliProcessor'' to extract the data for an IDP from the
    * command line.
    *
    * @return the ''CliProcessor'' to extract IDP-related data
    */
  private def commandInitProcessor: CliProcessor[Try[CommandConfig]] =
    for {triedAuthUrl <- mandatoryStringOption(AuthEndpointOption, HelpAuthEndpointOption)
         triedTokenUrl <- mandatoryStringOption(TokenEndpointOption, HelpTokenEndpointOption)
         triedScope <- scopeProcessor
         triedRedirect <- mandatoryStringOption(RedirectUrlOption, HelpRedirectUrlOption)
         triedID <- mandatoryStringOption(ClientIDOption, HelpClientIDOption)
         triedSecret <- clientSecretProcessor
         triedStorage <- storageConfigProcessor(needPassword = true)
         } yield createIdpConfig(triedAuthUrl, triedTokenUrl, triedScope, triedRedirect, triedID,
      triedSecret, triedStorage)

  /**
    * Returns a ''CliProcessor'' to extract the configuration for the login
    * command.
    *
    * @return the login command processor
    */
  private def commandLoginProcessor: CliProcessor[Try[CommandConfig]] =
    storageConfigProcessor(needPassword = true)
      .map(_.map(LoginCommandConfig))

  /**
    * Returns a ''CliProcessor'' to extract the configuration for the remove
    * IDP command.
    *
    * @return the remove command processor
    */
  private def commandRemoveProcessor: CliProcessor[Try[CommandConfig]] =
    storageConfigProcessor(needPassword = false)
      .map(_.map(RemoveCommandConfig))

  /**
    * Returns a ''CliProcessor'' for extracting the password of the storage
    * configuration. If a password is required (as indicated by the boolean
    * parameter), it is read from the console if it has not been specified on
    * the command line.
    *
    * @param needPassword flag whether a password is required
    * @param encOption    the key to be used for the encrypt option
    * @param pwdOption    the key to be used for the password option
    * @return the ''CliProcessor'' for the storage password
    */
  private def storagePasswordProcessor(needPassword: Boolean, encOption: String, pwdOption: String):
  CliProcessor[SingleOptionValue[String]] = {
    val condProc = cryptFlagProcessor(encOption, needPassword)
    optionValue(pwdOption, help = Some(HelpPasswordOption))
      .fallback(conditionalValue(condProc, consoleReaderValue(pwdOption, password = true)))
      .single
  }

  /**
    * Returns a ''CliProcessor'' for extracting the encryption flag of the
    * storage configuration. The flag determines whether a password is required
    * to encrypt IDP data stored locally.
    *
    * @param encOption    the name of the encrypt flag option
    * @param needPassword default value for the flag
    * @return the ''CliProcessor'' to extract the encrypt flag
    */
  private def cryptFlagProcessor(encOption: String, needPassword: Boolean): CliProcessor[Try[Boolean]] = {
    optionValue(encOption, help = Some(HelpEncryptOption))
      .toBoolean
      .fallbackValues(needPassword)
      .single
      .mandatory
  }

  /**
    * Returns a ''CliProcessor'' for extracting the scope. For scope different
    * separators are allowed. This is handled here.
    *
    * @return the ''CliProcessor'' for scope
    */
  private def scopeProcessor: CliProcessor[Try[String]] =
    optionValue(ScopeOption, help = Some(HelpScopeOption))
      .mapTo(_.replace(',', ' '))
      .single
      .mandatory

  /**
    * Returns a ''CliProcessor'' for extracting the client secret of an IDP.
    * If the secret has not been provided as command line argument, it has to
    * be read from the console.
    *
    * @return the ''CliProcessor'' for the client secret
    */
  private def clientSecretProcessor: CliProcessor[Try[Secret]] =
    optionValue(ClientSecretOption, help = Some(HelpClientSecretOption))
      .fallback(consoleReaderValue(ClientSecretOption, password = true))
      .mapTo(pwd => Secret(pwd))
      .single
      .mandatory

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
                                  triedCrypt: Try[Boolean]): Try[OAuthStorageConfig] =
    createRepresentation(triedName, triedPath, triedPwd, triedCrypt) { (name, path, pwd, _) =>
      OAuthStorageConfig(baseName = name, rootDir = path, optPassword = pwd.map(Secret(_)))
    }

  /**
    * Tries to create a configuration for the init command from the given
    * components.
    *
    * @param triedAuthUrl  the authorization URL component
    * @param triedTokenUrl the token URL component
    * @param triedScope    the scope component
    * @param triedRedirect the redirect URL component
    * @param triedID       the client ID component
    * @param triedSecret   the client secret component
    * @param triedStorage  the storage config component
    * @return a ''Try'' with the generated init command configuration
    */
  private def createIdpConfig(triedAuthUrl: Try[String], triedTokenUrl: Try[String], triedScope: Try[String],
                              triedRedirect: Try[String], triedID: Try[String], triedSecret: Try[Secret],
                              triedStorage: Try[OAuthStorageConfig]): Try[InitCommandConfig] =
    createRepresentation(triedAuthUrl, triedTokenUrl, triedScope, triedRedirect,
      triedID, triedSecret, triedStorage) { (authUrl, tokenUrl, scope, redirect, id, secret, storage) =>
      val oauthConfig = OAuthConfig(authorizationEndpoint = authUrl, tokenEndpoint = tokenUrl,
        scope = scope, redirectUri = redirect, clientID = id)
      InitCommandConfig(oauthConfig, secret, storage)
    }

  /**
    * Convenience function for the frequent use case to create a
    * ''CliProcessor'' for a mandatory string value.
    *
    * @param key  the key of the option
    * @param help the help text of the option
    * @return the processor to extract this option
    */
  private def mandatoryStringOption(key: String, help: String): CliProcessor[Try[String]] =
    optionValue(key, Some(help))
      .single
      .mandatory
}
