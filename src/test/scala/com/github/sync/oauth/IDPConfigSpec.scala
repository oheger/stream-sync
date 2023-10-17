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

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{OAuthConfig, OAuthTokenData}
import com.github.sync.oauth.IDPConfigSpec.createIDPConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object IDPConfigSpec:
  private val ValidSecret = Secret("a valid secret")

  /** A test OAuth configuration with a valid secret. */
  private val TestOAuthConfig = OAuthConfig(tokenEndpoint = "tokenEndpoint", redirectUri = "redirectUri",
    clientID = "clientID", initTokenData = OAuthTokenData("access", "refresh"), clientSecret = ValidSecret)

  /**
    * Creates a test configuration instance with the given secret.
    *
    * @param secret the client secret
    * @return the configuration instance with this client secret
    */
  private def createIDPConfig(secret: Secret = ValidSecret): IDPConfig =
    val oauthConf = TestOAuthConfig.copy(clientSecret = secret)
    IDPConfig(oauthConf, "authorizationEndpoint", "scope")

/**
  * Test class for ''IDPConfig''.
  */
class IDPConfigSpec extends AnyFlatSpec with Matchers :
  "IDPConfig" should "detect a valid secret" in {
    val config = createIDPConfig()

    config.hasValidSecret shouldBe true
  }

  it should "detect a blank secret as invalid" in {
    val config = createIDPConfig(Secret(" "))

    config.hasValidSecret shouldBe false
  }

  it should "detect a secret with non-printable characters as invalid" in {
    val characters = Array[Char](60128, 60233, 19634, 60547, 55442, 63524, 'A', 62626)
    val strangeSecret = Secret(new String(characters))
    val config = createIDPConfig(strangeSecret)

    config.hasValidSecret shouldBe false
  }
