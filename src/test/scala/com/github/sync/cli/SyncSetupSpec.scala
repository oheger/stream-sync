/*
 * Copyright 2018-2026 The Developers Team.
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

import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.*
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, Spawner}
import com.github.sync.auth.oauth.*
import com.github.sync.cli.SyncCliStreamConfig.{MirrorStreamConfig, StreamConfig}
import com.github.sync.cli.SyncParameterManager.{LogConfig, SyncConfig}
import com.github.sync.protocol.config.{DavStructureConfig, FsStructureConfig, GoogleDriveStructureConfig, OneDriveStructureConfig}
import com.github.sync.protocol.gdrive.GoogleDriveProtocolFactory
import com.github.sync.protocol.local.LocalProtocolFactory
import com.github.sync.protocol.onedrive.OneDriveProtocolFactory
import com.github.sync.protocol.webdav.DavProtocolFactory
import com.github.sync.stream.Throttle
import org.apache.logging.log4j.Level
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.KillSwitch
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, verifyNoInteractions, when}
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.nio.file.Paths
import java.time.ZoneId
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object SyncSetupSpec:
  /** Constant for a sync timeout. */
  private val SyncTimeout = Timeout(2.minutes)

  /** A test sync configuration. */
  private val TestSyncConfig = SyncConfig(
    srcUri = "someSrcUri",
    dstUri = "someDstUri",
    srcConfig = null,
    dstConfig = null,
    logConfig = LogConfig(None, None, Level.DEBUG),
    cryptConfig = null,
    streamConfig = StreamConfig(
      dryRun = false,
      timeout = SyncTimeout,
      ignoreTimeDelta = None,
      opsPerUnit = None,
      throttleUnit = Throttle.TimeUnit.Second,
      modeConfig = MirrorStreamConfig(None, switched = false),
      credentialsConfig = None
    ),
    filterData = null
  )

  /** A test configuration for HTTP actors. */
  private val TestSenderConfig = HttpRequestSenderConfig(actorName = Some("testActor"))

  /**
    * Returns a function to resolve credentials that obtains its data from the
    * passed in map. If the passed in credential is found in the map, the
    * associated value is returned; otherwise, result is the credential without
    * any changes.
    *
    * @param credentials the map with credentials
    * @return the test credentials resolver function
    */
  private def credentialsResolverFunc(credentials: Map[Secret, Secret] = Map.empty):
  CredentialsResolver.SecretResolverFunc =
    credential =>
      val resolved = credentials.getOrElse(credential, credential)
      Future.successful(resolved)
end SyncSetupSpec

/**
  * Test class for ''SyncSetup''.
  */
