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

package com.github.sync.oauth

import com.github.cloudfiles.core.http.auth.{OAuthConfig, OAuthTokenData}
import com.github.cloudfiles.core.http.{HttpRequestSender, Secret}
import com.github.sync.AsyncTestHelper
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.Uri.Query
import org.apache.pekko.http.scaladsl.model.headers.`Content-Type`
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.io.IOException
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

object OAuthTokenRetrieverServiceImplSpec:
  /** The host name of the test IDP. */
  private val Host = "my-idp.org"

  /** The path of the authorization endpoint. */
  private val AuthPath = "/authorize"

  /** The path of the token endpoint. */
  private val TokenPath = "/tokens"

  /** Constant simulating an authorization code. */
  private val AuthCode = "test_authorization_code"

  /** The client secret used by tests. */
  private val ClientSecret = "test_client_secret"

  /** Test token data. */
  private val TestTokens = OAuthTokenData("testAccessToken", "testRefreshToken")

  /** A test configuration object. */
  private val TestConfig = IDPConfig(authorizationEndpoint = s"https://$Host$AuthPath", scope = "foo bar",
    oauthConfig = OAuthConfig(tokenEndpoint = s"https://$Host$TokenPath", clientID = "testClient",
      redirectUri = "http://localhost:12345", clientSecret = Secret(ClientSecret), initTokenData = TestTokens))

  /**
    * A map with the expected parameters for a request to fetch new tokens.
    */
  private val FetchTokenParams = Map("client_id" -> TestConfig.oauthConfig.clientID,
    "redirect_uri" -> TestConfig.oauthConfig.redirectUri, "client_secret" -> ClientSecret,
    "grant_type" -> "authorization_code", "code" -> AuthCode)

  /**
    * A map with the expected parameters for a request to refresh the access
    * token.
    */
  private val RefreshTokenParams = Map("client_id" -> TestConfig.oauthConfig.clientID,
    "redirect_uri" -> TestConfig.oauthConfig.redirectUri, "client_secret" -> ClientSecret,
    "refresh_token" -> TestTokens.refreshToken, "grant_type" -> "refresh_token")

  /** A valid response of the IDP for a token request. */
  private val TokenResponse =
    s"""
       |{
       |  "token_type": "bearer",
       |  "expires_in": 3600,
       |  "scope": "${TestConfig.scope}",
       |  "access_token": "${TestTokens.accessToken}",
       |  "refresh_token": "${TestTokens.refreshToken}"
       |}
       |""".stripMargin

  /**
    * Validates some basic properties of a request and returns a future with
    * the result.
    *
    * @param request the request
    * @param expPath the expected path
    * @param ec      the execution context
    * @return the validation result
    */
  private def validateRequestProperties(request: HttpRequestSender.SendRequest, expPath: String)
                                       (implicit ec: ExecutionContext): Future[Done] = Future {
    val req = request.request
    if req.uri.path.toString() != expPath then
      throw new IllegalArgumentException(s"Wrong path: got ${req.uri.path}, want $expPath")
    if req.method != HttpMethods.POST then
      throw new IllegalArgumentException(s"Wrong method; got ${req.method}, want POST")
    if !req.header[`Content-Type`].map(_.contentType).contains(ContentTypes.`application/x-www-form-urlencoded`) then
      throw new IllegalArgumentException(s"Wrong content type; got ${req.header[`Content-Type`]}")
    if request.discardEntityMode != HttpRequestSender.DiscardEntityMode.OnFailure then
      throw new IllegalArgumentException(s"Unexpected discard mode: ${request.discardEntityMode}.")
    Done
  }

  /**
    * Validates the form parameters in the request body and returns a future
    * with the result.
    *
    * @param req       the request
    * @param expParams the expected parameters
    * @param ec        the execution context
    * @param system    the actor system
    * @return the validation result
    */
  private def validateFormParameters(req: HttpRequest, expParams: Map[String, String])
                                    (implicit ec: ExecutionContext, system: ActorSystem[_]): Future[Done] =
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    req.entity.dataBytes.runWith(sink)
      .map(bs => Query(bs.utf8String))
      .map { query =>
        if query.toMap != expParams then
          throw new IllegalArgumentException(s"Wrong parameters; got ${query.toMap}, want $expParams")
        Done
      }

  /**
    * Validates an HTTP request against given criteria.
    *
    * @param req       the request
    * @param expPath   the expected path
    * @param expParams the expected parameters
    * @param ec        the execution context
    * @param system    the actor system
    * @return the validation result
    */
  private def validateRequest(req: HttpRequestSender.SendRequest, expPath: String, expParams: Map[String, String])
                             (implicit ec: ExecutionContext, system: ActorSystem[_]): Future[Done] =
    for _ <- validateRequestProperties(req, expPath)
         res <- validateFormParameters(req.request, expParams)
         yield res

  /**
    * Generates a result for the given request with the content specified.
    *
    * @param req     the request
    * @param content the content of the response
    * @return the result object
    */
  private def createResponse(req: HttpRequestSender.SendRequest, content: String):
  Future[HttpRequestSender.Result] =
    val response = HttpResponse(entity = content)
    Future.successful(HttpRequestSender.SuccessResult(req, response))

  /**
    * Returns the behavior of an actor that simulates processing of an HTTP
    * request to an OAuth identity provider. The request is validated, and a
    * pre-configured response is returned.
    *
    * @param expPath   the expected path for the request
    * @param expParams the expected form parameters
    * @param response  the response to be returned
    * @return the behavior of this stub actor
    */
  private def httpStubActor(expPath: String, expParams: Map[String, String], response: Try[String]):
  Behavior[HttpRequestSender.HttpCommand] = Behaviors.receivePartial {
    case (ctx, req: HttpRequestSender.SendRequest) =>
      implicit val system: ActorSystem[Nothing] = ctx.system
      implicit val ec: ExecutionContextExecutor = system.executionContext
      (for _ <- validateRequest(req, expPath, expParams)
            respStr <- Future.fromTry(response)
            resp <- createResponse(req, respStr)
            yield resp) onComplete {
        case Success(result) => req.replyTo ! result
        case Failure(exception) =>
          req.replyTo ! HttpRequestSender.FailedResult(req, exception)
      }
      Behaviors.same
  }

