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

import com.github.cloudfiles.core.http.Secret
import com.github.scli.ParameterExtractor.{CliExtractor, conditionalGroupValue, consoleReaderValue, createRepresentation, inputValue, optionValue}
import com.github.sync.cli.CliActorSystemLifeCycle

import java.nio.file.Path
import scala.util.Try

/**
  * An object defining the command line options for the credentials commands.
  *
  * The commands allow storing credentials in an encrypted file which can then
  * be referenced as passwords for sync operations.
  */
object CredentialsParameterManager:
  /** The command to list the credentials in a file. */
  final val CommandListCredentials = "list"

  /** The command to add a credential to a file. */
  final val CommandAddCredential = "add"

  /** The command to query the value of a specific credential. */
  final val CommandGetCredential = "get"

  /** The option that defines the location of the credentials file. */
  final val CredentialsFileOption = "credentials-file"

  /** Help text for the credentials file option. */
  final val HelpCredentialsFileOption =
    """Defines the location (as an absolute or relative path) of the file that stores credentials."""

  /**
    * The name of the option that contains the secret of the credentials file.
    * This secret is used to encrypt the file, so that the credentials are
    * stored in a secure manner.
    */
  final val SecretOption = "secret"

  /** Help text for the secret option. */
  final val HelpSecretOption =
    """Defines the password to be used to encrypt and decrypt the file with the credentials. This secret \
      |protects the persisted credentials.""".stripMargin

  /**
    * The name of the option that contains the key of a credential which is 
    * affected by the current command.
    */
  final val KeyOption = "key"

  /** Help text for the key option. */
  final val HelpKeyOption = "The key of the credential affected by this command."

  /**
    * The name of the option that contains the value of a credential to be
    * added.
    */
  final val ValueOption = "value"

  /** Help text for the value option. */
  final val HelpValueOption =
    """The (secret) value of the credential to be added or the new value if a credential is overridden."""

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
       |$CommandListCredentials: 
       |  Lists the keys of credentials contained in the credentials file.
       |$CommandAddCredential:
       |  Adds (or overrides) a credential, specified by its key and value, to the credentials
       |  file. This command can also be used to create a new file.
       |$CommandGetCredential:
       |  Allows querying the value of a credential identified by its key.
       |Pass in a command name without any further options to see the parameters that are \\
       |supported by this specific command.""".stripMargin

  /**
    * A base trait for configurations for supported command classes.
    *
    * This trait is extended by all concrete command configuration classes. It
    * defines some properties for options common to all commands.
    */
  sealed trait CommandConfig:
    /**
      * The location of the file with the credentials to be manipulated by this
      * command.
      *
      * @return the path to the credentials file
      */
    def credentialsFilePath: Path

    /**
      * The [[Secret]] to encrypt and decrypt the file with credentials.
      *
      * @return the [[Secret]] for cryptographic operations
      */
    def secret: Secret
  end CommandConfig

  /**
    * A data class collecting the options supported by the command to list the
    * credentials in the file.
    *
    * @param credentialsFilePath the path to the credentials file
    * @param secret              the secret to encrypt the file
    */
  final case class ListCommandConfig(override val credentialsFilePath: Path,
                                     override val secret: Secret) extends CommandConfig

  /**
    * A data class collecting the options supported by the command to add a new
    * credential to a file.
    *
    * @param credentialsFilePath the path to the credentials file
    * @param secret              the secret to encrypt the file
    * @param key                 the key of the credential to add
    * @param value               the value of the new credential
    */
  final case class AddCommandConfig(override val credentialsFilePath: Path,
                                    override val secret: Secret,
                                    key: String,
                                    value: Secret) extends CommandConfig

  /**
    * A data class collecting the options supported by the command to get the
    * value of a specific credential.
    *
    * @param credentialsFilePath the path to the credentials file
    * @param secret              the secret to encrypt the file
    * @param key                 the key of the credential to add
    */
  final case class GetCommandConfig(override val credentialsFilePath: Path,
                                    override val secret: Secret,
                                    key: String) extends CommandConfig

  /**
    * A [[CliExtractor]] for extracting the command passed in the
    * command line. The command determines the actions to be executed. There
    * must be exactly one command.
    */
  final val commandExtractor: CliExtractor[Try[String]] =
    inputValue(0, optKey = Some(CommandOption), optHelp = Some(HelpCommandOption), last = true)
      .toLower
      .mandatory

  /**
    * Returns a [[CliExtractor]] for the configuration of the current command.
    * This extractor evaluates the command name specified on the first input
    * parameter and then extracts the options supported by the selected
    * command.
    *
    * @return the [[CliExtractor]] for the configuration of the current command
    */
  def commandConfigExtractor: CliExtractor[Try[CommandConfig]] =
    val groupMap = Map(
      CommandAddCredential -> addConfigExtractor,
      CommandGetCredential -> getConfigExtractor,
      CommandListCredentials -> listConfigExtractor
    )
    val cmdConfExt = conditionalGroupValue(commandExtractor, groupMap)

    for
      config <- cmdConfExt
      _ <- CliActorSystemLifeCycle.FileExtractor
    yield config

  /**
    * Returns the [[CliExtractor]] for the configuration of the "list
    * credentials" command.
    *
    * @return the extractor for the config of the list command
    */
  private def listConfigExtractor: CliExtractor[Try[ListCommandConfig]] =
    for
      path <- credentialsFileExtractor
      secret <- secretExtractor
    yield createListConfig(path, secret)

  /**
    * Creates the configuration for the list command based on the given
    * components.
    *
    * @param triedCredentialsFile the credentials file component
    * @param triedSecret          the secret component
    * @return a [[Try]] with the constructed configuration
    */
  private def createListConfig(triedCredentialsFile: Try[Path], triedSecret: Try[Secret]): Try[ListCommandConfig] =
    createRepresentation(triedCredentialsFile, triedSecret)(ListCommandConfig.apply)

  /**
    * Returns the [[CliExtractor]] for the configuration of the "add
    * credential" command.
    *
    * @return the extractor for the config of the add command
    */
  private def addConfigExtractor: CliExtractor[Try[AddCommandConfig]] =
    for
      path <- credentialsFileExtractor
      secret <- secretExtractor
      key <- keyExtractor
      value <- secretWithConsoleSupportExtractor(ValueOption, HelpValueOption)
    yield createAddConfig(path, secret, key, value)

  /**
    * Creates the configuration for the add credential command based on the
    * given components.
    *
    * @param triedCredentialsFile the credentials file component
    * @param triedSecret          the secret component
    * @param triedKey             the credential key component
    * @param triedValue           the credential value component
    * @return a [[Try]] with the constructed configuration
    */
  private def createAddConfig(triedCredentialsFile: Try[Path],
                              triedSecret: Try[Secret],
                              triedKey: Try[String],
                              triedValue: Try[Secret]): Try[AddCommandConfig] =
    createRepresentation(triedCredentialsFile, triedSecret, triedKey, triedValue)(AddCommandConfig.apply)

  /**
    * Returns the [[CliExtractor]] for the configuration of the "get
    * credential" command.
    *
    * @return the extractor for the config of the get command
    */
  private def getConfigExtractor: CliExtractor[Try[GetCommandConfig]] =
    for
      path <- credentialsFileExtractor
      secret <- secretExtractor
      key <- keyExtractor
    yield createGetConfig(path, secret, key)

  /**
    * Creates the configuration for the get credential command based on the
    * given components.
    *
    * @param triedCredentialsFile the credentials file component
    * @param triedSecret          the secret component
    * @param triedKey             the credential key component
    * @return a [[Try]] with the constructed configuration
    */
  private def createGetConfig(triedCredentialsFile: Try[Path],
                              triedSecret: Try[Secret],
                              triedKey: Try[String]): Try[GetCommandConfig] =
    createRepresentation(triedCredentialsFile, triedSecret, triedKey)(GetCommandConfig.apply)

  /**
    * Returns the [[CliExtractor]] for the path to the credentials file.
    *
    * @return the extractor for the credentials file path
    */
  private def credentialsFileExtractor: CliExtractor[Try[Path]] =
    optionValue(CredentialsFileOption, help = Some(HelpCredentialsFileOption))
      .toPath
      .mandatory

  /**
    * Returns the [[CliExtractor]] for the [[Secret]] to encrypt the
    * credentials file.
    *
    * @return the extractor for the encryption secret
    */
  private def secretExtractor: CliExtractor[Try[Secret]] =
    secretWithConsoleSupportExtractor(SecretOption, HelpSecretOption)

  /**
    * Returns a [[CliExtractor]] for an option of type [[Secret]] that supports
    * specifying the secret value either directly or reading it from the
    * console as fallback.
    *
    * @param option the name of the option
    * @param help   the help text of the option
    * @return the extractor to obtain the secret value
    */
  private def secretWithConsoleSupportExtractor(option: String, help: String): CliExtractor[Try[Secret]] =
    optionValue(option, help = Some(help))
      .fallback(consoleReaderValue(option, password = true))
      .map(_.map(optSec => optSec.map(Secret.apply)))
      .mandatory

  /**
    * Returns the [[CliExtractor]] for the key of the secret to be manipulated
    * by the current command.
    *
    * @return the extractor for the credential key
    */
  private def keyExtractor: CliExtractor[Try[String]] =
    optionValue(KeyOption, help = Some(HelpKeyOption))
      .mandatory
