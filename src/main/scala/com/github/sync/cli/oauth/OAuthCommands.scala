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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.stream.scaladsl.Sink
import com.github.sync.cli.ParameterManager.{CliProcessor, Parameters}
import com.github.sync.cli.oauth.OAuthParameterManager.IdpConfig
import com.github.sync.cli.{ConsoleReader, ParameterManager}
import com.github.sync.crypt.Secret
import com.github.sync.http.oauth._
import com.github.sync.http.{HttpRequestActor, OAuthStorageConfig}

import scala.concurrent.{ExecutionContext, Future, Promise}
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
    * @param system         the actor system
    * @param consoleReader  the console reader
    * @return a ''Future'' with the result of this command
    */
  def run(storageConfig: OAuthStorageConfig,
          storageService: OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData],
          parameters: Parameters)
         (implicit ec: ExecutionContext, system: ActorSystem, consoleReader: ConsoleReader): Future[String] = {
    val cliResult = ParameterManager.tryProcessor(cliProcessor, parameters)
    for {(config, updParamCtx) <- Future.fromTry(cliResult)
         _ <- ParameterManager.checkParametersConsumed(updParamCtx.parameters)
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
    * @param system         the actor system
    * @return a ''Future'' with the result of this command
    */
  protected def runCommand(storageConfig: OAuthStorageConfig,
                           storageService: OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret,
                             OAuthTokenData], config: C)
                          (implicit ec: ExecutionContext, system: ActorSystem): Future[String]
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
                                   (implicit ec: ExecutionContext, system: ActorSystem):
  Future[String] =
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
                                   (implicit ec: ExecutionContext, system: ActorSystem): Future[String] =
    storageService.removeStorage(storageConfig) map {
      case paths@_ :: _ =>
        val removeMsg = paths.mkString(", ")
        s"Removed data for IDP ${storageConfig.baseName}: $removeMsg"
      case _ =>
        s"Unknown identity provider '${storageConfig.baseName}'; no files have been removed."
    }
}

/**
  * A command implementation that allows users to login against a specific IDP
  * using the authorization code flow.
  *
  * The command generates the URL for the authorization request and tries to
  * open the Web Browser at this address. The user then has to login. The
  * resulting authorization code is then entered in the console. With this
  * information a token pair is retrieved from the IDP.
  *
  * @param tokenService   the service for retrieving tokens
  * @param browserHandler the object to control the browser
  */
