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

import akka.stream.ActorMaterializer
import com.github.sync.cli.{ConsoleReader, ParameterManager}
import com.github.sync.cli.ParameterManager.{CliProcessor, Parameters}
import com.github.sync.cli.oauth.OAuthParameterManager.IdpConfig
import com.github.sync.crypt.Secret
import com.github.sync.webdav.oauth.{OAuthConfig, OAuthStorageConfig, OAuthStorageService, OAuthTokenData}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

/**
  * A trait defining a command related to an OAuth operation.
  *
  * This trait is part of the CLI for managing OAuth identity providers. The
  * CLI supports multiple specialized commands that define the operation to be
  * executed, e.g. initializing a new identity provider or starting an
  * authorization flow. Each command can support additional specific command
  * line options and has a method to execute it.
  *
  * The main class of the CLI processes the command line arguments and extracts
  * the command name. This name is then mapped to a concrete implementation of
  * this trait. Based on the protocol defined here, the command is then run.
  *
  * This trait already implements some common functionality for commands,
  * especially related to parameter processing. So it can create a
  * command-specific configuration object from the command line arguments and
  * check whether no unsupported parameters have been passed.
  *
  * @tparam C the configuration class used by this command
  */
trait OAuthCommand[C] {
  /**
    * Returns the ''CliProcessor'' to be used to create the configuration
    * object for a concrete implementation. This processor is run on the
    * current parameters.
    *
    * @return the ''CliProcessor'' for this implementation
    */
  def cliProcessor: CliProcessor[Try[C]]

  /**
    * Executes this command. This function processes command line arguments to
    * obtain the specific configuration for this command; it also checks
    * whether all parameters that have been specified have been consumed. Then,
    * with the configuration created, the ''runCommand()'' function is called.
    * The resulting future contains a message with the command's result.
    *
    * @param storageConfig  the storage config for the OAuth provider affected
    * @param storageService the storage service
    * @param parameters     the current command line arguments
    * @param ec             the execution context
    * @param mat            the object to materialize streams
    * @param consoleReader  the console reader
    * @return a ''Future'' with the result of this command
    */
  def run(storageConfig: OAuthStorageConfig,
          storageService: OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData],
          parameters: Parameters)
         (implicit ec: ExecutionContext, mat: ActorMaterializer, consoleReader: ConsoleReader): Future[String] = {
    val cliResult = ParameterManager.tryProcessor(cliProcessor, parameters)
    for {(config, updParams) <- Future.fromTry(cliResult)
         _ <- ParameterManager.checkParametersConsumed(updParams)
         result <- runCommand(storageConfig, storageService, config)
         } yield result
  }

  /**
    * Actually executes this command with the specified configuration. This
    * function is called by ''run()'' after successful parameter processing.
    * Here the concrete actions of this command need to be implemented.
    *
    * @param storageConfig  the storage config for the OAuth provider affected
    * @param storageService the storage service
    * @param config         the configuration for this command
    * @param ec             the execution context
    * @param mat            the object to materialize streams
    * @return a ''Future'' with the result of this command
    */
  protected def runCommand(storageConfig: OAuthStorageConfig,
                           storageService: OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret,
                             OAuthTokenData], config: C)
                          (implicit ec: ExecutionContext, mat: ActorMaterializer): Future[String]
}

/**
  * A command implementation that allows initializing a new IDP.
  *
  * The command expects parameters that define the properties of the new
  * identity provider. With this information, the [[OAuthStorageService]] is
  * called to persist it, so that it can be later referenced when interacting
  * with the IDP.
  */
class OAuthInitCommand extends OAuthCommand[IdpConfig] {
  /**
    * @inheritdoc This implementation returns the processor to extract an
    *             ''IdpConfig'' from the OAuth parameter manager.
    */
  override def cliProcessor: CliProcessor[Try[IdpConfig]] =
    OAuthParameterManager.idpConfigProcessor

  /**
    * @inheritdoc This implementation invokes the storage service.
    */
  override protected def runCommand(storageConfig: OAuthStorageConfig,
                                    storageService: OAuthStorageService[OAuthStorageConfig, OAuthConfig,
                                      Secret, OAuthTokenData], config: IdpConfig)
                                   (implicit ec: ExecutionContext, mat: ActorMaterializer): Future[String] =
    for {_ <- storageService.saveConfig(storageConfig, config.oauthConfig)
         _ <- storageService.saveClientSecret(storageConfig, config.clientSecret)
         } yield s"IDP ${storageConfig.baseName} has been successfully initialized."
}

/**
  * A command implementation that removes all persistent data about an IDP.
  *
  * This command mainly delegates to [[OAuthStorageService]] to handle the
  * removal. It does not require a specific configuration beyond the storage
  * configuration.
  */
class OAuthRemoveCommand extends OAuthCommand[Unit] {
  override val cliProcessor: CliProcessor[Try[Unit]] =
    ParameterManager.constantProcessor(Success(()))

  /**
    * @inheritdoc This implementation invokes the storage service and generates
    *             a result message.
    */
  override protected def runCommand(storageConfig: OAuthStorageConfig,
                                    storageService: OAuthStorageService[OAuthStorageConfig, OAuthConfig,
                                      Secret, OAuthTokenData], config: Unit)
                                   (implicit ec: ExecutionContext, mat: ActorMaterializer): Future[String] =
    storageService.removeStorage(storageConfig) map {
      case paths@_ :: _ =>
        val removeMsg = paths.mkString(", ")
        s"Removed data for IDP ${storageConfig.baseName}: $removeMsg"
      case _ =>
        s"Unknown identity provider '${storageConfig.baseName}'; no files have been removed."
    }
}