/**
  * Test class for ''OAuthTokenRetrieverServiceImpl''.
  */
class OAuthTokenRetrieverServiceImplSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with AsyncTestHelper:

  import OAuthTokenRetrieverServiceImplSpec.*

  /**
    * Convenience function to create a stub actor with the given parameters.
    *
    * @param expParams the expected parameters in the request
    * @param response  the response to be returned
    * @return the stub HTTP actor
    */
  private def createStubActor(expParams: Map[String, String], response: Try[String]):
  ActorRef[HttpRequestSender.HttpCommand] =
    testKit.spawn(httpStubActor(TokenPath, expParams, response))

  "OAuthTokenRetrieverServiceImpl" should "generate a correct authorize URL" in {
    implicit val ec: ExecutionContextExecutor = system.executionContext
    val uri = futureResult(OAuthTokenRetrieverServiceImpl.authorizeUrl(TestConfig))

    uri.authority.host.toString() should be(Host)
    uri.path.toString() should be(AuthPath)
    val query = uri.query().toMap
    query("client_id") should be(TestConfig.oauthConfig.clientID)
    query("scope") should be(TestConfig.scope)
    query("redirect_uri") should be(TestConfig.oauthConfig.redirectUri)
    query("response_type") should be("code")
    query should not contain "state"
  }

  it should "generate an authorize URL with a state parameter" in {
    implicit val ec: ExecutionContextExecutor = system.executionContext
    val state = "my_state_parameter"
    val uri = futureResult(OAuthTokenRetrieverServiceImpl.authorizeUrl(TestConfig, Some(state)))

    uri.query().toMap("state") should be(state)
  }

  it should "handle an invalid URI when generating the authorize URL" in {
    implicit val ec: ExecutionContextExecutor = system.executionContext
    val config = TestConfig.copy(authorizationEndpoint = "?not a valid URI!")

    expectFailedFuture[IllegalUriException](OAuthTokenRetrieverServiceImpl.authorizeUrl(config))
  }

  it should "report an exception when fetching tokens" in {
    val exception = new IllegalStateException("no token")
    val httpActor = createStubActor(FetchTokenParams, Failure(exception))

    val ex = expectFailedFuture[Throwable](OAuthTokenRetrieverServiceImpl.fetchTokens(httpActor,
      TestConfig, Secret(ClientSecret), AuthCode))
    ex should be(exception)
  }

  it should "execute a successful authorization request" in {
    val httpActor = createStubActor(FetchTokenParams, Success(TokenResponse))

    futureResult(OAuthTokenRetrieverServiceImpl.fetchTokens(httpActor, TestConfig, Secret(ClientSecret),
      AuthCode)) should be(TestTokens)
  }

  it should "handle an unexpected response from the IDP" in {
    val respText = "a strange response?!"
    val httpActor = createStubActor(FetchTokenParams, Success(respText))

    val ex = expectFailedFuture[IOException](OAuthTokenRetrieverServiceImpl.fetchTokens(httpActor,
      TestConfig, Secret(ClientSecret), AuthCode))
    ex.getMessage should include(respText)
  }

  it should "execute a successful refresh token request" in {
    val httpActor = createStubActor(RefreshTokenParams, Success(TokenResponse))

    futureResult(OAuthTokenRetrieverServiceImpl.refreshToken(httpActor, TestConfig, Secret(ClientSecret),
      TestTokens.refreshToken)) should be(TestTokens)
  }
