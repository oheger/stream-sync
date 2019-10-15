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

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.github.sync.crypt.Secret
import com.github.sync.webdav.HttpExtensionActor
import com.github.sync.webdav.HttpRequestActor.{FailedResponseException, RequestException, Result, SendRequest}
import com.github.sync.webdav.oauth.OAuthTokenActor.{DoRefresh, PendingRequestData, TokensRefreshed}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object OAuthTokenActor {

  /**
    * An internally used data class that stores information about a request
    * that cannot be processed directly because the access token has to be
    * refreshed first.
    *
    * @param caller  the calling actor
    * @param request the request
    */
  private case class PendingRequestData(caller: ActorRef, request: SendRequest)

  /**
    * An internal message to trigger a token refresh operation.
    *
    * @param pendingRequest information about the current request
    * @param failedRequest  the request that failed
    */
  private case class DoRefresh(pendingRequest: PendingRequestData, failedRequest: SendRequest)

  /**
    * An internal message class used to report a successful token refresh
    * operation. The new ''OAuthTokenData'' object becomes the current one.
    *
    * @param tokenData the refreshed token material
    */
  private case class TokensRefreshed(tokenData: OAuthTokenData)

  /**
    * Creates a ''Props'' object for creating a new actor instance based on the
    * parameters specified.
    *
    * @param httpActor      the actor to forward HTTP requests to
    * @param clientCount    the initial number of clients
    * @param idpHttpActor   the actor for HTTP requests to the IDP
    * @param storageConfig  the storage configuration for the IDP
    * @param oauthConfig    the OAuth configuration
    * @param clientSecret   the client secret for the IDP
    * @param initTokenData  the initial token pair
    * @param storageService the storage service for OAuth data
    * @param tokenService   the token retriever service
    * @return the ''Props'' for creating a new actor instance
    */
  def apply(httpActor: ActorRef, clientCount: Int, idpHttpActor: ActorRef, storageConfig: OAuthStorageConfig,
            oauthConfig: OAuthConfig, clientSecret: Secret, initTokenData: OAuthTokenData,
            storageService: OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData],
            tokenService: OAuthTokenRetrieverService[OAuthConfig, Secret, OAuthTokenData]): Props =
    Props(classOf[OAuthTokenActor], httpActor, clientCount, idpHttpActor, storageConfig, oauthConfig,
      clientSecret, initTokenData, storageService, tokenService)
}

/**
  * An actor class that acts a bearer token obtained from an OAuth identity
  * provider to HTTP requests.
  *
  * The actor is configured with the parameters of an OAuth identity provider.
  * When a request arrives, an authorization header with the current access
  * token is added before the request is forwarded to the actual HTTP request
  * actor. If the response has status code 401 (indicating that the access
  * token is no longer valid), a request to refresh the token is sent to the
  * IDP. The access token is then updated (and also stored for later reuse).
  *
  * @param httpActor      the actor to forward HTTP requests to
  * @param clientCount    the initial number of clients
  * @param idpHttpActor   the actor for HTTP requests to the IDP
  * @param storageConfig  the storage configuration for the IDP
  * @param oauthConfig    the OAuth configuration
  * @param clientSecret   the client secret for the IDP
  * @param initTokenData  the initial token pair
  * @param storageService the storage service for OAuth data
  * @param tokenService   the token retriever service
  */
class OAuthTokenActor(override val httpActor: ActorRef,
                      override val clientCount: Int,
                      idpHttpActor: ActorRef,
                      storageConfig: OAuthStorageConfig,
                      oauthConfig: OAuthConfig,
                      clientSecret: Secret,
                      initTokenData: OAuthTokenData,
                      storageService: OAuthStorageService[OAuthStorageConfig, OAuthConfig,
                        Secret, OAuthTokenData],
                      tokenService: OAuthTokenRetrieverService[OAuthConfig, Secret, OAuthTokenData])
  extends Actor with ActorLogging with HttpExtensionActor {
  /** Execution context in implicit scope. */
  private implicit val ec: ExecutionContext = context.dispatcher

  /** The object to materialize streams. */
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  /**
    * A timeout for the ask patter. Note that here a huge value is used;
    * timeouts are actually handled by the caller of this actor.
    */
  private implicit val timeout: Timeout = Timeout(1.day)

  /**
    * The currently valid token data. This data is updated when another access
    * token has to be retrieved.
    */
  private var currentTokenData = initTokenData

  /** Stores pending requests during a refresh operation. */
  private var pendingRequests = List.empty[PendingRequestData]

  override protected def customReceive: Receive = {
    case req: SendRequest =>
      val caller = sender()
      (httpActor ? addAuthorization(req)).mapTo[Result] onComplete {
        case Success(result) =>
          caller ! result

        case Failure(RequestException(_, FailedResponseException(response), failedReq))
          if response.status == StatusCodes.Unauthorized =>
          self ! DoRefresh(PendingRequestData(caller, req), failedReq)

        case Failure(exception) =>
          caller ! Status.Failure(exception)
      }

    case DoRefresh(pendingRequest, failedRequest) =>
      if (usesCurrentToken(failedRequest)) {
        pendingRequests = pendingRequest :: Nil
        refreshTokens()
      } else self.tell(pendingRequest.request, pendingRequest.caller)
  }

  /**
    * A receive function that is active while a refresh operation for the
    * current access token is in progress.
    *
    * @return the receive function during a refresh operation
    */
  private def refreshing: Receive = {
    case req: SendRequest =>
      pendingRequests = PendingRequestData(sender(), req) :: pendingRequests
      log.info("Queuing request until token refresh is complete.")

    case TokensRefreshed(tokenData) =>
      log.info("Got updated token data.")
      currentTokenData = tokenData
      become(customReceive)
      pendingRequests foreach { pr =>
        self.tell(pr.request, pr.caller)
      }
      pendingRequests = Nil

    case DoRefresh(pendingRequest, _) =>
      // a refresh is already in progress
      pendingRequests = pendingRequest :: pendingRequests
  }

  /**
    * Adds an ''Authorization'' header with the currently valid access token to
    * the given request.
    *
    * @param request the request
    * @return the updated request
    */
  private def addAuthorization(request: SendRequest): SendRequest = {
    val auth = Authorization(OAuth2BearerToken(currentTokenData.accessToken))
    val httpReq = request.request.copy(headers = auth :: request.request.headers.toList)
    request.copy(request = httpReq)
  }

  /**
    * Sends a request to the IDP to refresh the access token. When this is
    * successful, the current tokens are replaced, and all pending requests are
    * processed.
    */
  private def refreshTokens(): Unit = {
    become(refreshing)
    log.info("Obtaining a new access token.")
    //TODO handle failed refresh operation
    tokenService.refreshToken(idpHttpActor, oauthConfig, clientSecret, currentTokenData.refreshToken)
      .foreach { tokenData =>
        self ! TokensRefreshed(tokenData)
      }
  }

  /**
    * Checks whether the given request has the current access token set in its
    * ''Authorization'' header. If this is the case, and the request failed
    * with status 401, this means that the access token has probably expired
    * and must be refreshed. If, however, the access token has been changed
    * since the request was sent, the new one should be valid. In this case,
    * the request should just be retried with the new token.
    *
    * @param request the request to be checked
    * @return '''true''' if the request uses the current access token;
    *         '''false''' otherwise
    */
  private def usesCurrentToken(request: SendRequest): Boolean =
    request.request.header[Authorization].exists(_.credentials.token() == currentTokenData.accessToken)
}
