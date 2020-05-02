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

import com.github.sync.cli.ParameterManager.ParameterExtractionException
import com.github.sync.cli._
import com.github.sync.cli.oauth.OAuthParameterManager.{CommandConfig, InitCommandConfig, LoginCommandConfig, RemoveCommandConfig}
import com.github.sync.http.oauth.{OAuthStorageServiceImpl, OAuthTokenRetrieverServiceImpl}

import scala.concurrent.Future
import scala.util.{Failure, Success}

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
  /**
    * The main function of this CLI application. Processes the command line and
    * invokes the desired command. If parameter parsing fails, an error message
    * is printed.
    *
    * @param args the command line arguments
    */
  def main(args: Array[String]): Unit = {
    new OAuth(OAuthCommandsImpl).run(args)
  }
}

/**
  * The implementation class of the CLI extending [[ActorSystemLifeCycle]].
  *
  * @param commands the service to execute the CLI commands
  */
class OAuth(commands: OAuthCommands) extends ActorSystemLifeCycle {

  override val name: String = "OAuthCLI"

  /**
    * @inheritdoc This implementation determines the command to be executed and
    *             runs it.
    */
  override protected def runApp(args: Array[String]): Future[String] = {
    implicit val consoleReader: ConsoleReader = DefaultConsoleReader
    (for {params <- ParameterManager.parseParameters(args)
          (cmdConf, paramCtx) <- OAuthParameterManager.extractCommandConfig(params)
          _ <- Future.fromTry(ParameterManager.checkParametersConsumed(paramCtx))
          result <- executeCommand(cmdConf)
          } yield result) recover {
      case e: ParameterExtractionException =>
        generateHelpMessage(e, e.parameterContext.parameters)
    }
  }

  /**
    * Executes the command entered on the command line based on the
    * configuration extracted from the options provided.
    *
    * @param cmdConfig     the configuration of the command
    * @param consoleReader the console reader
    * @return a future with the output generated by the command execution
    */
  private def executeCommand(cmdConfig: CommandConfig)(implicit consoleReader: ConsoleReader): Future[String] = {
    val storageService = OAuthStorageServiceImpl
    cmdConfig match {
      case initConfig: InitCommandConfig =>
        commands.initIdp(initConfig, storageService)
      case loginConfig: LoginCommandConfig =>
        commands.login(loginConfig, storageService, OAuthTokenRetrieverServiceImpl, BrowserHandler(),
          consoleReader)
      case removeConfig: RemoveCommandConfig =>
        commands.removeIdp(removeConfig, storageService)
    }
  }

  /**
    * Generates a string with a help text for this CLI application.
    *
    * @param exception the exception causing the help to be displayed
    * @param params    the parameters passed to the command line
    * @return a string with the help message
    */
  private def generateHelpMessage(exception: Throwable, params: ParameterManager.Parameters): String = {
    val (_, context) = ParameterManager.runProcessor(OAuthParameterManager.commandConfigProcessor,
      params)(DummyConsoleReader)
    val helpContext = context.helpContext

    import CliHelpGenerator._
    val helpGenerator = composeColumnGenerator(
      wrapColumnGenerator(attributeColumnGenerator(AttrHelpText), 70),
      prefixColumnGenerator(attributeColumnGenerator(AttrFallbackValue), prefixText = Some("Default value: "))
    )
    val generators = Seq(
      optionNameColumnGenerator(),
      helpGenerator
    )

    val triedCmdGroup = ParameterManager.tryProcessor(OAuthParameterManager.commandProcessor,
      params)(DefaultConsoleReader)
    val groupFilter = triedCmdGroup match {
      case Success((command, _)) => groupFilterFunc(command)
      case Failure(_) => UnassignedGroupFilterFunc
    }
    val optionsFilter = andFilter(groupFilter, OptionsFilterFunc)

    val buf = new java.lang.StringBuilder
    buf.append(exception.getMessage)
      .append(CR)
      .append(CR)
    buf.append("Usage: OAuth ")
      .append(generateInputParamsOverview(helpContext).mkString(" "))
      .append(" [options]")
      .append(CR)
      .append(CR)
      .append(generateOptionsHelp(helpContext, sortFunc = inputParamSortFunc(helpContext),
        filterFunc = InputParamsFilterFunc)(generators: _*))

    val optionsHelp = generateOptionsHelp(helpContext, filterFunc = optionsFilter)(generators: _*)
    if (optionsHelp.nonEmpty) {
      buf.append(CR)
        .append(CR)
        .append("Supported options:")
        .append(CR)
        .append(optionsHelp)
    }
    buf.toString
  }
}
