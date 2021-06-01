/*
 * Copyright 2018-2021 The Developers Team.
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

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{OAuthConfig, OAuthTokenData}
import com.github.scli.ParameterExtractor._
import com.github.sync.cli.CliActorSystemLifeCycle
import com.github.sync.http.SyncOAuthStorageConfig
import com.github.sync.http.oauth.IDPConfig

import java.nio.file.Path
import scala.util.Try

/**
  * A service responsible for parsing parameters for OAuth commands.
  *
  * The object defines command line extractors for extracting the information
  * from the command line required by the commands supported.
  */
object OAuthParameterManager {
  /** The command to initialize an IDP. */
  final val CommandInitIDP = "init"

  /** The command to remove all the data of an IDP. */
  final val CommandRemoveIDP = "remove"

  /** The command to perform a login into an IDP. */
  final val CommandLoginIDP = "login"

  /** The option that defines the storage path for OAuth data. */
  final val StoragePathOption = "idp-storage-path"

  /** Help text for the storage path option. */
  final val HelpStoragePathOption =
    """Determines a path (relative or absolute) where data about identity providers is stored. \
      |The init command stores the data about the new IDP in this path; other commands retrieve \
      |the data from there.""".stripMargin

  /** The option that defines the (base) name of an IDP. */
  final val NameOption = "idp-name"

  /** Help text for the IDP name option. */
  final val HelpNameOption =
    """Defines the name of the identity provider affected by this command. The data about this IDP is \
      |stored in the folder defined by the storage path option in a couple of files whose names are \
      |derived from the IDP name.""".stripMargin

  /**
    * The option that defines a password for the data of an IDP. If a
    * password is provided, sensitive information is encrypted with it.
    */
  final val PasswordOption = "idp-password"

  /** Help test for the password option. */
  final val HelpPasswordOption =
    """Sets a password for the identity provider affected by this command. The password is used \
      |to encrypt sensitive data about the IDP in the storage folder.""".stripMargin

  /**
    * The switch that defines whether sensitive data of an IDP should not be
    * encrypted. If the switch is absent, data is stored encrypted. Then a
    * password must be present (and is read from the console if necessary). By
    * specifying this switch, encryption can be disabled, and sensitive IDP
    * information is stored as plain text.
    */
  final val EncryptOption = "store-unencrypted"

  /** Help text for the encrypt IDP option. */
  final val HelpEncryptOption =
    """A flag that controls whether sensitive data about the identity provider affected by this \
      |command needs to be encrypted by a password. This is true by default, and a password is obtained either \
      |from the corresponding command line option or read from the console. When the data files \
      |for the IDP are read or written this password is used for encryption. By passing this switch on \
      |the command line, encryption can be disabled.""".stripMargin

  /**
    * Name of the option that defines the authorization endpoint for an IDP.
    */
  final val AuthEndpointOption: String = "auth-url"

  /** Help text for the authorization endpoint option. */
  final val HelpAuthEndpointOption =
    """The URL used by the identity provider for authorization code requests."""

  /** Name of the option that defines the token endpoint for an IDP. */
  final val TokenEndpointOption: String = "token-url"

  /** Help text for the token endpoint option. */
  final val HelpTokenEndpointOption =
    """The URL used by the identity provider for requests for access tokens. Requests to this URL \
      |are sent to trade an authorization code against an access/refresh token pair and to refresh \
      |expired access tokens.""".stripMargin

  /** Name of the option that defines the redirect URL for an IDP. */
  final val RedirectUrlOption: String = "redirect-url"

  /** Help text for the redirect URL option. */
  final val HelpRedirectUrlOption =
    """Defines the redirect URL. After the user has logged in into the identity provider, her user agent is \
      |redirected to this URL, and the authorization code is appended as parameter.""".stripMargin

  /**
    * Name of the option that defines the scope values to be requested when
    * asking for a token. The value can contain multiple scope values separated
    * by either comma or whitespace.
    */
  final val ScopeOption: String = "scope"

  /** Help text for the scope option. */
  final val HelpScopeOption =
    """Defines the scope to be requested when querying an access token."""

  /** Name of the option that defines the client ID for an IDP. */
  final val ClientIDOption: String = "client-id"

  /** Help text for the client ID option. */
  final val HelpClientIDOption =
    """Sets the ID of this OAuth client. This is needed to authenticate this application against the \
      |identity provider.""".stripMargin

