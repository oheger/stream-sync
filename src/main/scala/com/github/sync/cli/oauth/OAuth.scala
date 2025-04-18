/*
 * Copyright 2018-2025 The Developers Team.
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

import com.github.scli.HelpGenerator.ParameterFilter
import com.github.scli.ParameterManager.ProcessingContext
import com.github.scli.{ConsoleReader, DefaultConsoleReader, HelpGenerator, ParameterExtractor}
import com.github.sync.cli.*
import com.github.sync.cli.oauth.OAuthParameterManager.{CommandConfig, InitCommandConfig, ListTokensCommandConfig, LoginCommandConfig, RemoveCommandConfig}
import com.github.sync.oauth.{OAuthStorageServiceImpl, OAuthTokenRetrieverServiceImpl}

import scala.concurrent.Future
import scala.util.Try

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
object OAuth:
  /** The help text for the switch to display help information. */
  private val HelpOptionHelp =
    """Displays a help screen for the OAuth application. Note that the content of this help screen \
      |depends on the parameters passed on the command line. If command has been entered, \
      |the usage message lists only the basic parameters that are supported by all commands. \
      |If a specific command is available, the parameters supported by this command are listed \
      |as well.""".stripMargin

  /**
    * The main function of this CLI application. Processes the command line and
    * invokes the desired command. If parameter parsing fails, an error message
    * is printed.
    *
    * @param args the command line arguments
    */
  def main(args: Array[String]): Unit =
    new OAuth(OAuthCommandsImpl).run(args.toIndexedSeq)

/**
  * The implementation class of the CLI extending [[CliActorSystemLifeCycle]].
  *
  * @param commands the service to execute the CLI commands
  */
class OAuth(commands: OAuthCommands) extends CliActorSystemLifeCycle[CommandConfig]:

  override val name: String = "OAuthCLI"

  /**
    * @inheritdoc This implementation determines the command to be executed and
    *             runs it.
    */
  override protected def runApp(config: CommandConfig): Future[String] =
    implicit val consoleReader: ConsoleReader = DefaultConsoleReader
    executeCommand(config)

  /**
    * Executes the command entered on the command line based on the
    * configuration extracted from the options provided.
    *
    * @param cmdConfig     the configuration of the command
    * @param consoleReader the console reader
    * @return a future with the output generated by the command execution
    */
  private def executeCommand(cmdConfig: CommandConfig)(implicit consoleReader: ConsoleReader): Future[String] =
    val storageService = OAuthStorageServiceImpl
    cmdConfig match
      case initConfig: InitCommandConfig =>
        commands.initIdp(initConfig, storageService)
      case loginConfig: LoginCommandConfig =>
        commands.login(loginConfig, storageService, OAuthTokenRetrieverServiceImpl, BrowserHandler(),
          consoleReader)
      case removeConfig: RemoveCommandConfig =>
        commands.removeIdp(removeConfig, storageService)
      case listTokensConfig: ListTokensCommandConfig =>
        commands.listTokens(listTokensConfig, storageService)

  override protected def cliExtractor: ParameterExtractor.CliExtractor[Try[CommandConfig]] =
    OAuthParameterManager.commandConfigExtractor

  override protected def usageCaption(processingContext: ProcessingContext): String =
    "Usage: OAuth " +
      HelpGenerator.generateInputParamsOverview(processingContext.parameterContext.modelContext).mkString(" ") +
      " [options]"

  override protected def helpOptionHelp: String = OAuth.HelpOptionHelp

  override protected def optionsGroupFilter(context: ProcessingContext): ParameterFilter =
    import HelpGenerator._
    val groupFilter = contextGroupFilterForExtractors(context.parameterContext,
      List(OAuthParameterManager.commandExtractor))
    andFilter(groupFilter, negate(InputParamsFilterFunc))
