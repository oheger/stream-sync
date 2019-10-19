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

/**
  * A data class collecting the properties required for an OAuth client
  * application.
  *
  * An instance of this class stores the major part of the information required
  * for the implementation of an OAuth code flow against a specific OAuth2
  * identity provider.
  *
  * @param authorizationEndpoint the URI of the authorization endpoint
  * @param tokenEndpoint         the URI of the token endpoint
  * @param scope                 the scope value
  * @param redirectUri           the redirect URI
  * @param clientID              the client ID
  */
case class OAuthConfig(authorizationEndpoint: String,
                       tokenEndpoint: String,
                       scope: String,
                       redirectUri: String,
                       clientID: String)

/**
  * A data class representing the token material to be stored for a single
  * OAuth identity provider.
  *
  * @param accessToken  the access token
  * @param refreshToken the refresh token
  */
case class OAuthTokenData(accessToken: String, refreshToken: String)
