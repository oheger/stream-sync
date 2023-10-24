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

import com.github.cloudfiles.core.http.HttpRequestSender
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.model.Uri

import scala.concurrent.Future

/**
  * A trait defining a service that is responsible for retrieving token-related
  * data from an OAuth identity provider.
  *
  * The service provides functions to handle the different steps of typical
  * OAuth flows.
  *
  * @tparam CONFIG        the type representing the OAuth configuration
  * @tparam CLIENT_SECRET the type representing the client secret
  * @tparam TOKENS        the type representing token data
  */
trait OAuthTokenRetrieverService[CONFIG, CLIENT_SECRET, TOKENS]:
  /**
    * Generates the URI for an authorization request based on the given OAuth
    * configuration. Optionally, a state parameter can be provided, which is
    * added to the URL.
    *
    * @param config the OAuth configuration
    * @param optState an optional state to be added to the URL               
    * @param system the actor system
    * @return a ''Future'' with the authorization URI
    */
  def authorizeUrl(config: CONFIG, optState: Option[String] = None)(implicit system: ActorSystem[_]): Future[Uri]

  /**
    * Sends a request to the token endpoint of the referenced IDP to exchange
    * an authorization code against a token pair.
    *
    * @param httpActor the actor for sending HTTP requests
    * @param config    the OAuth configuration
    * @param secret    the client secret
    * @param code      the authorization code
    * @param system    the actor system
    * @return a ''Future'' with the tokens retrieved from the IDP
    */
  def fetchTokens(httpActor: ActorRef[HttpRequestSender.HttpCommand], config: CONFIG, secret: CLIENT_SECRET,
                  code: String)(implicit system: ActorSystem[_]): Future[TOKENS]

  /**
    * Sends a request to the token endpoint of the referenced IDP to obtain
    * another access token for the given refresh token.
    *
    * @param httpActor    the actor for sending HTTP requests
    * @param config       the OAuth configuration
    * @param secret       the client secret
    * @param refreshToken the refresh token
    * @param system       the actor system
    * @return a ''Future'' with the tokens retrieved from the IDP
    */
  def refreshToken(httpActor: ActorRef[HttpRequestSender.HttpCommand], config: CONFIG, secret: CLIENT_SECRET,
                   refreshToken: String)(implicit system: ActorSystem[_]): Future[TOKENS]
