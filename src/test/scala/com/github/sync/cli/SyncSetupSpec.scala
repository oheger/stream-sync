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

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import akka.{actor => classic}
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth._
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, Spawner}
import com.github.sync.AsyncTestHelper
import com.github.sync.cli.SyncParameterManager.SyncConfig
import com.github.sync.http.oauth.{IDPConfig, OAuthStorageService}
import com.github.sync.http.{SyncBasicAuthConfig, SyncNoAuth, SyncOAuthStorageConfig}
import com.github.sync.protocol.config.{DavStructureConfig, FsStructureConfig, OneDriveStructureConfig}
import com.github.sync.protocol.local.LocalProtocolFactory
import com.github.sync.protocol.onedrive.OneDriveProtocolFactory
import com.github.sync.protocol.webdav.DavProtocolFactory
import org.mockito.Matchers.any
import org.mockito.Mockito.{never, verify, verifyZeroInteractions, when}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.nio.file.Paths
import java.time.ZoneId
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object SyncSetupSpec {
  /** Constant for a sync timeout. */
  private val SyncTimeout = Timeout(2.minutes)

  /** A test sync configuration. */
  private val TestSyncConfig = SyncConfig(srcUri = "someSrcUri", dstUri = "someDstUri", srcConfig = null,
    dstConfig = null, timeout = SyncTimeout, applyMode = SyncParameterManager.ApplyModeTarget("test"),
    logFilePath = None, syncLogPath = None, ignoreTimeDelta = None, cryptConfig = null, opsPerSecond = None,
    filterData = null, switched = false)

  /** A test configuration for HTTP actors. */
  private val TestSenderConfig = HttpRequestSenderConfig(actorName = Some("testActor"))
}

/**
  * Test class for ''SyncSetup''.
  */
class SyncSetupSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar
  with AsyncTestHelper {

  import SyncSetupSpec._

  /**
    * Returns the classic actor system in implicit scope. This is needed for
    * interactions with the storage service.
    *
    * @param system the typed actor system
    * @return the classic actor system
    */
  private implicit def classicActorSystem(implicit system: ActorSystem[_]): classic.ActorSystem =
    system.toClassic

  /**
    * Returns the execution context in implicit scope.
    *
    * @param system the actor system
    * @return the execution context
    */
  private implicit def executionContext(implicit system: ActorSystem[_]): ExecutionContext =
    system.executionContext

  /**
    * Convenience function to create a mock storage service.
    *
    * @return the mock storage service
    */
  private def createStorageService(): OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData] =
    mock[OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData]]

  "AuthFactory" should "convert a SyncNoAuth config" in {
    val storageService = createStorageService()
    val authFunc = SyncSetup.defaultAuthSetupFunc(storageService)

    futureResult(authFunc(SyncNoAuth)) should be(NoAuthConfig)
    verifyZeroInteractions(storageService)
  }

  it should "convert a SyncBasicAuth config" in {
    val storageService = createStorageService()
    val syncConfig = SyncBasicAuthConfig("test-user", Secret("theSecretPassword"))
    val authFunc = SyncSetup.defaultAuthSetupFunc(storageService)

    val authConfig = futureResult(authFunc(syncConfig))
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
    val authFunc = SyncSetup.defaultAuthSetupFunc(storageService)

    val authConfig = expectOAuthConfig(authFunc(storageConfig))
    authConfig.copy(refreshNotificationFunc =
      idpConfig.oauthConfig.refreshNotificationFunc) should be(idpConfig.oauthConfig)
  }

  it should "provide an OAuth refresh notification func that saves updated tokens" in {
    val storageService = createStorageService()
    val storageConfig = SyncOAuthStorageConfig(Paths.get("/etc/oauth"), "my-idp", Some(Secret("crypt")))
    val idpConfig = createIDPConfig()
    when(storageService.loadIdpConfig(storageConfig)).thenReturn(Future.successful(idpConfig))
    val authFunc = SyncSetup.defaultAuthSetupFunc(storageService)

    val authConfig = expectOAuthConfig(authFunc(storageConfig))
    val newTokens = OAuthTokenData("refreshedAccessToken", "refreshToken")
    authConfig.refreshNotificationFunc(Success(newTokens))
    verify(storageService).saveTokens(storageConfig, newTokens)
  }

  it should "provide an OAuth refresh notification func that does not fail on errors" in {
    val storageService = createStorageService()
    val storageConfig = SyncOAuthStorageConfig(Paths.get("/etc/oauth"), "my-idp", Some(Secret("crypt")))
    val idpConfig = createIDPConfig()
    when(storageService.loadIdpConfig(storageConfig)).thenReturn(Future.successful(idpConfig))
    val authFunc = SyncSetup.defaultAuthSetupFunc(storageService)

    val authConfig = expectOAuthConfig(authFunc(storageConfig))
    authConfig.refreshNotificationFunc(Failure(new IOException("Test Exception: No tokens.")))
    verify(storageService, never()).saveTokens(any(), any())(any(), any())
  }

  it should "provide a setup function that creates a local sync protocol" in {
    val structConfig = FsStructureConfig(Some(ZoneId.of("Z")))
    val spawner = mock[Spawner]

    SyncSetup.defaultProtocolFactorySetupFunc.apply(structConfig, TestSyncConfig, TestSenderConfig, spawner) match {
      case f: LocalProtocolFactory =>
        f.config should be(structConfig)
        f.timeout should be(SyncTimeout)
        f.httpSenderConfig should be(TestSenderConfig)
      case o => fail("Unexpected protocol factory: " + o)
    }
  }

  it should "provide a setup function that creates a WebDav sync protocol" in {
    val structConfig = DavStructureConfig(optLastModifiedProperty = Some("changed"),
      optLastModifiedNamespace = Some("my-ns"), deleteBeforeOverride = false)
    val spawner = mock[Spawner]

    SyncSetup.defaultProtocolFactorySetupFunc.apply(structConfig, TestSyncConfig, TestSenderConfig, spawner) match {
      case f: DavProtocolFactory =>
        f.config should be(structConfig)
        f.timeout should be(SyncTimeout)
        f.httpSenderConfig should be(TestSenderConfig)
      case o => fail("Unexpected protocol factory: " + o)
    }
  }

  it should "provide a setup function that creates a OneDrive sync protocol" in {
    val structConfig = OneDriveStructureConfig(syncPath = "/my/data", optUploadChunkSizeMB = None,
      optServerUri = None)
    val spawner = mock[Spawner]

    SyncSetup.defaultProtocolFactorySetupFunc.apply(structConfig, TestSyncConfig, TestSenderConfig, spawner) match {
      case f: OneDriveProtocolFactory =>
        f.config should be(structConfig)
        f.timeout should be(SyncTimeout)
        f.httpSenderConfig should be(TestSenderConfig)
      case o => fail("Unexpected protocol factory: " + o)
    }
  }
}
