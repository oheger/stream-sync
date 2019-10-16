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

package com.github.sync.webdav.oauth

import java.io.IOException
import java.nio.file.Paths

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.stream.KillSwitch
import akka.testkit.{ImplicitSender, TestKit}
import com.github.sync.crypt.Secret
import com.github.sync.webdav.{DepthHeader, HttpRequestActor}
import org.mockito.Mockito._
import org.mockito.Matchers.{any, eq => argEq}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object OAuthTokenActorSpec {
  /** The URI of the test token endpoint. */
  private val TokenUri = "https://test.idp.org/tokens"

  /** OAuth configuration of the test client. */
  private val TestConfig = OAuthConfig(authorizationEndpoint = "https://auth.idp.org/auth",
    scope = "test run", redirectUri = "https://redirect.uri.org/", clientID = "testClient",
    tokenEndpoint = TokenUri)

  /** Secret of the test client. */
  private val ClientSecret = Secret("theSecretOfTheTestClient")

  /** A test storage configuration. */
  private val TestStorageConfig = OAuthStorageConfig(Paths.get("/token/data"), "test-idp", None)

  /** A test request used by the tests. */
  private val TestRequest = HttpRequest(method = HttpMethods.POST, uri = "http://test.org/foo",
    headers = List(DepthHeader("1")))

  /** Test token data. */
  private val TestTokens = OAuthTokenData(accessToken = "<access_token>", refreshToken = "<refresh_token>")

  /** Token data representing refreshed tokens. */
  private val RefreshedTokens = OAuthTokenData(accessToken = "<new_access>", refreshToken = "<new_refresh>")

  /** Test data associated with a request. */
  private val RequestData = new Object

  /** A test request with the default authorization header. */
  private val TestRequestWithAuth = createAuthorizedTestRequest()

  /**
    * A data class describing a response to be sent by the stub actor. The
    * response can be successful or failed. An optional delay can be
    * configured.
    *
    * @param triedResponse the tried response to be sent
    * @param optDelay      an optional delay
    */
  case class StubResponse(triedResponse: Try[HttpResponse], optDelay: Option[FiniteDuration] = None)

  /**
    * A message processed by ''HttpStubActor'' that defines the responses to be
    * sent for incoming requests. Responses can also fail.
    *
    * @param responses the list with tried responses
    */
  case class StubResponses(responses: List[StubResponse])

  /**
    * Checks whether the given request matches the test request with an
    * ''Authorization'' header.
    *
    * @param req the request to be checked
    * @return a flag whether the request is as expected
    */
  private def checkRequest(req: HttpRequest): Boolean = {
    val optAuth = req.header[Authorization]
    if (optAuth.isDefined) {
      val noAuthHeaders = req.headers filterNot (_ == optAuth.get)
      val baseReq = req.copy(headers = noAuthHeaders)
      TestRequest == baseReq
    } else false
  }

  /**
    * Creates a test request that has the standard authorization header.
    *
    * @return the authorized test request
    */
  private def createAuthorizedTestRequest(): HttpRequest = {
    val headerAuth = Authorization(OAuth2BearerToken(TestTokens.accessToken))
    val newHeaders = headerAuth :: TestRequest.headers.toList
    TestRequest.copy(headers = newHeaders)
  }

  /**
    * Generates the data structures representing the exception for a failed
    * (non-success) HTTP response.
    *
    * @param response the failed response to be wrapped
    * @return the wrapped failed response
    */
  private def failedResponse(response: HttpResponse): Failure[HttpResponse] =
    Failure(HttpRequestActor.RequestException("Failed response",
      HttpRequestActor.FailedResponseException(response),
      HttpRequestActor.SendRequest(TestRequestWithAuth, RequestData)))

  /**
    * An actor implementation that simulates an HTTP actor. This actor expects
    * ''SendRequest'' messages that must correspond to the test request. These
    * requests are answered by configurable responses. From the requests
    * received the ''Authorization'' headers and recorded and can be queried.
    */
  class HttpStubActor extends Actor {
    /** The responses to send for requests. */
    private var responses = List.empty[StubResponse]

    override def receive: Receive = {
      case StubResponses(stubResponses) =>
        responses = stubResponses

      case req: HttpRequestActor.SendRequest if checkRequest(req.request) =>
        val result = responses.head.triedResponse match {
          case Success(response) =>
            HttpRequestActor.Result(req, response)
          case Failure(exception) =>
            Status.Failure(exception)
        }
        if (responses.head.optDelay.isDefined) {
          implicit val ec: ExecutionContext = context.dispatcher
          context.system.scheduler.scheduleOnce(responses.head.optDelay.get, sender(), result)
        } else {
          sender() ! result
        }
        responses = responses.tail
    }
  }

}

/**
  * Test class for ''OAuthTokenActor''.
  */
class OAuthTokenActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("OAuthTokenActorSpec"))

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit shutdownActorSystem system
  }

  import OAuthTokenActorSpec._

  /**
    * Checks the ''Authorization'' header in the request of the given result.
    * It must contain the given access token.
    *
    * @param result         the result object
    * @param expAccessToken the expected access token
    */
  private def checkAuthorization(result: HttpRequestActor.Result, expAccessToken: String = TestTokens.accessToken):
  Unit = {
    val optAuthHeader = result.request.request.header[Authorization]
    optAuthHeader.exists { auth =>
      auth.credentials.token() == expAccessToken
    } shouldBe true
  }

  "OAuthTokenActor" should "add the Authorization header to requests" in {
    val response = HttpResponse(status = StatusCodes.Accepted)
    val helper = new TokenActorTestHelper

    helper.initResponses(List(StubResponse(Success(response))))
      .sendTestRequest()
    val result = expectMsgType[HttpRequestActor.Result]
    result.response should be(response)
    checkAuthorization(result)
  }

  it should "handle a request exception from the target actor" in {
    val exception = HttpRequestActor.RequestException("failed request", new IllegalStateException("crash"),
      HttpRequestActor.SendRequest(TestRequest, RequestData))
    val responses = List(StubResponse(Failure(exception)))
    val helper = new TokenActorTestHelper

    helper.initResponses(responses)
      .sendTestRequest()
    expectMsg(Status.Failure(exception))
  }

  it should "handle a response status exception from the target actor" in {
    val response = HttpResponse(status = StatusCodes.BadRequest)
    val failedResp = failedResponse(response)
    val responses = List(StubResponse(failedResp))
    val helper = new TokenActorTestHelper

    helper.initResponses(responses)
      .sendTestRequest()
    expectMsg(Status.Failure(failedResp.exception))
  }

  it should "obtain another access token when receiving an UNAUTHORIZED status" in {
    val response1 = HttpResponse(status = StatusCodes.Unauthorized)
    val response2 = HttpResponse(status = StatusCodes.Created)
    val responses = List(StubResponse(failedResponse(response1)),
      StubResponse(Success(response2)))
    val helper = new TokenActorTestHelper

    helper.initResponses(responses)
      .expectTokenRequest(Future.successful(RefreshedTokens))
      .sendTestRequest()
    val result = expectMsgType[HttpRequestActor.Result]
    result.response should be(response2)
    checkAuthorization(result, RefreshedTokens.accessToken)
  }

  it should "hold incoming requests until the access token has been refreshed" in {
    val promise = Promise[OAuthTokenData]()
    val response1 = HttpResponse(status = StatusCodes.Unauthorized)
    val response2 = HttpResponse(status = StatusCodes.Created)
    val response3 = HttpResponse(status = StatusCodes.Accepted)
    val responses = List(StubResponse(failedResponse(response1)),
      StubResponse(Success(response2)), StubResponse(Success(response3)))
    val helper = new TokenActorTestHelper

    helper.initResponses(responses)
      .expectTokenRequest(promise.future)
      .sendTestRequest()
    expectNoMessage(100.millis)
    helper.sendTestRequest()
    promise.complete(Success(RefreshedTokens))
    val result1 = expectMsgType[HttpRequestActor.Result]
    val result2 = expectMsgType[HttpRequestActor.Result]
    result1.response should be(response2)
    result2.response should be(response3)
    checkAuthorization(result1, RefreshedTokens.accessToken)
    checkAuthorization(result2, RefreshedTokens.accessToken)
    helper.verifyTokenRefreshed()
  }

  it should "do only a single refresh operation for concurrent unauthorized requests" in {
    val promise = Promise[OAuthTokenData]()
    val response1 = HttpResponse(status = StatusCodes.Unauthorized)
    val response2 = HttpResponse(status = StatusCodes.Unauthorized)
    val response3 = HttpResponse(status = StatusCodes.Created)
    val response4 = HttpResponse(status = StatusCodes.Accepted)
    val responses = List(StubResponse(failedResponse(response1)),
      StubResponse(failedResponse(response2)),
      StubResponse(Success(response3)), StubResponse(Success(response4)))
    val helper = new TokenActorTestHelper

    helper.initResponses(responses)
      .expectTokenRequest(promise.future)
      .sendTestRequest()
      .sendTestRequest()
    expectNoMessage(100.millis)
    promise.complete(Success(RefreshedTokens))
    val result1 = expectMsgType[HttpRequestActor.Result]
    val result2 = expectMsgType[HttpRequestActor.Result]
    result1.response should be(response3)
    result2.response should be(response4)
    checkAuthorization(result1, RefreshedTokens.accessToken)
    checkAuthorization(result2, RefreshedTokens.accessToken)
    helper.verifyTokenRefreshed()
  }

  it should "do only a single refresh operation for concurrent unauthorized responses" in {
    val response1 = HttpResponse(status = StatusCodes.Unauthorized)
    val response2 = HttpResponse(status = StatusCodes.Unauthorized)
    val response3 = HttpResponse(status = StatusCodes.Created)
    val response4 = HttpResponse(status = StatusCodes.Accepted)
    val responses = List(StubResponse(failedResponse(response1)),
      StubResponse(failedResponse(response2), optDelay = Some(100.millis)),
      StubResponse(Success(response3)), StubResponse(Success(response4)))
    val helper = new TokenActorTestHelper

    helper.initResponses(responses)
      .expectTokenRequest(Future.successful(RefreshedTokens))
      .sendTestRequest()
      .sendTestRequest()
    val result1 = expectMsgType[HttpRequestActor.Result]
    val result2 = expectMsgType[HttpRequestActor.Result]
    result1.response should be(response3)
    result2.response should be(response4)
    checkAuthorization(result1, RefreshedTokens.accessToken)
    checkAuthorization(result2, RefreshedTokens.accessToken)
    helper.verifyTokenRefreshed()
  }

  it should "trigger the kill switch in case of a failed refresh operation" in {
    val failedResp = failedResponse(HttpResponse(status = StatusCodes.Unauthorized))
    val refreshEx = HttpRequestActor.RequestException("Failed", new IOException("OAuthError"), null)
    val helper = new TokenActorTestHelper

    helper.initResponses(List(StubResponse(failedResp), StubResponse(failedResp)))
      .expectTokenRequest(Future.failed(refreshEx))
      .sendTestRequest()
      .sendTestRequest()
    (1 to 2) foreach { _ =>
      val response = expectMsgType[Status.Failure]
      response.cause match {
        case HttpRequestActor.RequestException(_, HttpRequestActor.FailedResponseException(r), sendReq) =>
          r.status should be(StatusCodes.Unauthorized)
          sendReq.request should be(TestRequest)
        case c =>
          fail("Unexpected exception", c)
      }
    }
    helper.verifyKillSwitchTriggered(refreshEx)
  }

  /**
    * A test helper class that manages a test instance and all its
    * dependencies.
    */
  private class TokenActorTestHelper {
    /** Mock for the storage service. */
    private val storageService = mock[OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData]]

    /** Mock for the token service. */
    private val tokenService = mock[OAuthTokenRetrieverService[OAuthConfig, Secret, OAuthTokenData]]

    /** Mock for the kill switch to handle token refresh failures. */
    private val killSwitch = mock[KillSwitch]

    /** The target HTTP actor. */
    private val targetHttpActor = system.actorOf(Props[HttpStubActor])

    /**
      * The actor for interacting with the IDP. Note that this actor is not
      * actually invoked; the reference is just passed to the mock token
      * service.
      */
    private val idpHttpActor = system.actorOf(Props[HttpStubActor])

    /** The actor to be tested. */
    private val tokenActor = createTokenActor()

    /**
      * Prepares the stub HTTP actor to send the given responses for requests.
      *
      * @param responses the list with tried responses
      * @return this test helper
      */
    def initResponses(responses: List[StubResponse]): TokenActorTestHelper = {
      targetHttpActor ! StubResponses(responses)
      this
    }

    /**
      * Sends the test request to the test token actor.
      *
      * @return this test helper
      */
    def sendTestRequest(): TokenActorTestHelper = {
      tokenActor ! HttpRequestActor.SendRequest(TestRequest, RequestData)
      this
    }

    /**
      * Prepares the mock for the token service to expect a refresh token
      * request that is answered with the given result.
      *
      * @param tokenResult the result ''Future'' for the request
      * @return this test helper
      */
    def expectTokenRequest(tokenResult: Future[OAuthTokenData]): TokenActorTestHelper = {
      when(tokenService.refreshToken(argEq(idpHttpActor), argEq(TestConfig), argEq(ClientSecret),
        argEq(TestTokens.refreshToken))(any(), any())).thenReturn(tokenResult)
      this
    }

    /**
      * Verifies that exactly one refresh operation was performed.
      *
      * @return this test helper
      */
    def verifyTokenRefreshed(): TokenActorTestHelper = {
      verify(tokenService).refreshToken(argEq(idpHttpActor), argEq(TestConfig), argEq(ClientSecret),
        argEq(TestTokens.refreshToken))(any(), any())
      this
    }

    /**
      * Verifies that the kill switch has been invoked with the given
      * exception.
      *
      * @param expException the expected exception
      * @return this test helper
      */
    def verifyKillSwitchTriggered(expException: Throwable): TokenActorTestHelper = {
      verify(killSwitch).abort(expException)
      this
    }

    /**
      * Creates the token actor to be tested.
      *
      * @return the test actor instance
      */
    private def createTokenActor(): ActorRef =
      system.actorOf(OAuthTokenActor(targetHttpActor, 1, idpHttpActor, TestStorageConfig, TestConfig,
        ClientSecret, TestTokens, storageService, tokenService, killSwitch))
  }

}
