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

package com.github.sync.cli.oauth

import akka.Done
import akka.actor.ActorSystem
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.{OAuthConfig, OAuthTokenData}
import com.github.sync.AsyncTestHelper
import com.github.sync.cli.oauth.OAuthParameterManager.InitCommandConfig
import com.github.sync.oauth.{IDPConfig, OAuthStorageService, SyncOAuthStorageConfig}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => eqArg}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.nio.file.Paths
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

object OAuthInitCommandSpec:
  /** A name for the test IDP. */
  private val IdpName = "myTestIDP"

  /** A test storage configuration. */
  private val StorageConfig = SyncOAuthStorageConfig(baseName = IdpName, rootDir = Paths.get("/foo"),
    optPassword = None)

  /** The content of the client secret. */
  private val ClientSecret = "<very secret client>"

  /** Test configuration for an IDP. */
  private val TestConfig = IDPConfig(authorizationEndpoint = "authEndpoint", scope = "scope",
    oauthConfig = OAuthConfig(tokenEndpoint = "tokenEndpoint", redirectUri = "redirect", clientID = "clientID",
      clientSecret = Secret(ClientSecret), initTokenData = OAuthTokenData("", "")))

  /** The default configuration for the init command. */
  private val InitConfig = InitCommandConfig(oauthConfig = TestConfig, storageConfig = StorageConfig)

/**
  * Test class for the functionality to initialize an IDP.
  */
class OAuthInitCommandSpec extends AnyFlatSpec with Matchers with MockitoSugar with AsyncTestHelper:

  import OAuthInitCommandSpec._

  "OAuthCommands" should "initialize a new IDP" in {
    val helper = new CommandTestHelper

    val result = futureResult(helper.prepareStorageService()
      .runCommand())
    result should include(IdpName)
    result should include("successfully initialized")
    helper.verifyStorageService()
  }

  it should "report an exception when storing the configuration" in {
    val exception = new IOException("Cannot store config")
    val helper = new CommandTestHelper

    val ex = expectFailedFuture[IOException](helper.prepareStorageService(saveConfigResult = Future.failed(exception))
      .runCommand())
    ex should be(exception)
  }

  /**
    * A test helper class managing dependencies of the execution.
    */
  private class CommandTestHelper:
    /** Implicit actor system required for command execution. */
    private implicit val actorSystem: ActorSystem = mock[ActorSystem]

    /** Mock for the storage service. */
    private val storageService = mock[OAuthStorageService[SyncOAuthStorageConfig, IDPConfig,
      Secret, OAuthTokenData]]

    /**
      * Prepares the mock for the storage service to expect invocations for
      * saving data related to a new IDP. The results can be specified.
      *
      * @param saveConfigResult result for saving the config
      * @return this test helper
      */
    def prepareStorageService(saveConfigResult: Future[Done] = Future.successful(Done)): CommandTestHelper =
      when(storageService.saveIdpConfig(eqArg(StorageConfig), any())(eqArg(implicitly[ExecutionContext]),
        eqArg(actorSystem))).thenReturn(saveConfigResult)
      this

    /**
      * Verifies that the storage service has been correctly invoked for saving
      * the data of an IDP.
      *
      * @return this test helper
      */
    def verifyStorageService(): CommandTestHelper =
      val capt = ArgumentCaptor.forClass(classOf[IDPConfig])
      verify(storageService).saveIdpConfig(eqArg(StorageConfig), capt.capture())(any(), any())
      val idpConfig = capt.getValue
      idpConfig.copy(oauthConfig = null) should be(TestConfig.copy(oauthConfig = null))
      idpConfig.oauthConfig.copy(clientSecret = null) should be(TestConfig.oauthConfig.copy(clientSecret = null))
      idpConfig.oauthConfig.clientSecret.secret should be(ClientSecret)
      this

    /**
      * Executes the command to be tested.
      *
      * @return the result of the execution
      */
    def runCommand(): Future[String] = OAuthCommandsImpl.initIdp(InitConfig, storageService)

