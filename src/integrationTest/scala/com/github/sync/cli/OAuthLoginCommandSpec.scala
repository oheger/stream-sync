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

package com.github.sync.cli

import java.io.IOException
import java.net.{ServerSocket, Socket}
import java.nio.file.Paths

import akka.Done
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes, Uri}
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.github.sync.cli.ParameterManager.Parameters
import com.github.sync.cli.oauth.{BrowserHandler, OAuthLoginCommand}
import com.github.sync.crypt.Secret
import com.github.sync.http.OAuthStorageConfig
import com.github.sync.http.oauth._
import com.github.sync.{AsyncTestHelper, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock._
import org.mockito.Matchers.any
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.{ExecutionContext, Future}

object OAuthLoginCommandSpec {
  /** The authorization URI used by tests. */
  private val AuthorizationUri = "https://auth.idp.org/test?params=many"

  /** The path for the token endpoint of the mock server. */
  private val TokenEndpoint = "/tokens"

  /** A test storage configuration used by test cases. */
  private val TestStorageConfig = OAuthStorageConfig(Paths.get("idp-data"), "testIdp", None)

  /**
    * A basic OAuth configuration. The URL for the token endpoint has to be
    * generated dynamically.
    */
  private val BaseOAuthConfig = OAuthConfig(authorizationEndpoint = AuthorizationUri,
    tokenEndpoint = "TBD", redirectUri = "https://redirect.org", scope = "foo bar",
    clientID = "test-client-1234")

  /** Client secret of the test IDP. */
  private val ClientSecret = Secret("the-secret")

  /** Test token material. */
  private val TestTokenData = OAuthTokenData(accessToken = "test_access_token",
    refreshToken = "test_refresh_token")

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
  private def fetchFreePort(): Int = {
    var socket: ServerSocket = null
    try {
      socket = new ServerSocket(0)
      socket.getLocalPort
    } finally {
      socket.close()
    }
  }

  /**
    * Checks whether the given port has been released. Tries to connect to the
    * port. If the HTTP server at this port has been terminated, the connection
    * should fail.
    *
    * @param port the port to be tested
    * @return a flag whether the given port has been released
    */
  private def portIsReleased(port: Int): Boolean = {
    var socket: Socket = null
    try {
      socket = new Socket("localhost", port)
      false
    } catch {
      case _: IOException => true
    } finally {
      if (socket != null) socket.close()
    }
  }
}

/**
  * Test class for ''OAuthLoginCommand''.
  */
class OAuthLoginCommandSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with WireMockSupport with AsyncTestHelper {
  def this() = this(ActorSystem("OAuthLoginCommandSpec"))

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit shutdownActorSystem system
  }

  import OAuthLoginCommandSpec._
  import system.dispatcher

  /** The object to materialize streams in implicit scope. */
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  /**
    * Prepares the mock server to answer a successful token request.
    */
  private def stubTokenRequest(): Unit = {
    stubFor(post(urlPathEqualTo(TokenEndpoint))
      .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
      .withRequestBody(containing(s"client_id=${BaseOAuthConfig.clientID}"))
      .withRequestBody(containing(s"client_secret=${ClientSecret.secret}"))
      .withRequestBody(containing(s"code=$Code"))
      .willReturn(aResponse().withStatus(200)
        .withBody(TokenResponse)))
  }

  /**
    * Creates a test OAuth configuration from the basic configuration and the
    * current token endpoint URL pointing to the mock server.
    *
    * @return the test OAuth configuration
    */
  private def createTestOAuthConfig(): OAuthConfig =
    BaseOAuthConfig.copy(tokenEndpoint = serverUri(TokenEndpoint))

  /**
    * Checks whether the HTTP server at the given port has been properly
    * closed.
    *
    * @param port the port the server has been listening on
    */
  private def checkHttpServerClosed(port: Int): Unit = {
    awaitCond(portIsReleased(port))
  }

  "OAuthLoginCommand" should "create a default browser helper" in {
    val command = new OAuthLoginCommand

    command.browserHandler should not be null
  }

  it should "create a default token retriever service" in {
    val command = new OAuthLoginCommand

    command.tokenService should be(OAuthTokenRetrieverServiceImpl)
  }

  it should "execute a successful login" in {
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
    val config = createTestOAuthConfig().copy(redirectUri = s"http://localhost:$port")
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val helper = new CommandTestHelper(config)

    val result = futureResult(helper.prepareBrowserHandlerToCallRedirectUri(config.redirectUri + "?code=" + Code)
      .runCommand())
    result should include("successful")
    helper.verifyTokenStored()
    checkHttpServerClosed(port)
  }

  it should "handle a local redirect URI if no code parameter is passed" in {
    val port = fetchFreePort()
    val config = createTestOAuthConfig().copy(redirectUri = s"http://localhost:$port")
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val helper = new CommandTestHelper(config)

    val exception = expectFailedFuture[IllegalStateException] {
      helper.prepareBrowserHandlerToCallRedirectUri(config.redirectUri, StatusCodes.BadRequest)
        .runCommand()
    }
    exception.getMessage should include("authorization code")
    checkHttpServerClosed(port)
  }

  /**
    * A test helper class managing a command instance to be tested and its
    * dependencies.
    *
    * @param testOAutConfig the OAuth configuration to be used
    */
  private class CommandTestHelper(val testOAutConfig: OAuthConfig = createTestOAuthConfig()) {
    /** Mock for the token retriever service. */
    private val tokenService = createTokenService()

    /** Mock for the storage service. */
    private val storageService = createStorageService()

    /** Mock for the browser handler. */
    private val browserHandler = createBrowserHandler()

    /** Buffer to capture the output of the command. */
    private val outputBuf = new StringBuffer

    /** The command to be tested. */
    private val command = createCommand()

    /**
      * Executes the test command and returns the resulting ''Future''.
      *
      * @param consoleReader the console reader to be used
      * @return the ''Future'' returned by the command
      */
    def runCommand()(implicit consoleReader: ConsoleReader): Future[String] =
      command.run(TestStorageConfig, storageService, Parameters(Map.empty, Set.empty))

    /**
      * Executes the test command with an initialized console reader mock and
      * returns the resulting ''Future''. Here it is expected that the code is
      * entered manually via the console.
      *
      * @return the ''Future'' returned by the command
      */
    def runCommandWithCodeInput(): Future[String] = {
      implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
      when(consoleReader.readOption("Enter authorization code", password = true)).thenReturn(Code)
      runCommand()
    }

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
    def runCommandSuccessfulCheckMessage(): CommandTestHelper = {
      val result = runCommandSuccessful()
      result should include("Login")
      result should include("successful")
      this
    }

    /**
      * Verifies that tokens have been stored after they had been retrieved
      * from the IDP.
      *
      * @return this test helper
      */
    def verifyTokenStored(): CommandTestHelper = {
      Mockito.verify(storageService).saveTokens(TestStorageConfig, TestTokenData)
      this
    }

    /**
      * Verifies that the browser helper has been called correctly to open the
      * browser.
      *
      * @return this test helper
      */
    def verifyBrowserOpened(): CommandTestHelper = {
      Mockito.verify(browserHandler).openBrowser(AuthorizationUri)
      this
    }

    /**
      * Tests that the output generated by the command contains the given text.
      *
      * @param sub the text to be matched
      * @return this test helper
      */
    def commandOutputContaining(sub: String): CommandTestHelper = {
      outputBuf.toString should include(sub)
      this
    }

    /**
      * Tests that the output generated by the command does not contain the
      * given text.
      *
      * @param sub the text to be matched
      * @return this test helper
      */
    def commandOutputNotContaining(sub: String): CommandTestHelper = {
      outputBuf.toString should not include sub
      this
    }

    /**
      * Prepares the mock for the browser handler to invoke the redirect URI
      * with the code as parameter. This is used to test whether localhost
      * redirect URIs are handled in a special way.
      *
      * @return this test helper
      */
    def prepareBrowserHandlerToCallRedirectUri(uri: String, expStatus: StatusCode = StatusCodes.OK):
    CommandTestHelper = {
      when(browserHandler.openBrowser(AuthorizationUri)).thenAnswer((_: InvocationOnMock) => {
        val redirectRequest = HttpRequest(uri = uri)
        val response = futureResult(Http().singleRequest(redirectRequest))
        response.status should be(expStatus)
        true
      })
      this
    }

    /**
      * Prepares the mock for the browser handler to return a failure result.
      *
      * @return this test helper
      */
    def failBrowserHandler(): CommandTestHelper = {
      initBrowserHandler(browserHandler, success = false)
      this
    }

    /**
      * Prepares the mock for the token service to return a failed future when
      * asked for the authorization URI.
      *
      * @param ex the exception to fail the future with
      * @return this test helper
      */
    def failAuthorizationUri(ex: Throwable): CommandTestHelper = {
      initTokenServiceAuthorizationUri(tokenService, Future.failed(ex))
      this
    }

    /**
      * Initializes the browser handler mock to return the given success result
      * when called for the test URI.
      *
      * @param handler the browser handler mock
      * @param success the success result
      */
    private def initBrowserHandler(handler: BrowserHandler, success: Boolean): Unit = {
      when(handler.openBrowser(AuthorizationUri)).thenReturn(success)
    }

    /**
      * Creates the mock for the browser handler and initializes it to return
      * a success result.
      *
      * @return the mock for the browser handler
      */
    private def createBrowserHandler(): BrowserHandler = {
      val handler = mock[BrowserHandler]
      initBrowserHandler(handler, success = true)
      handler
    }

    /**
      * Prepares the mock for the token service to return the the given result
      * for the authorization URI.
      *
      * @param service   the mock for the service
      * @param uriFuture the ''Future'' with the URI
      */
    private def initTokenServiceAuthorizationUri(service: OAuthTokenRetrieverService[OAuthConfig, Secret,
      OAuthTokenData], uriFuture: Future[Uri]): Unit = {
      when(service.authorizeUrl(testOAutConfig)).thenReturn(uriFuture)
    }

    /**
      * Creates the mock for the token service and initializes it to return the
      * test authorization URI and to forward token requests to the actual
      * service.
      *
      * @return the mock token service
      */
    private def createTokenService(): OAuthTokenRetrieverService[OAuthConfig, Secret, OAuthTokenData] = {
      val service = mock[OAuthTokenRetrieverService[OAuthConfig, Secret, OAuthTokenData]]
      initTokenServiceAuthorizationUri(service, Future.successful(AuthorizationUri))
      when(service.fetchTokens(any(), any(), any(), any())(any(), any()))
        .thenAnswer((invocation: InvocationOnMock) => {
          val args = invocation.getArguments
          println(s"fetchToken operation with arguments ${args.mkString(", ")}")
          OAuthTokenRetrieverServiceImpl.fetchTokens(args.head.asInstanceOf[ActorRef],
            args(1).asInstanceOf[OAuthConfig], args(2).asInstanceOf[Secret],
            args(3).asInstanceOf[String])(args(4).asInstanceOf[ExecutionContext],
            args(5).asInstanceOf[ActorMaterializer])
        })
      service
    }

    /**
      * Creates the mock for the storage service and initializes it to return
      * test data for the test IDP.
      *
      * @return the mock for the storage service
      */
    private def createStorageService(): OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData] = {
      val service = mock[OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData]]
      when(service.loadConfig(TestStorageConfig)).thenReturn(Future.successful(testOAutConfig))
      when(service.loadClientSecret(TestStorageConfig)).thenReturn(Future.successful(ClientSecret))
      when(service.saveTokens(TestStorageConfig, TestTokenData))
        .thenReturn(Future.successful(Done))
      service
    }

    /**
      * Creates the command instance to be tested.
      *
      * @return the test command instance
      */
    private def createCommand(): OAuthLoginCommand =
      new OAuthLoginCommand(tokenService, browserHandler) {
        /**
          * @inheritdoc This implementation captures the output.
          */
        override protected def output(s: String): Unit = {
          super.output(s)
          outputBuf append s
        }
      }
  }

}
