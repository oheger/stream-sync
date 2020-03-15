/*
 * Copyright 2018-2020 The Developers Team.
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

import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.stream.ActorMaterializer
import com.github.sync.crypt.Secret
import com.github.sync.http.OAuthStorageConfig
import com.github.sync.http.oauth.{OAuthConfig, OAuthStorageServiceImpl, OAuthTokenData}
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, post, stubFor, urlPathEqualTo}

import scala.concurrent.ExecutionContext

object OAuthMockSupport {
  /** Path to the token endpoint of the simulated IDP. */
  val TokenEndpoint = "/token"

  /** Token material that is available initially. */
  val CurrentTokenData = OAuthTokenData(accessToken = "accessOld", refreshToken = "make-it-fresh")

  /** Token material after a refresh. */
  val RefreshedTokenData = OAuthTokenData(accessToken = "accessNew", refreshToken = "nextRefresh")

  /** The client secret for the test IDP. */
  val ClientSecret = Secret("secretOfMyTestIDP")

  /** The secret for encrypting the data of the test IDP. */
  val PwdIdpData = Secret("idpDataEncryption")
}

/**
  * A trait providing functionality related to OAuth and a mock IDP for
  * integration tests.
  *
  * Using this trait, interactions with HTTP servers can be tested that use
  * OAuth as authentication mechanism.
  */
trait OAuthMockSupport {
  this: AsyncTestHelper with FileTestHelper with WireMockSupport =>

  import OAuthMockSupport._

  protected implicit val ec: ExecutionContext
  protected implicit val mat: ActorMaterializer

  /**
    * Creates an OAuth configuration that points to the mock server.
    *
    * @return the test OAuth configuration
    */
  protected def createOAuthConfig(): OAuthConfig =
    OAuthConfig(authorizationEndpoint = "https://auth.org", tokenEndpoint = serverUri(TokenEndpoint),
      scope = "test", redirectUri = "https://redirect.org", clientID = "testClient")

  /**
    * Creates an OAuth storage configuration that can be used in tests.
    *
    * @param optPassword optional password to encrypt IDP data
    * @return the storage configuration
    */
  protected def createOAuthStorageConfig(optPassword: Option[Secret] = Some(PwdIdpData)): OAuthStorageConfig =
    OAuthStorageConfig(rootDir = testDirectory, baseName = "testIdp",
      optPassword = optPassword)

  /**
    * Stores the data of an OAuth IDP, so that it can be referenced in tests.
    *
    * @param storageConfig the OAuth storage configuration
    * @param oauthConfig   the OAuth configuration
    * @param secret        the client secret
    * @param tokens        the current token pair
    */
  protected def saveIdpData(storageConfig: OAuthStorageConfig, oauthConfig: OAuthConfig, secret: Secret,
                            tokens: OAuthTokenData): Unit = {
    futureResult(for {
      _ <- OAuthStorageServiceImpl.saveConfig(storageConfig, oauthConfig)
      _ <- OAuthStorageServiceImpl.saveClientSecret(storageConfig, secret)
      done <- OAuthStorageServiceImpl.saveTokens(storageConfig, tokens)
    } yield done)
  }

  /**
    * Creates the objects required to access the test IDP and stores them, so
    * that they can be accessed via the storage configuration returned.
    *
    * @param optPassword optional password to encrypt IDP data
    * @return the OAuth storage configuration
    */
  protected def prepareIdpConfig(optPassword: Option[Secret] = Some(PwdIdpData)): OAuthStorageConfig = {
    val storageConfig = createOAuthStorageConfig(optPassword)
    val oauthConfig = createOAuthConfig()
    saveIdpData(storageConfig, oauthConfig, ClientSecret, CurrentTokenData)
    storageConfig
  }

  /**
    * Stubs a request to refresh an access token. The request is answered with
    * the refreshed token pair.
    */
  protected def stubTokenRefresh(): Unit = {
    stubFor(post(urlPathEqualTo(TokenEndpoint))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withHeader(`Content-Type`.name, ContentTypes.`application/json`.value)
        .withBodyFile("token_response.json")))
  }

  /**
    * Generates an array with the given options plus options related to OAuth
    * based on the given configuration.
    *
    * @param storageConfig the OAuth storage config
    * @param prefix        the prefix for the OAuth options (--src- or --dst-)
    * @param options       additional options
    * @return the resulting array with options
    */
  protected def withOAuthOptions(storageConfig: OAuthStorageConfig, prefix: String, options: String*):
  Array[String] = {
    val (pwdOpt, value) = storageConfig.optPassword match {
      case Some(pwd) => ("idp-password", pwd.secret)
      case None => ("encrypt-idp-data", "false")
    }
    options.toArray ++ Array(prefix + pwdOpt, value, prefix + "idp-name", storageConfig.baseName,
      prefix + "idp-storage-path", testDirectory.toAbsolutePath.toString)
  }
}
