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

package com.github.sync

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{OAuthConfig, OAuthTokenData}
import com.github.sync.oauth.{IDPConfig, OAuthStorageServiceImpl, SyncOAuthStorageConfig}
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, post, stubFor, urlPathEqualTo}
import org.scalatest.Suite

import scala.concurrent.ExecutionContext

object OAuthMockSupport:
  /** Path to the token endpoint of the simulated IDP. */
  val TokenEndpoint = "/token"

  /** Token material that is available initially. */
  val CurrentTokenData: OAuthTokenData = OAuthTokenData(accessToken = "accessOld", refreshToken = "make-it-fresh")

  /** Token material after a refresh. */
  val RefreshedTokenData: OAuthTokenData = OAuthTokenData(accessToken = "accessNew", refreshToken = "nextRefresh")

  /** The client secret for the test IDP. */
  val ClientSecret: Secret = Secret("secretOfMyTestIDP")

  /** The secret for encrypting the data of the test IDP. */
  val PwdIdpData: Secret = Secret("idpDataEncryption")

/**
  * A trait providing functionality related to OAuth and a mock IDP for
  * integration tests.
  *
  * Using this trait, interactions with HTTP servers can be tested that use
  * OAuth as authentication mechanism.
  */
trait OAuthMockSupport:
  this: Suite with AsyncTestHelper with FileTestHelper with WireMockSupport =>

  import OAuthMockSupport._

  protected implicit val ec: ExecutionContext
  protected implicit val system: ActorSystem

  /**
    * Creates an OAuth configuration that points to the mock server.
    *
    * @return the test OAuth configuration
    */
  protected def createOAuthConfig(): IDPConfig =
    val oauthConfig = OAuthConfig(tokenEndpoint = serverUri(TokenEndpoint), redirectUri = "https://redirect.org",
      clientID = "testClient", clientSecret = ClientSecret, initTokenData = CurrentTokenData)
    IDPConfig(authorizationEndpoint = "https://auth.org", scope = "test", oauthConfig = oauthConfig)


  /**
    * Creates an OAuth storage configuration that can be used in tests.
    *
    * @param optPassword optional password to encrypt IDP data
    * @return the storage configuration
    */
  protected def createOAuthStorageConfig(optPassword: Option[Secret] = Some(PwdIdpData)): SyncOAuthStorageConfig =
    SyncOAuthStorageConfig(rootDir = testDirectory, baseName = "testIdp",
      optPassword = optPassword)

  /**
    * Stores the data of an OAuth IDP, so that it can be referenced in tests.
    *
    * @param storageConfig the OAuth storage configuration
    * @param oauthConfig   the OAuth configuration
    */
  protected def saveIdpData(storageConfig: SyncOAuthStorageConfig, oauthConfig: IDPConfig): Unit =
    futureResult(OAuthStorageServiceImpl.saveIdpConfig(storageConfig, oauthConfig))

  /**
    * Creates the objects required to access the test IDP and stores them, so
    * that they can be accessed via the storage configuration returned.
    *
    * @param optPassword optional password to encrypt IDP data
    * @return the OAuth storage configuration
    */
  protected def prepareIdpConfig(optPassword: Option[Secret] = Some(PwdIdpData)): SyncOAuthStorageConfig =
    val storageConfig = createOAuthStorageConfig(optPassword)
    val oauthConfig = createOAuthConfig()
    saveIdpData(storageConfig, oauthConfig)
    storageConfig

  /**
    * Stubs a request to refresh an access token. The request is answered with
    * the refreshed token pair.
    */
  protected def stubTokenRefresh(): Unit =
    stubFor(post(urlPathEqualTo(TokenEndpoint))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withHeader(`Content-Type`.name, ContentTypes.`application/json`.value)
        .withBodyFile("token_response.json")))

  /**
    * Generates an array with the given options plus options related to OAuth
    * based on the given configuration.
    *
    * @param storageConfig the OAuth storage config
    * @param prefix        the prefix for the OAuth options (--src- or --dst-)
    * @param options       additional options
    * @return the resulting array with options
    */
  protected def withOAuthOptions(storageConfig: SyncOAuthStorageConfig, prefix: String, options: String*):
  IndexedSeq[String] =
    val pwdOptions = storageConfig.optPassword match
      case Some(pwd) => IndexedSeq(prefix + "idp-password", pwd.secret)
      case None => IndexedSeq(prefix + "store-unencrypted")
    options.toIndexedSeq ++ pwdOptions ++ IndexedSeq(prefix + "idp-name", storageConfig.baseName,
      prefix + "idp-storage-path", testDirectory.toAbsolutePath.toString)
