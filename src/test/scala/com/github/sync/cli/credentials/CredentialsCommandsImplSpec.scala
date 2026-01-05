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

package com.github.sync.cli.credentials

import com.github.cloudfiles.core.http.Secret
import com.github.sync.auth.{CredentialsService, CredentialsServiceImpl}
import org.apache.pekko.actor.ActorSystem
import org.mockito.Mockito.*
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import scala.concurrent.Future

object CredentialsCommandsImplSpec:
  /** Test path to the credentials file. */
  private val CredentialsFilePath = Paths.get("path", "to", "credentials.crypt")

  /** The secret used by the test cases. */
  private val TestSecret = Secret("credentials-encrypt-secret")
end CredentialsCommandsImplSpec

/**
  * Test class for [[CredentialsCommandsImpl]].
  */
class CredentialsCommandsImplSpec extends AsyncFlatSpec with Matchers with MockitoSugar:

  import CredentialsCommandsImplSpec.*

  /** A mock actor system used for service invocations. */
  given mockActorSystem: ActorSystem = mock

  "A CredentialsCommandsImpl" should "list the keys of credentials from an existing file" in :
    val credentialKeys = List("cred1", "anotherCred", "testCred", "credit")
    val credentialEntries = credentialKeys.map(k => CredentialsServiceImpl.CredentialEntry(k, Secret(k + "_value")))
    val credentialsService = mock[CredentialsService[CredentialsServiceImpl.CredentialEntry]]
    when(credentialsService.loadCredentials(CredentialsFilePath, TestSecret))
      .thenReturn(Future.successful(credentialEntries))

    val commands = new CredentialsCommandsImpl(credentialsService)
    commands.listCredentials(CredentialsFilePath, TestSecret) map : result =>
      forEvery(credentialKeys): key =>
        result should include(key)

  it should "propagate an exception from the credentials service" in :
    val exception = new IllegalStateException("Test exception: Could not load credentials file.")
    val credentialsService = mock[CredentialsService[CredentialsServiceImpl.CredentialEntry]]
    when(credentialsService.loadCredentials(CredentialsFilePath, TestSecret))
      .thenReturn(Future.failed(exception))

    val commands = new CredentialsCommandsImpl(credentialsService)
    recoverToExceptionIf[IllegalStateException]:
      commands.listCredentials(CredentialsFilePath, TestSecret)
    .map: actualException =>
      actualException should be(exception)
