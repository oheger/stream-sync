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

import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.auth.OAuthTokenData
import com.github.scli.ConsoleReader
import com.github.sync.cli.oauth.OAuthParameterManager.{InitCommandConfig, ListTokensCommandConfig, LoginCommandConfig, RemoveCommandConfig}
import com.github.sync.oauth.*
import org.apache.pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import org.apache.pekko.actor.{ActorSystem, typed}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes, Uri}
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Random

/**
  * A module implementing the logic behind the commands accepted by the OAuth
  * CLI application.
  *
  * The OAuth application processes its command line and determines the command
  * to be executed and its specific arguments. Based on this, the corresponding
  * function of this module is called.
  */
object OAuthCommandsImpl extends OAuthCommands:

  import OAuthCommands.*

  override def initIdp(config: InitCommandConfig, storageService: StorageService)
                      (implicit ec: ExecutionContext, system: ActorSystem): Future[String] =
    storageService.saveIdpConfig(config.storageConfig, config.oauthConfig) map { _ =>
      s"IDP ${config.storageConfig.baseName} has been successfully initialized."
    }

  /**
    * @inheritdoc
    * The function generates the URL for the authorization request and tries to
    * open the Web Browser at this address. The user then has to login. The
    * resulting authorization code is then entered in the console. With this
    * information a token pair is retrieved from the IDP.
    */
  override def login(loginConfig: LoginCommandConfig, storageService: StorageService, tokenService: TokenService,
                     browserHandler: BrowserHandler, consoleReader: ConsoleReader,
                     printFunc: PrintFunc = ConsolePrintFunc)
                    (implicit ec: ExecutionContext, system: ActorSystem): Future[String] =
    implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped
    val state = new Random().nextInt().toString

    for config <- storageService.loadIdpConfig(loginConfig.storageConfig)
        authUri <- tokenService.authorizeUrl(config, optState = Some(state))
        code <- obtainCode(config, authUri, browserHandler, consoleReader, printFunc, state)
        tokens <- fetchTokens(config, code, tokenService)
        _ <- storageService.saveTokens(loginConfig.storageConfig, tokens)
    yield "Login into identity provider was successful. Token data has been stored."

  override def removeIdp(removeConfig: RemoveCommandConfig, storageService: StorageService)
                        (implicit ec: ExecutionContext, system: ActorSystem): Future[String] =
    storageService.removeStorage(removeConfig.storageConfig) map {
      case paths@_ :: _ =>
        val removeMsg = paths.mkString(", ")
        s"Removed data for IDP ${removeConfig.storageConfig.baseName}: $removeMsg"
      case _ =>
        s"Unknown identity provider '${removeConfig.storageConfig.baseName}'; no files have been removed."
    }

  override def listTokens(listTokensConfig: ListTokensCommandConfig, storageService: StorageService)
                         (using ec: ExecutionContext, system: ActorSystem): Future[String] =
    storageService.loadIdpConfig(listTokensConfig.storageConfig).map { idpConfig =>
      s"""
         |Tokens for IDP ${listTokensConfig.storageConfig.baseName}:
         |Access token:  ${idpConfig.oauthConfig.initTokenData.accessToken}
         |Refresh token: ${idpConfig.oauthConfig.initTokenData.refreshToken}
         |""".stripMargin
    }

  /**
    * Handles the authorization step of the code flow and tries to obtain the
    * code. This is done by opening the browser at the authorization URI.
    * Depending on the redirect URI, either a HTTP server is opened (if it
    * points to ''localhost''), and the redirect is expected or the user is
    * prompted to enter the resulting code manually.
    *
    * @param config         the OAuth config
    * @param authUri        the authorization URI
    * @param browserHandler the ''BrowserHandler''
    * @param reader         the console reader to prompt the user
    * @param printFunc      the function to output strings
    * @param state          the state to add to the authorize URL
    * @param ec             the execution context
    * @param system         the actor system
    * @return a ''Future'' with the code
    */
  private def obtainCode(config: IDPConfig, authUri: Uri, browserHandler: BrowserHandler, reader: ConsoleReader,
                         printFunc: PrintFunc, state: String)(implicit ec: ExecutionContext, system: ActorSystem):
  Future[String] =
    checkLocalRedirectUri(config) match
      case Some(port) =>
        val futCode = obtainCodeFromRedirect(port, state)
        openBrowser(authUri, browserHandler, printFunc)
        futCode
      case None =>
        openBrowser(authUri, browserHandler, printFunc)
        Future {
          reader.readOption("Enter authorization code", password = true)
        }

  /**
    * Checks whether the redirect URI points to localhost. If so, the port is
    * extracted and returned.
    *
    * @param config the OAuth configuration
    * @return an ''Option'' with the extracted local redirect port
    */
  private def checkLocalRedirectUri(config: IDPConfig): Option[Int] =
    val RegLocalPort = "http://localhost:(\\d+).*".r
    config.oauthConfig.redirectUri match
      case RegLocalPort(sPort) => Some(sPort.toInt)
      case _ => None

  /**
    * Tries to open the Web browser with the authorization URI. If this fails,
    * a corresponding message is printed.
    *
    * @param authUri        the authorization URI
    * @param browserHandler the ''BrowserHandler''
    * @param printFunc      the function to print strings
    */
  private def openBrowser(authUri: Uri, browserHandler: BrowserHandler, printFunc: PrintFunc): Unit =
    printFunc("Opening Web browser to login into identity provider...")
    if !browserHandler.openBrowser(authUri.toString()) then
      printFunc("Could not open Web browser!")
      printFunc("Please open the browser manually and navigate to this URL:")
      printFunc(s"\t${authUri.toString()}")

  /**
    * Tries to obtain the authorization code from a redirect to a local port.
    * This method is called for redirect URIs referring to ''localhost''. It
    * opens a web server at the port specified and waits for a request that
    * contains the code and expected state as parameter.
    *
    * @param port          the port to bind the server
    * @param expectedState the expected state value
    * @param ec            the execution context
    * @param system        the actor system
    * @return a ''Future'' with the authorization code
    */
  private def obtainCodeFromRedirect(port: Int, expectedState: String)
                                    (implicit ec: ExecutionContext, system: ActorSystem): Future[String] =
    val promiseCode = Promise[String]()
    val handler: HttpRequest => HttpResponse = request => {
      val params = for
        code <- request.uri.query().get("code")
        state <- request.uri.query().get("state")
      yield (code, state)

      val status = params match
        case Some((code, state)) if state == expectedState =>
          promiseCode.trySuccess(code)
          StatusCodes.OK
        case Some(_) =>
          promiseCode.tryFailure(new IllegalStateException("Wrong state value in redirect URI."))
          StatusCodes.BadRequest
        case None =>
          promiseCode.tryFailure(
            new IllegalStateException("No authorization code or state value passed to redirect URI."))
          StatusCodes.BadRequest
      HttpResponse(status)
    }

    val serverSource = Http().newServerAt(interface = "localhost", port = port).connectionSource()
    val bindFuture = serverSource.to(Sink.foreach { con =>
      con handleWithSyncHandler handler
    }).run()

    promiseCode.future.andThen {
      case _ => bindFuture foreach (_.unbind())
    }

  /**
    * Invokes the token service to obtain a token pair for the given
    * authorization code.
    *
    * @param config       the OAuth configuration
    * @param code         the authorization code
    * @param tokenService the service to retrieve a token
    * @param ec           the execution context
    * @param system       the actor system
    * @return a ''Future'' with the token pair
    */
  private def fetchTokens(config: IDPConfig, code: String, tokenService: TokenService)
                         (implicit ec: ExecutionContext, system: ActorSystem):
  Future[OAuthTokenData] =
    implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped
    val httpActor = system.spawn(HttpRequestSender(config.oauthConfig.tokenEndpoint), "httpRequestActor")
    tokenService.fetchTokens(httpActor, config, config.oauthConfig.clientSecret, code) andThen {
      case _ => httpActor ! HttpRequestSender.Stop
    }
