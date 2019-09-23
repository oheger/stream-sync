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
import com.github.sync.webdav.oauth.OAuthStorageConfig

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
    for {name <- asMandatory(NameOption, singleOptionValue(NameOption))
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
}
