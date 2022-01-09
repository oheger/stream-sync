/*
 * Copyright 2018-2022 The Developers Team.
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

import com.github.cloudfiles.core.http.auth.OAuthConfig

/**
  * A data class collecting the properties required for an OAuth client
  * application to interact with an identity provider (IDP) server.
  *
  * An instance of this class stores the major part of the information required
  * for the implementation of an OAuth code flow against a specific OAuth2
  * identity provider.
  *
  * @param oauthConfig           the part of the OAuth configuration related to
  *                              access and refresh tokens
  * @param authorizationEndpoint the URI of the authorization endpoint
  * @param scope                 the scope value
  */
case class IDPConfig(oauthConfig: OAuthConfig,
                     authorizationEndpoint: String,
                     scope: String)