class SyncSetupSpec extends ScalaTestWithActorTestKit with AsyncFlatSpecLike with Matchers with MockitoSugar:

  import SyncSetupSpec.*

  /**
    * Returns the classic actor system in implicit scope. This is needed for
    * interactions with the storage service.
    *
    * @param system the typed actor system
    */
  private given classicActorSystem(using system: ActorSystem[?]): classic.ActorSystem = system.toClassic

  /**
    * Returns the execution context in implicit scope.
    *
    * @param system the actor system
    */
  private given executionContext(using system: ActorSystem[?]): ExecutionContext = system.executionContext

  /**
    * Convenience function to create a mock storage service.
    *
    * @return the mock storage service
    */
  private def createStorageService(): OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData] =
    mock[OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData]]

  "SyncSetup" should "convert a SyncNoAuth config" in :
    val storageService = createStorageService()
    val authFunc = SyncSetup.defaultAuthSetupFunc(credentialsResolverFunc(), storageService)

    authFunc(SyncNoAuth, mock[KillSwitch]) map: authConfig =>
      verifyNoInteractions(storageService)
      authConfig should be(NoAuthConfig)

  it should "convert a SyncBasicAuth config" in :
    val storageService = createStorageService()
    val syncConfig = SyncBasicAuthConfig("test-user", Secret("theSecretPassword"))
    val authFunc = SyncSetup.defaultAuthSetupFunc(credentialsResolverFunc(), storageService)

    authFunc(syncConfig, mock[KillSwitch]) map: authConfig =>
      verifyNoInteractions(storageService)
      authConfig should be(BasicAuthConfig(syncConfig.user, syncConfig.password))

  it should "resolve the password credential for a SyncBasicAuth config" in :
    val PasswordCredential = Secret("mySpecialPassword")
    val PasswordSecret = Secret("YouCan'tGuessIt!")
    val credentials = Map(PasswordCredential -> PasswordSecret)
    val storageService = createStorageService()
    val syncConfig = SyncBasicAuthConfig("test-user", PasswordCredential)

    val authFunc = SyncSetup.defaultAuthSetupFunc(credentialsResolverFunc(credentials), storageService)
    authFunc(syncConfig, mock[KillSwitch]) map: authConfig =>
      verifyNoInteractions(storageService)
      authConfig should be(BasicAuthConfig(syncConfig.user, PasswordSecret))

  /**
    * Creates a test IDP configuration.
    *
    * @return the test configuration
    */
  private def createIDPConfig(): IDPConfig =
    val oauthConfig = OAuthConfig(
      tokenEndpoint = "someTokenEndpoint",
      clientID = "someClientID",
      redirectUri = "someRedirectURI",
      clientSecret = Secret("someSecret"),
      initTokenData = OAuthTokenData("someAccessToken", "someRefreshToken")
    )
    val idpConfig = IDPConfig(oauthConfig, "someAuthorizationEndpoint", "someScope")
    idpConfig

  /**
    * Asserts the given factory result is an OAuth configuration.
    *
    * @param factoryResult the factory result
    * @return the extracted OAuth configuration
    */
  private def expectOAuthConfig(factoryResult: Future[AuthConfig]): Future[OAuthConfig] =
    factoryResult map:
      case authConfig: OAuthConfig => authConfig
      case c => fail("Unexpected result: " + c)

  it should "convert a SyncOAuth config" in :
    val storageService = createStorageService()
    val storageConfig = SyncOAuthStorageConfig(Paths.get("/etc/oauth"), "my-idp", None)
    val idpConfig = createIDPConfig()
    when(storageService.loadIdpConfig(storageConfig)).thenReturn(Future.successful(idpConfig))
    val authFunc = SyncSetup.defaultAuthSetupFunc(credentialsResolverFunc(), storageService)

    expectOAuthConfig(authFunc(storageConfig, mock[KillSwitch])) map: authConfig =>
      authConfig.copy(refreshNotificationFunc =
        idpConfig.oauthConfig.refreshNotificationFunc) should be(idpConfig.oauthConfig)

  it should "provide an OAuth refresh notification func that saves updated tokens" in :
    val idpSecret = Secret("crypt")
    val credentialsMap = Map(idpSecret -> idpSecret)
    val storageService = createStorageService()
    val storageConfig = SyncOAuthStorageConfig(Paths.get("/etc/oauth"), "my-idp", Some(idpSecret))
    val idpConfig = createIDPConfig()
    val killSwitch = mock[KillSwitch]
    when(storageService.loadIdpConfig(storageConfig)).thenReturn(Future.successful(idpConfig))
    val authFunc = SyncSetup.defaultAuthSetupFunc(credentialsResolverFunc(credentialsMap), storageService)

    expectOAuthConfig(authFunc(storageConfig, killSwitch)) map: authConfig =>
      val newTokens = OAuthTokenData("refreshedAccessToken", "refreshToken")
      authConfig.refreshNotificationFunc(Success(newTokens))
      verify(storageService).saveTokens(storageConfig, newTokens)
      verifyNoInteractions(killSwitch)
      succeed

  it should "provide an OAuth refresh notification func that triggers the kill switch on errors" in :
    val storageService = createStorageService()
    val storageConfig = SyncOAuthStorageConfig(Paths.get("/etc/oauth"), "my-idp", None)
    val idpConfig = createIDPConfig()
    val killSwitch = mock[KillSwitch]
    when(storageService.loadIdpConfig(storageConfig)).thenReturn(Future.successful(idpConfig))
    val exception = new IOException("Test Exception: No tokens.")
    val authFunc = SyncSetup.defaultAuthSetupFunc(credentialsResolverFunc(), storageService)

    expectOAuthConfig(authFunc(storageConfig, killSwitch)) map: authConfig =>
      authConfig.refreshNotificationFunc(Failure(exception))
      verify(storageService, never()).saveTokens(any(), any())(using any(), any())
      verify(killSwitch).abort(exception)
      succeed

  it should "resolve the secret of the OAuth storage config" in :
    val idpSecret = Secret("crypt")
    val resolvedIdpSecret = Secret("crypt_resolved")
    val credentialsMap = Map(idpSecret -> resolvedIdpSecret)
    val storageService = createStorageService()
    val storageConfig = SyncOAuthStorageConfig(Paths.get("/etc/oauth"), "my-idp", Some(idpSecret))
    val resolvedStorageConfig = storageConfig.copy(optPassword = Some(resolvedIdpSecret))
    val idpConfig = createIDPConfig()
    val killSwitch = mock[KillSwitch]
    when(storageService.loadIdpConfig(resolvedStorageConfig)).thenReturn(Future.successful(idpConfig))
    val authFunc = SyncSetup.defaultAuthSetupFunc(credentialsResolverFunc(credentialsMap), storageService)

    expectOAuthConfig(authFunc(storageConfig, killSwitch)) map: authConfig =>
      val newTokens = OAuthTokenData("refreshedAccessToken", "refreshToken")
      authConfig.refreshNotificationFunc(Success(newTokens))
      verify(storageService).saveTokens(resolvedStorageConfig, newTokens)
      verifyNoInteractions(killSwitch)
      succeed

  it should "provide a setup function that creates a local sync protocol" in :
    val structConfig = FsStructureConfig(Some(ZoneId.of("Z")))
    val spawner = mock[Spawner]

    SyncSetup.defaultProtocolFactorySetupFunc.apply(structConfig, TestSyncConfig, TestSenderConfig, spawner) match
      case f: LocalProtocolFactory =>
        f.config should be(structConfig)
        f.timeout should be(SyncTimeout)
        f.httpSenderConfig should be(TestSenderConfig)
      case o => fail("Unexpected protocol factory: " + o)

  it should "provide a setup function that creates a WebDav sync protocol" in :
    val structConfig = DavStructureConfig(optLastModifiedProperty = Some("changed"),
      optLastModifiedNamespace = Some("my-ns"), deleteBeforeOverride = false)
    val spawner = mock[Spawner]

    SyncSetup.defaultProtocolFactorySetupFunc.apply(structConfig, TestSyncConfig, TestSenderConfig, spawner) match
      case f: DavProtocolFactory =>
        f.config should be(structConfig)
        f.timeout should be(SyncTimeout)
        f.httpSenderConfig should be(TestSenderConfig)
      case o => fail("Unexpected protocol factory: " + o)

  it should "provide a setup function that creates a OneDrive sync protocol" in :
    val structConfig = OneDriveStructureConfig(syncPath = "/my/data", optUploadChunkSizeMB = None,
      optServerUri = None)
    val spawner = mock[Spawner]

    SyncSetup.defaultProtocolFactorySetupFunc.apply(structConfig, TestSyncConfig, TestSenderConfig, spawner) match
      case f: OneDriveProtocolFactory =>
        f.config should be(structConfig)
        f.timeout should be(SyncTimeout)
        f.httpSenderConfig should be(TestSenderConfig)
      case o => fail("Unexpected protocol factory: " + o)

  it should "provide a setup function that creates a GoogleDrive sync protocol" in :
    val structConfig = GoogleDriveStructureConfig(optServerUri = Some("https://google-drive.example.org"))
    val spawner = mock[Spawner]

    SyncSetup.defaultProtocolFactorySetupFunc.apply(structConfig, TestSyncConfig, TestSenderConfig, spawner) match
      case f: GoogleDriveProtocolFactory =>
        f.config should be(structConfig)
        f.timeout should be(SyncTimeout)
        f.httpSenderConfig should be(TestSenderConfig)
      case o => fail("Unexpected protocol factory: " + o)
