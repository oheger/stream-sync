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

import com.github.scli.HelpGenerator.ParameterFilter
import com.github.scli.ParameterManager.ProcessingContext
import com.github.scli.{HelpGenerator, ParameterExtractor, ParameterManager}
import com.github.sync.cli.CliActorSystemLifeCycle
import com.github.sync.cli.credentials.CredentialsParameterManager.CommandConfig

import scala.concurrent.Future
import scala.util.Try

/**
  * An object implementing a CLI with commands that allow managing credentials
  * for sync operations in an encrypted file.
  *
  * The supported commands are provided by the [[CredentialsCommands]] trait.
  * Parameter parsing and processing is done by
  * [[CredentialsParameterManager]].
  */
object Credentials:
  /**
    * The main function of the credentials manager CLI application.
    *
    * @param args the array with command line arguments
    */
  def main(args: Array[String]): Unit =
    new Credentials().run(args.toIndexedSeq)
end Credentials

/**
  * The CLI application implementation to manage an encrypted file with
  * credentials.
  *
  * @param commands the object providing the command implementations
  */
private class Credentials(val commands: CredentialsCommands = CredentialsCommandsImpl())
  extends CliActorSystemLifeCycle[CommandConfig]:
  override val name: String = "CredentialsCLI"

  override protected def runApp(config: CommandConfig): Future[String] =
    config match
      case CredentialsParameterManager.ListCommandConfig(credentialsFilePath, secret) =>
        commands.listCredentials(credentialsFilePath, secret)
      case CredentialsParameterManager.AddCommandConfig(credentialsFilePath, secret, key, value) =>
        commands.addCredential(credentialsFilePath, secret, key, value)
      case CredentialsParameterManager.GetCommandConfig(credentialsFilePath, secret, key) =>
        commands.getCredential(credentialsFilePath, secret, key)
      case CredentialsParameterManager.RemoveCommandConfig(credentialsFilePath, secret, key) =>
        commands.removeCredential(credentialsFilePath, secret, key)

  override protected def cliExtractor: ParameterExtractor.CliExtractor[Try[CommandConfig]] =
    CredentialsParameterManager.commandConfigExtractor

  /**
    * Generates the caption for the usage message of this application, which is
    * part of the help text. Here the application name and an overview of the
    * input parameters supported should be contained.
    *
    * @param processingContext the current ''ProcessingContext''
    * @return the caption for the usage message
    */
  override protected def usageCaption(processingContext: ParameterManager.ProcessingContext): String =
    "Usage: Credentials " +
      HelpGenerator.generateInputParamsOverview(processingContext.parameterContext.modelContext).mkString(" ") +
      " [options]"

  override protected def optionsGroupFilter(context: ProcessingContext): ParameterFilter =
    CliActorSystemLifeCycle.optionsGroupFilter(context, CredentialsParameterManager.commandExtractor)
