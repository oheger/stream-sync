/*
 * Copyright 2018-2019 The Developers Team.
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

import java.io.IOException
import java.nio.file.Paths

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.github.sync.AsyncTestHelper
import com.github.sync.cli.ConsoleReader
import com.github.sync.cli.ParameterManager.ParametersMap
import com.github.sync.crypt.Secret
import com.github.sync.webdav.OAuthStorageConfig
import com.github.sync.http.oauth.{OAuthConfig, OAuthStorageService, OAuthTokenData}
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.mockito.Matchers.{any, eq => eqArg}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

object OAuthInitCommandSpec {
  /** A name for the test IDP. */
  private val IdpName = "myTestIDP"

  /** Test configuration for an IDP. */
  private val TestConfig = OAuthConfig("authEndpoint", "tokenEndpoint", "scope", "redirect", "clientID")

  /** A test storage configuration. */
  private val StorageConfig = OAuthStorageConfig(baseName = IdpName, rootDir = Paths.get("/foo"),
    optPassword = None)

  /** The content of the client secret. */
  private val ClientSecret = "<very secret client>"

  /** The map with command line parameters for the test command. */
  private val CommandParameters = createParametersMap()

  /**
    * Creates the map with test parameters for the command.
    *
    * @return the parameters map
    */
  private def createParametersMap(): ParametersMap =
    Map(OAuthParameterManager.AuthEndpointOption -> List(TestConfig.authorizationEndpoint),
      OAuthParameterManager.TokenEndpointOption -> List(TestConfig.tokenEndpoint),
      OAuthParameterManager.RedirectUrlOption -> List(TestConfig.redirectUri),
      OAuthParameterManager.ScopeOption -> List(TestConfig.scope),
      OAuthParameterManager.ClientIDOption -> List(TestConfig.clientID),
      OAuthParameterManager.ClientSecretOption -> List(ClientSecret))
}

/**
  * Test class for ''OAuthInitCommand''.
  */
class OAuthInitCommandSpec extends FlatSpec with Matchers with MockitoSugar with AsyncTestHelper {

  import OAuthInitCommandSpec._

  "OAuthInitCommand" should "initialize a new IDP" in {
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

  it should "report an exception when storing the secret" in {
    val exception = new IOException("Cannot store secret")
    val helper = new CommandTestHelper

    val ex = expectFailedFuture[IOException](helper.prepareStorageService(saveSecretResult = Future.failed(exception))
      .runCommand())
    ex should be(exception)
  }

  /**
    * A test helper class managing dependencies of a command.
    */
  private class CommandTestHelper {
    /** Implicit console reader required for command execution. */
    private implicit val consoleReader: ConsoleReader = mock[ConsoleReader]

    /** Implicit actor system required for command execution. */
    private implicit val actorSystem: ActorSystem = mock[ActorSystem]

    /** Implicit object to materialize streams required by commands. */
    private implicit val streamMat: ActorMaterializer = mock[ActorMaterializer]

    /** Mock for the storage service. */
    private val storageService = mock[OAuthStorageService[OAuthStorageConfig, OAuthConfig,
      Secret, OAuthTokenData]]

    /**
      * Prepares the mock for the storage service to expect invocations for
      * saving data related to a new IDP. The results can be specified.
      *
      * @param saveConfigResult result for saving the config
      * @param saveSecretResult result for saving the client secret
      * @return this test helper
      */
    def prepareStorageService(saveConfigResult: Future[Done] = Future.successful(Done),
                              saveSecretResult: Future[Done] = Future.successful(Done)): CommandTestHelper = {
      when(storageService.saveConfig(StorageConfig, TestConfig)).thenReturn(saveConfigResult)
      when(storageService.saveClientSecret(eqArg(StorageConfig), any())(eqArg(implicitly[ExecutionContext]),
        eqArg(streamMat))).thenReturn(saveSecretResult)
      this
    }

    /**
      * Verifies that the storage service has been correctly invoked for saving
      * the data of an IDP.
      *
      * @return this test helper
      */
    def verifyStorageService(): CommandTestHelper = {
      verify(storageService).saveConfig(StorageConfig, TestConfig)
      val capt = ArgumentCaptor.forClass(classOf[Secret])
      verify(storageService).saveClientSecret(eqArg(StorageConfig), capt.capture())(eqArg(implicitly[ExecutionContext]),
        eqArg(streamMat))
      capt.getValue.secret should be(ClientSecret)
      this
    }

    /**
      * Executes the command to be tested.
      *
      * @return the result of the execution
      */
    def runCommand(): Future[String] = {
      val cmd = new OAuthInitCommand
      cmd.run(StorageConfig, storageService, CommandParameters)
    }
  }

}