  /** Name of the option that defines the client secret for an IDP. */
  final val ClientSecretOption: String = "client-secret"

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
    def storageConfig: SyncOAuthStorageConfig
  }

  /**
    * A data class collecting all the data required by the command to
    * initialize an OAuth identity provider.
    *
    * This is basically a combination of the (public) OAuth configuration plus
    * the client secret.
    *
    * @param oauthConfig   the OAuth configuration
    * @param storageConfig the ''OAuthStorageConfig''
    */
  case class InitCommandConfig(oauthConfig: IDPConfig,
                               override val storageConfig: SyncOAuthStorageConfig) extends CommandConfig

  /**
    * A data class collecting all the data required by the command to remove an
    * OAuth identity provider.
    *
    * The remove command requires no additional configuration.
    *
    * @param storageConfig the ''OAuthStorageConfig''
    */
  case class RemoveCommandConfig(override val storageConfig: SyncOAuthStorageConfig) extends CommandConfig

  /**
    * A data class collecting all the data required by the command to login
    * into an OAuth identity provider.
    *
    * The login command requires no additional configuration.
    *
    * @param storageConfig the ''OAuthStorageConfig''
    */
  case class LoginCommandConfig(override val storageConfig: SyncOAuthStorageConfig) extends CommandConfig

  /**
    * A ''CliExtractor'' for extracting the command passed in the
    * command line. The command determines the actions to be executed. There
    * must be exactly one command.
    */
  final val commandExtractor: CliExtractor[Try[String]] =
    inputValue(0, optKey = Some(CommandOption), optHelp = Some(HelpCommandOption), last = true)
      .toLower
      .mandatory

  /**
    * Returns a ''CliExtractor'' for extracting a ''CommandConfig'' object.
    * This extractor extracts the command name from the first input argument.
    * Then a conditional group is applied to extract the specific arguments for
    * this command.
    *
    * @return the ''CliExtractor'' for a ''CommandConfig''
    */
  def commandConfigExtractor: CliExtractor[Try[CommandConfig]] = {
    val groupMap = Map(CommandInitIDP -> commandInitExtractor,
      CommandLoginIDP -> commandLoginExtractor,
      CommandRemoveIDP -> commandRemoveExtractor)
    val cmdConfExt = conditionalGroupValue(commandExtractor, groupMap)

    for {
      config <- cmdConfExt
      _ <- CliActorSystemLifeCycle.FileExtractor
    } yield config
  }

  /**
    * Returns a ''CliExtractor'' for extracting an ''OAuthStorageConfig''
    * object. Whether a password is required or not is determined by the given
    * boolean parameter. If it is required, but not provided, it is read from
    * the console. As the storage configuration can be used in multiple
    * contexts, a prefix for option names can be specified.
    *
    * @param needPassword flag whether a password is required
    * @param prefix       a prefix that is added to all option names
    * @return the ''CliExtractor'' for the ''OAuthStorageConfig''
    */
  def storageConfigExtractor(needPassword: Boolean, prefix: String = ""):
  CliExtractor[Try[SyncOAuthStorageConfig]] = {
    val addAlias = prefix.isEmpty
    val procPath = withAlias(optionValue(prefix + StoragePathOption, help = Some(HelpStoragePathOption))
      .toPath
      .mandatory, "d", addAlias)
    val procName = withAlias(optionValue(prefix + NameOption, help = Some(HelpNameOption))
      .mandatory, "n", addAlias)

    for {name <- procName
         path <- procPath
         pwd <- storagePasswordExtractor(needPassword, prefix + EncryptOption, prefix + PasswordOption, addAlias)
         crypt <- cryptFlagExtractor(prefix + EncryptOption, addAlias)
         } yield createStorageConfig(name, path, pwd, crypt)
  }

  /**
    * Returns a ''CliExtractor'' to extract the data for an IDP from the
    * command line.
    *
    * @return the ''CliExtractor'' to extract IDP-related data
    */
  private def commandInitExtractor: CliExtractor[Try[CommandConfig]] =
    for {triedAuthUrl <- mandatoryStringOption(AuthEndpointOption, HelpAuthEndpointOption)
         triedTokenUrl <- mandatoryStringOption(TokenEndpointOption, HelpTokenEndpointOption)
         triedScope <- scopeExtractor
         triedRedirect <- mandatoryStringOption(RedirectUrlOption, HelpRedirectUrlOption)
         triedID <- mandatoryStringOption(ClientIDOption, HelpClientIDOption)
         triedSecret <- clientSecretExtractor
         triedStorage <- storageConfigExtractor(needPassword = true)
         } yield createIdpConfig(triedAuthUrl, triedTokenUrl, triedScope, triedRedirect, triedID,
      triedSecret, triedStorage)

  /**
    * Returns a ''CliExtractor'' to extract the configuration for the login
    * command.
    *
    * @return the login command extractor
    */
  private def commandLoginExtractor: CliExtractor[Try[CommandConfig]] =
    storageConfigExtractor(needPassword = true)
      .map(_.map(LoginCommandConfig))

  /**
    * Returns a ''CliExtractor'' to extract the configuration for the remove
    * IDP command.
    *
    * @return the remove command extractor
    */
  private def commandRemoveExtractor: CliExtractor[Try[CommandConfig]] =
    storageConfigExtractor(needPassword = false)
      .map(_.map(RemoveCommandConfig))

  /**
    * Returns a ''CliExtractor'' for extracting the password of the storage
    * configuration. If a password is required (as indicated by the boolean
    * parameter), it is read from the console if it has not been specified on
    * the command line. Otherwise, a dummy extractor returning an undefined
    * password is returned.
    *
    * @param needPassword flag whether a password is required
    * @param encOption    the key to be used for the encrypt option
    * @param pwdOption    the key to be used for the password option
    * @param addAlias     flag whether an alias is to be Added
    * @return the ''CliExtractor'' for the storage password
    */
  private def storagePasswordExtractor(needPassword: Boolean, encOption: String, pwdOption: String, addAlias: Boolean):
  CliExtractor[SingleOptionValue[String]] = {
    val elseExt = constantExtractor(Try(Option[String](null)))
    if (needPassword) {
      val condProc = cryptFlagExtractor(encOption, addAlias = false)
      withAlias(optionValue(pwdOption, help = Some(HelpPasswordOption)), "p", addAlias)
        .fallback(conditionalValue(condProc, consoleReaderValue(pwdOption, password = true), elseExt))
    } else elseExt
  }

  /**
    * Returns a ''CliExtractor'' for extracting the encryption flag of the
    * storage configuration. The flag determines whether a password is required
    * to encrypt IDP data stored locally.
    *
    * @param encOption the name of the encrypt flag option
    * @param addAlias  flag whether an alias is to be Added
    * @return the ''CliExtractor'' to extract the encrypt flag
    */
  private def cryptFlagExtractor(encOption: String, addAlias: Boolean): CliExtractor[Try[Boolean]] =
    withAlias(switchValue(encOption, optHelp = Some(HelpEncryptOption), presentValue = false),
      "U", addAlias)

  /**
    * Returns a ''CliExtractor'' for extracting the scope. For scope different
    * separators are allowed. This is handled here.
    *
    * @return the ''CliExtractor'' for scope
    */
  private def scopeExtractor: CliExtractor[Try[String]] =
    optionValue(ScopeOption, help = Some(HelpScopeOption))
      .mapTo(_.replace(',', ' '))
      .mandatory

  /**
    * Returns a ''CliExtractor'' for extracting the client secret of an IDP.
    * If the secret has not been provided as command line argument, it has to
    * be read from the console.
    *
    * @return the ''CliExtractor'' for the client secret
    */
  private def clientSecretExtractor: CliExtractor[Try[Secret]] =
    optionValue(ClientSecretOption, help = Some(HelpClientSecretOption))
      .fallback(consoleReaderValue(ClientSecretOption, password = true))
      .mapTo(pwd => Secret(pwd))
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
                                  triedCrypt: Try[Boolean]): Try[SyncOAuthStorageConfig] =
    createRepresentation(triedName, triedPath, triedPwd, triedCrypt) { (name, path, pwd, _) =>
      SyncOAuthStorageConfig(baseName = name, rootDir = path, optPassword = pwd.map(Secret(_)))
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
                              triedStorage: Try[SyncOAuthStorageConfig]): Try[InitCommandConfig] =
    createRepresentation(triedAuthUrl, triedTokenUrl, triedScope, triedRedirect,
      triedID, triedSecret, triedStorage) { (authUrl, tokenUrl, scope, redirect, id, secret, storage) =>
      val oauthConfig = OAuthConfig(tokenEndpoint = tokenUrl, redirectUri = redirect, clientID = id,
        clientSecret = secret, initTokenData = OAuthTokenData(null, null))
      val idpConfig = IDPConfig(authorizationEndpoint = authUrl,  scope = scope, oauthConfig = oauthConfig)
      InitCommandConfig(idpConfig, storage)
    }

  /**
    * Convenience function for the frequent use case to create a
    * ''CliExtractor'' for a mandatory string value.
    *
    * @param key  the key of the option
    * @param help the help text of the option
    * @return the extractor to extract this option
    */
  private def mandatoryStringOption(key: String, help: String): CliExtractor[Try[String]] =
    optionValue(key, Some(help))
      .mandatory

  /**
    * Optionally adds an alias to an extractor.
    *
    * @param extractor the extractor affected
    * @param alias     the alias to be added
    * @param addAlias  flag whether the alias is to be added
    * @tparam A the result type of the extractor
    * @return the modified extractor
    */
  private def withAlias[A](extractor: CliExtractor[A], alias: String, addAlias: Boolean): CliExtractor[A] =
    if (addAlias) extractor.alias(alias)
    else extractor
}
