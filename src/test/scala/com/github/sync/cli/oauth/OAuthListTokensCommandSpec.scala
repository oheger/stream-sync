/*
 * Copyright 2018-2025 The Developers Team.
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

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{OAuthConfig, OAuthTokenData}
import com.github.sync.AsyncTestHelper
import com.github.sync.oauth.{IDPConfig, OAuthStorageService, SyncOAuthStorageConfig}
import org.apache.pekko.actor.ActorSystem
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

object OAuthListTokensCommandSpec:
  /** A name for the test IDP. */
  private val IdpName = "myTestIDP"

  /** A test storage configuration. */
  private val StorageConfig = SyncOAuthStorageConfig(baseName = IdpName, rootDir = Paths.get("/foo"),
    optPassword = None)

  /** The access token used by the IDP. */
  private val AccessToken = "<some_access_token>"

  /** The refresh token used by the IDP. */
  private val RefreshToken = "<some_refresh_token>"

  /** The configuration for the list tokens command. */
  private val ListTokensConfig = OAuthParameterManager.ListTokensCommandConfig(StorageConfig)
end OAuthListTokensCommandSpec

/**
  * Test class for the functionality to list the tokens of an IDP.
  */
class OAuthListTokensCommandSpec extends AnyFlatSpec with Matchers with MockitoSugar with AsyncTestHelper:

  import OAuthListTokensCommandSpec.*

  "List tokens command" should "list the tokens of an IDP" in :

    given ActorSystem = mock[ActorSystem]

    val storageService = mock[OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData]]
    val oauthConfig = OAuthConfig(
      tokenEndpoint = "someTokenEndpoint",
      redirectUri = "someRedirectUri",
      clientID = "someClientID",
      clientSecret = Secret("someClientSecret"),
      initTokenData = OAuthTokenData(AccessToken, RefreshToken)
    )
    val idpConfig = IDPConfig(oauthConfig, "authEndpoint", "scope")
    when(storageService.loadIdpConfig(StorageConfig)).thenReturn(Future.successful(idpConfig))

    val result = futureResult(OAuthCommandsImpl.listTokens(ListTokensConfig, storageService))
    result should include(IdpName)
    result should include(AccessToken)
    result should include(RefreshToken)
