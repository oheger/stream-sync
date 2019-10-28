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

import java.util.Locale

import com.github.sync.cli.oauth.OAuthParameterManager.CommandConfig
import com.github.sync.cli.{ActorSystemLifeCycle, ConsoleReader, DefaultConsoleReader, ParameterManager}
import com.github.sync.http.oauth.OAuthStorageServiceImpl

import scala.concurrent.Future

/**
  * An object implementing a CLI with commands related to OAuth identity
  * providers (IDPs).
  *
  * With the commands accepted by this object, some actions can be executed on
  * IDPs that are required, before they can be used with StreamSync, such as
  * registering a new IDP or retrieving a set of tokens.
  *
  * The command line of an invocation must contain exactly one of the commands
  * supported. In addition, there can be a number of options specific to the
  * command.
  */
object OAuth {
  /** The command to initialize an IDP. */
  val CommandInitIDP = "init"

  /** The command to remove all the data of an IDP. */
  val CommandRemoveIDP = "remove"

  /** The command to perform a login into an IDP. */
  val CommandLoginIDP = "login"

  /**
    * A function type definition for creating [[OAuthCommand]] instances.
    *
    * A function of this type is used to create the command instance for the
    * passed in command name dynamically.
    */
  type CommandProvider = () => OAuthCommand[_]

  /**
    * A data class holding information about the commands supported by this
    * CLI.
    *
    * The application has a map that associates command names with objects of
    * this class. That way it can be checked whether a command entered by the
    * user is valid and how it has to be processed.
    *
    * @param provider       the provider function to create the command
    * @param needStoragePwd flag whether the command needs a storage password
    */
  case class CommandData(provider: CommandProvider, needStoragePwd: Boolean)

  /**
    * A map with the commands supported by this CLI. Here information about all
    * commands supported is stored.
    */
  val SupportedCommands: Map[String, CommandData] =
    Map(CommandInitIDP -> CommandData(() => new OAuthInitCommand, needStoragePwd = true),
      CommandRemoveIDP -> CommandData(() => new OAuthRemoveCommand, needStoragePwd = false),
      CommandLoginIDP -> CommandData(() => new OAuthLoginCommand, needStoragePwd = true))

  /**
    * Implementation of the function that checks whether for a command a
    * storage password is required. The result is based on the data defined for
    * the command in question. If the command cannot be resolved, result is
    * '''false'''.
    *
    * @param command the name of the command
    * @return a flag whether this command requires a storage password
    */
  private def needsPwdFunc(command: String): Boolean =
    SupportedCommands get canonicalCommand(command) exists (_.needStoragePwd)

  /**
    * Obtains the command to be executed from the map of supported commands and
    * returns a future with it. If the command is unknown, the future is failed
    * with a meaningful exception that also lists all supported commands.
    *
    * @param cmdConf the command config
    * @return a ''Future'' with the command to be executed
    */
  private def fetchCommand(cmdConf: CommandConfig): Future[OAuthCommand[_]] =
    SupportedCommands get canonicalCommand(cmdConf.command) match {
      case Some(cmdData) =>
        Future.successful(cmdData.provider())
      case None =>
        val msg = s"""Unsupported command "${cmdConf.command}". $supportedCommandsMessage"""
        Future.failed(ParameterManager.paramException("command", msg))
    }

  /**
    * Generates a message about all the commands supported by this CLI.
    *
    * @return the message about supported commands
    */
  private def supportedCommandsMessage: String = {
    val allCommands = SupportedCommands.keys.mkString(", ")
    s"Supported commands are: $allCommands"
  }

  /**
    * Returns a canonical name for the given command. This function basically
    * converts the command name to lower case, as command names are
    * case-insensitive.
    *
    * @param cmd the command name
    * @return the canonical name for this command
    */
  private def canonicalCommand(cmd: String): String = cmd.toLowerCase(Locale.ROOT)

  /**
    * The main function of this CLI application. Processes the command line and
    * invokes the desired command. If parameter parsing fails, an error message
    * is printed.
    *
    * @param args the command line arguments
    */
  def main(args: Array[String]): Unit = {
    new OAuth().run(args)
  }
}

/**
  * The implementation class of the CLI extending [[ActorSystemLifeCycle]].
  */
class OAuth extends ActorSystemLifeCycle {

  import OAuth._

  override val name: String = "OAuthCLI"

  /**
    * @inheritdoc This implementation determines the command to be executed and
    *             runs it.
    */
  override protected def runApp(args: Array[String]): Future[String] = {
    implicit val consoleReader: ConsoleReader = DefaultConsoleReader
    val storageService = OAuthStorageServiceImpl
    for {params <- ParameterManager.parseParameters(args)
         (cmdConf, nextParams) <- OAuthParameterManager.extractCommandConfig(params)(needsPwdFunc)
         cmd <- fetchCommand(cmdConf)
         result <- cmd.run(cmdConf.storageConfig, storageService, nextParams)
         } yield result
  }
}
