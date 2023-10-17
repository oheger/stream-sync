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

import com.github.cloudfiles.core.http.auth.OAuthConfig
import com.github.sync.oauth.IDPConfig.hasMostlyNonPrintableChars

object IDPConfig:
  /** Regular expression to detect non-printable characters. */
  private val RegExNonPrintable = """\p{C}""".r

  /**
    * Returns a flag whether the given string has more non-printable than
    * printable characters.
    *
    * @param s the string to check
    * @return a flag whether the string has more non-printable characters
    */
  private def hasMostlyNonPrintableChars(s: String): Boolean =
    RegExNonPrintable.findAllIn(s).size > s.length / 2

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
                     scope: String):
  /**
    * Returns a flag whether the client secret used in this configuration seems
    * to be valid. Since the secret has typically been decrypted, this function
    * can be used to check whether the password used for decryption was
    * correct. In case of a wrong password, the secret should mostly consist of
    * special, non printable characters.
    *
    * @return a flag whether a valid client secret is provided
    */
  def hasValidSecret: Boolean =
    val clientSecret = oauthConfig.clientSecret.secret
    !clientSecret.isBlank && !hasMostlyNonPrintableChars(clientSecret)