class OAuthLoginCommand(val tokenService: OAuthTokenRetrieverService[OAuthConfig, Secret, OAuthTokenData],
                        val browserHandler: BrowserHandler) extends OAuthCommand[ConsoleReader] {
  /**
    * Creates a new instance with default dependencies.
    */
  def this() = this(OAuthTokenRetrieverServiceImpl, BrowserHandler())

  /**
    * @inheritdoc This command does not need any configuration.
    */
  override def cliProcessor: CliProcessor[Try[ConsoleReader]] =
    new CliProcessor[Try[ConsoleReader]](ctx => (Success(ctx.reader), ctx))

  /**
    * @inheritdoc This implementation performs the authorization code flow
    *             against the IDP specified.
    */
  override protected def runCommand(storageConfig: OAuthStorageConfig,
                                    storageService: OAuthStorageService[OAuthStorageConfig, OAuthConfig,
                                      Secret, OAuthTokenData], reader: ConsoleReader)
                                   (implicit ec: ExecutionContext, system: ActorSystem): Future[String] =
    for {config <- storageService.loadConfig(storageConfig)
                        authUri <- tokenService.authorizeUrl(config)
                        code <- obtainCode(config, authUri, reader)
                        secret <- storageService.loadClientSecret(storageConfig)
                        tokens <- fetchTokens(config, secret, code)
                        _ <- storageService.saveTokens(storageConfig, tokens)
                        } yield "Login into identity provider was successful. Token data has been stored."

  /**
    * Generates output on the console. Note: This is mainly to have the output
    * code in a central place that can also be overridden by tests.
    *
    * @param s the string to be written to console
    */
  protected def output(s: String): Unit = {
    println(s)
  }

  /**
    * Handles the authorization step of the code flow and tries to obtain the
    * code. This is done by opening the browser at the authorization URI.
    * Depending on the redirect URI, either a HTTP server is opened (if it
    * points to ''localhost''), and the redirect is expected or the user is
    * prompted to enter the resulting code manually.
    *
    * @param config  the OAuth config
    * @param authUri the authorization URI
    * @param reader  the console reader to prompt the user
    * @param ec      the execution context
    * @param system  the actor system
    * @return a ''Future'' with the code
    */
  private def obtainCode(config: OAuthConfig, authUri: Uri, reader: ConsoleReader)
                        (implicit ec: ExecutionContext, system: ActorSystem): Future[String] =
    checkLocalRedirectUri(config) match {
      case Some(port) =>
        val futCode = obtainCodeFromRedirect(port)
        openBrowser(authUri)
        futCode
      case None =>
        openBrowser(authUri)
        Future {
          reader.readOption("Enter authorization code", password = true)
        }
    }

  /**
    * Checks whether the redirect URI points to localhost. If so, the port is
    * extracted and returned.
    *
    * @param config the OAuth configuration
    * @return an ''Option'' with the extracted local redirect port
    */
  private def checkLocalRedirectUri(config: OAuthConfig): Option[Int] = {
    val RegLocalPort = "http://localhost:(\\d+).*".r
    config.redirectUri match {
      case RegLocalPort(sPort) => Some(sPort.toInt)
      case _ => None
    }
  }

  /**
    * Tries to open the Web browser with the authorization URI. If this fails,
    * a corresponding message is printed.
    *
    * @param authUri the authorization URI
    */
  private def openBrowser(authUri: Uri): Unit = {
    output("Opening Web browser to login into identity provider...")
    if (!browserHandler.openBrowser(authUri.toString())) {
      output("Could not open Web browser!")
      output("Please open the browser manually and navigate to this URL:")
      output(s"\t${authUri.toString()}")
    }
  }

  /**
    * Tries to obtain the authorization code from a redirect to a local port.
    * This method is called for redirect URIs referring to ''localhost''. It
    * opens a web server at the port specified and waits for a request that
    * contains the code as parameter.
    *
    * @param port   the port to bind the server
    * @param ec     the execution context
    * @param system the actor system
    * @return a ''Future'' with the authorization code
    */
  private def obtainCodeFromRedirect(port: Int)(implicit ec: ExecutionContext, system: ActorSystem): Future[String] = {
    val promiseCode = Promise[String]()
    val handler: HttpRequest => HttpResponse = request => {
      val status = request.uri.query().get("code") match {
        case Some(code) =>
          promiseCode.success(code)
          StatusCodes.OK
        case None =>
          promiseCode.failure(new IllegalStateException("No authorization code passed to redirect URI."))
          StatusCodes.BadRequest
      }
      HttpResponse(status)
    }

    val serverSource = Http().bind(interface = "localhost", port = port)
    val bindFuture = serverSource.to(Sink.foreach { con =>
      con handleWithSyncHandler handler
    }).run()

    promiseCode.future.andThen {
      case _ => bindFuture foreach (_.unbind())
    }
  }

  /**
    * Invokes the token service to obtain a token pair for the given
    * authorization code.
    *
    * @param config the OAuth configuration
    * @param secret the client secret
    * @param code   the authorization code
    * @param ec     the execution context
    * @param system the actor system
    * @return a ''Future'' with the token pair
    */
  private def fetchTokens(config: OAuthConfig, secret: Secret, code: String)
                         (implicit ec: ExecutionContext, system: ActorSystem):
  Future[OAuthTokenData] = {
    val httpActor = system.actorOf(HttpRequestActor(config.tokenEndpoint), "httpRequestActor")
    tokenService.fetchTokens(httpActor, config, secret, code) andThen {
      case _ => system stop httpActor
    }
  }
}
