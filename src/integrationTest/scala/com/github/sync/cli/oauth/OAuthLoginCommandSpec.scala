/*
 * Copyright 2018-2023 The Developers Team.
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

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.{ActorSystem, typed}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes, Uri}
import akka.testkit.TestKit
import com.github.cloudfiles.core.http.auth.{OAuthConfig, OAuthTokenData}
import com.github.cloudfiles.core.http.{HttpRequestSender, Secret}
import com.github.scli.ConsoleReader
import com.github.sync.cli.oauth.OAuthParameterManager.LoginCommandConfig
import com.github.sync.oauth.{SyncOAuthStorageConfig, *}
import com.github.sync.{AsyncTestHelper, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock.*
import org.mockito.ArgumentMatchers.{any, eq as argEq}
import org.mockito.{ArgumentCaptor, Mockito}
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.net.{ServerSocket, Socket}
import java.nio.file.Paths
import scala.concurrent.Future

object OAuthLoginCommandSpec:
  /** The authorization URI used by tests. */
  private val AuthorizationUri = "https://auth.idp.org/test?params=many"

  /** The path for the token endpoint of the mock server. */
  private val TokenEndpoint = "/tokens"

  /** A test storage configuration used by test cases. */
  private val TestStorageConfig = SyncOAuthStorageConfig(Paths.get("idp-data"), "testIdp", None)

  /** The configuration for the login command. */
  private val LoginConfig = LoginCommandConfig(TestStorageConfig)

  /** Client secret of the test IDP. */
  private val ClientSecret = Secret("the-secret")

  /** Test token material. */
  private val TestTokenData = OAuthTokenData(accessToken = "test_access_token",
    refreshToken = "test_refresh_token")

  /**
    * A basic OAuth configuration. The URL for the token endpoint has to be
    * generated dynamically.
    */
  private val BaseOAuthConfig = IDPConfig(authorizationEndpoint = AuthorizationUri, scope = "foo bar",
    oauthConfig = OAuthConfig(tokenEndpoint = "TBD", redirectUri = "https://redirect.org",
      clientID = "test-client-1234", clientSecret = ClientSecret, initTokenData = TestTokenData))

  /** The test authorization code. */
  private val Code = "authorizationCode"

  /** Response for a successful token request. */
  private val TokenResponse =
    s"""
       |{
       |  "access_token": "${TestTokenData.accessToken}",
       |  "refresh_token": "${TestTokenData.refreshToken}"
       |}
       |""".stripMargin


  /**
    * Obtains a free network port.
    *
    * @return the port number
    */
  private def fetchFreePort(): Int =
    var socket: ServerSocket = null
    try
      socket = new ServerSocket(0)
      socket.getLocalPort
    finally
      socket.close()

  /**
    * Checks whether the given port has been released. Tries to connect to the
    * port. If the HTTP server at this port has been terminated, the connection
    * should fail.
    *
    * @param port the port to be tested
    * @return a flag whether the given port has been released
    */
  private def portIsReleased(port: Int): Boolean =
    var socket: Socket = null
    try
      socket = new Socket("localhost", port)
      false
    catch
      case _: IOException => true
    finally
      if socket != null then socket.close()

/**
  * Test class for OAuth login functionality.
  */
class OAuthLoginCommandSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with WireMockSupport with AsyncTestHelper :
  def this() = this(ActorSystem("OAuthLoginCommandSpec"))

  override protected def afterAll(): Unit =
    super.afterAll()
    TestKit shutdownActorSystem system

  import OAuthLoginCommandSpec._
  import system.dispatcher

  implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped

  /**
    * Prepares the mock server to answer a successful token request.
    */
  private def stubTokenRequest(): Unit =
    stubFor(post(urlPathEqualTo(TokenEndpoint))
      .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
      .withRequestBody(containing(s"client_id=${BaseOAuthConfig.oauthConfig.clientID}"))
      .withRequestBody(containing(s"client_secret=${ClientSecret.secret}"))
      .withRequestBody(containing(s"code=$Code"))
      .willReturn(aResponse().withStatus(200)
        .withBody(TokenResponse)))

  /**
    * Creates a test OAuth configuration from the basic configuration and the
    * current token endpoint URL pointing to the mock server.
    *
    * @return the test OAuth configuration
    */
  private def createTestOAuthConfig(): IDPConfig =
    BaseOAuthConfig.copy(oauthConfig = BaseOAuthConfig.oauthConfig.copy(tokenEndpoint = serverUri(TokenEndpoint)))

  /**
    * Checks whether the HTTP server at the given port has been properly
    * closed.
    *
    * @param port the port the server has been listening on
    */
  private def checkHttpServerClosed(port: Int): Unit =
    awaitCond(portIsReleased(port))

  "The login function" should "execute a successful login" in {
    stubTokenRequest()
    val helper = new CommandTestHelper

    helper.runCommandSuccessfulCheckMessage()
      .verifyBrowserOpened()
      .verifyTokenStored()
      .commandOutputContaining("Opening Web browser")
      .commandOutputNotContaining("Could not open Web browser")
  }

  it should "handle a failure to open the browser" in {
    stubTokenRequest()
    val helper = new CommandTestHelper

    helper.failBrowserHandler()
      .runCommandSuccessfulCheckMessage()
      .commandOutputContaining("Could not open Web browser")
      .commandOutputContaining(helper.testOAutConfig.authorizationEndpoint)
      .commandOutputContaining("navigate to")
  }

  it should "react on a failed future during execution" in {
    val exception = new IllegalStateException("Command execution failure")
    val helper = new CommandTestHelper

    expectFailedFuture[IllegalStateException](helper.failAuthorizationUri(exception)
      .runCommandWithCodeInput()) should be(exception)
    getAllServeEvents should have size 0
  }

  it should "handle the redirect for localhost redirect URIs" in {
    val port = fetchFreePort()
    val orgConfig = createTestOAuthConfig()
    val config = orgConfig.copy(oauthConfig = orgConfig.oauthConfig.copy(redirectUri = s"http://localhost:$port"))
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val helper = new CommandTestHelper(config)

    val result = futureResult(helper.prepareBrowserHandlerToCallRedirectUri(config.oauthConfig.redirectUri +
      "?code=" + Code).runCommand())
    result should include("successful")
    helper.verifyTokenStored()
    checkHttpServerClosed(port)
  }

  it should "handle a local redirect URI if no code parameter is passed" in {
    val port = fetchFreePort()
    val orgConfig = createTestOAuthConfig()
    val config = orgConfig.copy(oauthConfig = orgConfig.oauthConfig.copy(redirectUri = s"http://localhost:$port"))
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val helper = new CommandTestHelper(config)

    val exception = expectFailedFuture[IllegalStateException] {
      helper.prepareBrowserHandlerToCallRedirectUri(config.oauthConfig.redirectUri + "?q=p", StatusCodes.BadRequest)
        .runCommand()
    }
    exception.getMessage should include("authorization code")
    checkHttpServerClosed(port)
  }

  /**
    * Checks whether an incorrect state value passed to the redirect URI is
    * detected and correctly handled.
    *
    * @param uriQuery the query part to be added to the redirect URI
    */
  private def checkInvalidStateValueIsHandled(uriQuery: String): Unit =
    val port = fetchFreePort()
    val orgConfig = createTestOAuthConfig()
    val config = orgConfig.copy(oauthConfig = orgConfig.oauthConfig.copy(redirectUri = s"http://localhost:$port"))
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val helper = new CommandTestHelper(config)

    val exception = expectFailedFuture[IllegalStateException] {
      helper.prepareBrowserHandlerToCallRedirectUri(config.oauthConfig.redirectUri + uriQuery,
        StatusCodes.BadRequest, includeState = false)
        .runCommand()
    }
    exception.getMessage should include("state value")
    checkHttpServerClosed(port)


  it should "check the state value when handling a redirect" in {
    checkInvalidStateValueIsHandled(s"?code=$Code&state=wrongState")
  }

  it should "handle a missing state value in the redirect URI" in {
    checkInvalidStateValueIsHandled("?code=" + Code)
  }

  it should "use random state values in the authorize URL" in {
    val orgConfig = createTestOAuthConfig()
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]

    def doLoginAndObtainState(): String =
      val port = fetchFreePort()
      val config = orgConfig.copy(oauthConfig = orgConfig.oauthConfig.copy(redirectUri = s"http://localhost:$port"))
      val helper = new CommandTestHelper(config)
      futureResult(helper.prepareBrowserHandlerToCallRedirectUri(config.oauthConfig.redirectUri + "?code=" + Code)
        .runCommand())
      helper.fetchState()

    val state1 = doLoginAndObtainState()
    val state2 = doLoginAndObtainState()
    state1 should not be state2
  }

  /**
    * A test helper class managing a command instance to be tested and its
    * dependencies.
    *
    * @param testOAutConfig the OAuth configuration to be used
    */
  private class CommandTestHelper(val testOAutConfig: IDPConfig = createTestOAuthConfig()):
    /** Mock for the token retriever service. */
    private val tokenService = createTokenService()

    /** Mock for the storage service. */
    private val storageService = createStorageService()

    /** Mock for the browser handler. */
    private val browserHandler = createBrowserHandler()

    /** Buffer to capture the output of the command. */
    private val outputBuf = new StringBuffer

    /**
      * Executes the test command and returns the resulting ''Future''.
      *
      * @param consoleReader the console reader to be used
      * @return the ''Future'' returned by the command
      */
    def runCommand()(implicit consoleReader: ConsoleReader): Future[String] =
      OAuthCommandsImpl.login(LoginConfig, storageService, tokenService, browserHandler, consoleReader,
        str => outputBuf.append(str))

    /**
      * Executes the test command with an initialized console reader mock and
      * returns the resulting ''Future''. Here it is expected that the code is
      * entered manually via the console.
      *
      * @return the ''Future'' returned by the command
      */
    def runCommandWithCodeInput(): Future[String] =
      implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
      when(consoleReader.readOption("Enter authorization code", password = true)).thenReturn(Code)
      runCommand()

    /**
      * Executes the test command and returns the message it produces.
      *
      * @return the message from the test command
      */
    def runCommandSuccessful(): String = futureResult(runCommandWithCodeInput())

    /**
      * Executes the test command and expects a successful execution that
      * manifests itself in the result message.
      *
      * @return this test helper
      */
    def runCommandSuccessfulCheckMessage(): CommandTestHelper =
      val result = runCommandSuccessful()
      result should include("Login")
      result should include("successful")
      this

    /**
      * Verifies that tokens have been stored after they had been retrieved
      * from the IDP.
      *
      * @return this test helper
      */
    def verifyTokenStored(): CommandTestHelper =
      Mockito.verify(storageService).saveTokens(TestStorageConfig, TestTokenData)
      this

    /**
      * Verifies that the browser helper has been called correctly to open the
      * browser.
      *
      * @return this test helper
      */
    def verifyBrowserOpened(): CommandTestHelper =
      Mockito.verify(browserHandler).openBrowser(AuthorizationUri)
      this

    /**
      * Tests that the output generated by the command contains the given text.
      *
      * @param sub the text to be matched
      * @return this test helper
      */
    def commandOutputContaining(sub: String): CommandTestHelper =
      outputBuf.toString should include(sub)
      this

    /**
      * Tests that the output generated by the command does not contain the
      * given text.
      *
      * @param sub the text to be matched
      * @return this test helper
      */
    def commandOutputNotContaining(sub: String): CommandTestHelper =
      outputBuf.toString should not include sub
      this

    /**
      * Prepares the mock for the browser handler to invoke the redirect URI
      * with the code as parameter. This is used to test whether localhost
      * redirect URIs are handled in a special way.
      *
      * @param uri          the URI to redirect to
      * @param expStatus    the expected response status
      * @param includeState flag whether to include the state that was passed
      *                     to the token service
      * @return this test helper
      */
    def prepareBrowserHandlerToCallRedirectUri(uri: String, expStatus: StatusCode = StatusCodes.OK,
                                               includeState: Boolean = true): CommandTestHelper =
      when(browserHandler.openBrowser(AuthorizationUri)).thenAnswer((_: InvocationOnMock) => {
        val redirectUri = if includeState then
          val state = fetchState()
          s"$uri&state=$state"
        else uri

        val redirectRequest = HttpRequest(uri = redirectUri)
        val response = futureResult(Http().singleRequest(redirectRequest))
        response.status should be(expStatus)
        true
      })
      this

    /**
      * Prepares the mock for the browser handler to return a failure result.
      *
      * @return this test helper
      */
    def failBrowserHandler(): CommandTestHelper =
      initBrowserHandler(browserHandler, success = false)
      this

    /**
      * Prepares the mock for the token service to return a failed future when
      * asked for the authorization URI.
      *
      * @param ex the exception to fail the future with
      * @return this test helper
      */
    def failAuthorizationUri(ex: Throwable): CommandTestHelper =
      initTokenServiceAuthorizationUri(tokenService, Future.failed(ex))
      this

    /**
      * Returns the state string that was passed to the token service.
      *
      * @return the state string
      */
    def fetchState(): String =
      val captor = ArgumentCaptor.forClass(classOf[Option[String]])
      Mockito.verify(tokenService).authorizeUrl(any(), captor.capture())(any())
      captor.getValue.get

    /**
      * Initializes the browser handler mock to return the given success result
      * when called for the test URI.
      *
      * @param handler the browser handler mock
      * @param success the success result
      */
    private def initBrowserHandler(handler: BrowserHandler, success: Boolean): Unit =
      when(handler.openBrowser(AuthorizationUri)).thenReturn(success)

    /**
      * Creates the mock for the browser handler and initializes it to return
      * a success result.
      *
      * @return the mock for the browser handler
      */
    private def createBrowserHandler(): BrowserHandler =
      val handler = mock[BrowserHandler]
      initBrowserHandler(handler, success = true)
      handler

    /**
      * Prepares the mock for the token service to return the the given result
      * for the authorization URI.
      *
      * @param service   the mock for the service
      * @param uriFuture the ''Future'' with the URI
      */
    private def initTokenServiceAuthorizationUri(service: OAuthTokenRetrieverService[IDPConfig, Secret,
      OAuthTokenData], uriFuture: Future[Uri]): Unit =
      when(service.authorizeUrl(argEq(testOAutConfig), any())(any())).thenReturn(uriFuture)

    /**
      * Creates the mock for the token service and initializes it to return the
      * test authorization URI and to forward token requests to the actual
      * service.
      *
      * @return the mock token service
      */
    private def createTokenService(): OAuthTokenRetrieverService[IDPConfig, Secret, OAuthTokenData] =
      val service = mock[OAuthTokenRetrieverService[IDPConfig, Secret, OAuthTokenData]]
      initTokenServiceAuthorizationUri(service, Future.successful(AuthorizationUri))
      when(service.fetchTokens(any(), any(), any(), any())(any()))
        .thenAnswer((invocation: InvocationOnMock) => {
          val args = invocation.getArguments
          OAuthTokenRetrieverServiceImpl.fetchTokens(args.head.asInstanceOf[ActorRef[HttpRequestSender.HttpCommand]],
            args(1).asInstanceOf[IDPConfig], args(2).asInstanceOf[Secret],
            args(3).asInstanceOf[String])(args(4).asInstanceOf[akka.actor.typed.ActorSystem[_]])
        })
      service

    /**
      * Creates the mock for the storage service and initializes it to return
      * test data for the test IDP.
      *
      * @return the mock for the storage service
      */
    private def createStorageService(): OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData] =
      val service = mock[OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData]]
      when(service.loadIdpConfig(TestStorageConfig)).thenReturn(Future.successful(testOAutConfig))
      when(service.saveTokens(TestStorageConfig, TestTokenData)).thenReturn(Future.successful(Done))
      service
