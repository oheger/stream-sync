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
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq as argEq}
import org.mockito.Mockito.*
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import scala.concurrent.{ExecutionContext, Future}

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

  /**
    * Prepares the given mock for a credentials service to expect an invocation
    * of the ''storeCredentials()'' operation.
    *
    * @param service the mock service
    */
  private def expectUpdatedCredentials(service: CredentialsService[CredentialsServiceImpl.CredentialEntry]): Unit =
    when(service.storeCredentials(argEq(CredentialsFilePath), argEq(TestSecret), any())(using any(), any()))
      .thenReturn(Future.successful(Done))

  /**
    * Verifies that the given mock for a credentials service was called to
    * store updated credentials and returns the credentials passed to this
    * invocation.
    *
    * @param service the mock service
    * @return the credentials that have been stored as a map
    */
  private def fetchUpdatedCredentials(service: CredentialsService[CredentialsServiceImpl.CredentialEntry]):
  Map[String, String] =
    given ActorSystem = argEq(mockActorSystem)

    given ExecutionContext = any()

    val captCredentials = ArgumentCaptor.forClass(classOf[Iterable[CredentialsServiceImpl.CredentialEntry]])
    verify(service).storeCredentials(
      argEq(CredentialsFilePath),
      argEq(TestSecret),
      captCredentials.capture()
    )
    captCredentials.getValue.map(e => e.key -> e.value.secret).toMap

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

  it should "add a new credential to a file" in :
    val CredentialKey = "newKey"
    val CredentialValue = Secret("newSecret")
    val credentialsService = mock[CredentialsService[CredentialsServiceImpl.CredentialEntry]]
    when(credentialsService.loadCredentialsOrEmpty(CredentialsFilePath, TestSecret))
      .thenReturn(Future.successful(Nil))
    expectUpdatedCredentials(credentialsService)

    val commands = new CredentialsCommandsImpl(credentialsService)
    commands.addCredential(CredentialsFilePath, TestSecret, CredentialKey, CredentialValue) map : result =>
      val updatedCredentials = fetchUpdatedCredentials(credentialsService)
      updatedCredentials should have size 1
      updatedCredentials(CredentialKey) should be(CredentialValue.secret)
      result should include(CredentialsFilePath.toString)
      result should include(s"'$CredentialKey' was added")
      result should include("contains 1 credential(s)")

  it should "override the value of a credential" in :
    val CredentialKey = "existingKey"
    val CredentialValue = Secret("changedSecret")
    val existingCredentials = List(
      CredentialsServiceImpl.CredentialEntry("someKey", Secret("someSecret")),
      CredentialsServiceImpl.CredentialEntry(CredentialKey, Secret("oldValue")),
      CredentialsServiceImpl.CredentialEntry("someOtherKey", Secret("someOtherSecret"))
    )
    val credentialsService = mock[CredentialsService[CredentialsServiceImpl.CredentialEntry]]
    when(credentialsService.loadCredentialsOrEmpty(CredentialsFilePath, TestSecret))
      .thenReturn(Future.successful(existingCredentials))
    expectUpdatedCredentials(credentialsService)

    val commands = new CredentialsCommandsImpl(credentialsService)
    commands.addCredential(CredentialsFilePath, TestSecret, CredentialKey, CredentialValue) map : result =>
      val updatedCredentials = fetchUpdatedCredentials(credentialsService)
      updatedCredentials should have size 3
      updatedCredentials(CredentialKey) should be(CredentialValue.secret)
      updatedCredentials("someKey") should be("someSecret")
      result should include(CredentialsFilePath.toString)
      result should include(s"'$CredentialKey' was replaced")
      result should include("contains 3 credential(s)")
