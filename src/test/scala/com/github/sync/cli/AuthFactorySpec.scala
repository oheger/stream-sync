/*
 * Copyright 2018-2021 The Developers Team.
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

package com.github.sync.cli

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth._
import com.github.sync.AsyncTestHelper
import com.github.sync.http.oauth.{IDPConfig, OAuthStorageService}
import com.github.sync.http.{SyncBasicAuthConfig, SyncNoAuth, SyncOAuthStorageConfig}
import org.mockito.Matchers.any
import org.mockito.Mockito.{never, verify, verifyZeroInteractions, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.nio.file.Paths
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Test class for ''AuthFactory''.
  */
class AuthFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike with BeforeAndAfterAll
  with Matchers with MockitoSugar with AsyncTestHelper {
  def this() = this(ActorSystem("AuthFactorySpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  /**
    * Returns the execution context in implicit scope.
    *
    * @return the execution context
    */
  private implicit def executionContext: ExecutionContext = system.dispatcher

  /**
    * Convenience function to create a mock storage service.
    *
    * @return the mock storage service
    */
  private def createStorageService(): OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData] =
    mock[OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData]]

  "AuthFactory" should "convert a SyncNoAuth config" in {
    val storageService = createStorageService()
    val factory = new AuthFactory(storageService)

    futureResult(factory.createAuthConfig(SyncNoAuth)) should be(NoAuthConfig)
    verifyZeroInteractions(storageService)
  }

  it should "convert a SyncBasicAuth config" in {
    val storageService = createStorageService()
    val syncConfig = SyncBasicAuthConfig("test-user", Secret("theSecretPassword"))
    val factory = new AuthFactory(storageService)

    val authConfig = futureResult(factory.createAuthConfig(syncConfig))
    authConfig should be(BasicAuthConfig(syncConfig.user, syncConfig.password))
    verifyZeroInteractions(storageService)
  }

  /**
    * Creates a test IDP configuration.
    *
    * @return the test configuration
    */
  private def createIDPConfig(): IDPConfig = {
    val oauthConfig = OAuthConfig(tokenEndpoint = "someTokenEndpoint", clientID = "someClientID",
      redirectUri = "someRedirectURI", clientSecret = Secret("someSecret"),
      initTokenData = OAuthTokenData("someAccessToken", "someRefreshToken"))
    val idpConfig = IDPConfig(oauthConfig, "someAuthorizationEndpoint", "someScope")
    idpConfig
  }

  /**
    * Asserts the given factory result is an OAuth configuration.
    *
    * @param factoryResult the factory result
    * @return the extracted OAuth configuration
    */
  private def expectOAuthConfig(factoryResult: Future[AuthConfig]): OAuthConfig =
    futureResult(factoryResult) match {
      case authConfig: OAuthConfig => authConfig
      case c => fail("Unexpected result: " + c)
    }

  it should "convert a SyncOAuth config" in {
    val storageService = createStorageService()
    val storageConfig = SyncOAuthStorageConfig(Paths.get("/etc/oauth"), "my-idp", None)
    val idpConfig = createIDPConfig()
    when(storageService.loadIdpConfig(storageConfig)).thenReturn(Future.successful(idpConfig))
    val factory = new AuthFactory(storageService)

    val authConfig = expectOAuthConfig(factory.createAuthConfig(storageConfig))
    authConfig.copy(refreshNotificationFunc =
      idpConfig.oauthConfig.refreshNotificationFunc) should be(idpConfig.oauthConfig)
  }

  it should "provide an OAuth refresh notification func that saves updated tokens" in {
    val storageService = createStorageService()
    val storageConfig = SyncOAuthStorageConfig(Paths.get("/etc/oauth"), "my-idp", Some(Secret("crypt")))
    val idpConfig = createIDPConfig()
    when(storageService.loadIdpConfig(storageConfig)).thenReturn(Future.successful(idpConfig))
    val factory = new AuthFactory(storageService)

    val authConfig = expectOAuthConfig(factory.createAuthConfig(storageConfig))
    val newTokens = OAuthTokenData("refreshedAccessToken", "refreshToken")
    authConfig.refreshNotificationFunc(Success(newTokens))
    verify(storageService).saveTokens(storageConfig, newTokens)
  }

  it should "provide an OAuth refresh notification func that does not fail on errors" in {
    val storageService = createStorageService()
    val storageConfig = SyncOAuthStorageConfig(Paths.get("/etc/oauth"), "my-idp", Some(Secret("crypt")))
    val idpConfig = createIDPConfig()
    when(storageService.loadIdpConfig(storageConfig)).thenReturn(Future.successful(idpConfig))
    val factory = new AuthFactory(storageService)

    val authConfig = expectOAuthConfig(factory.createAuthConfig(storageConfig))
    authConfig.refreshNotificationFunc(Failure(new IOException("Test Exception: No tokens.")))
    verify(storageService, never()).saveTokens(any(), any())(any(), any())
  }
}
