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
import com.github.sync.auth.CredentialsServiceImpl
import com.github.sync.cli.CredentialsResolver.ResolverFunc
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import scala.concurrent.Future

object CredentialsResolverSpec:
  /** The prefix to indicate a resolvable credential. */
  private val CredentialsPrefix = "cred:"

  /** The path to the credentials file. */
  private val CredentialsPath = Paths.get("path", "to", "my", "credentials.store")

  /** The secret to encrypt the credentials file. */
  private val CredentialsSecret = Secret("UNeverGetMyCredentials!")

  /** A credentials configuration with test values. */
  private val TestCredentialsConfig = SyncCliStreamConfig.CredentialsConfig(
    credentialsFile = CredentialsPath,
    credentialsSecret = CredentialsSecret,
    credentialsPrefix = CredentialsPrefix
  )

  /** A test credential key. */
  private val TestCredential = "mySecretPassword"

  /** The test credential key with the configured prefix. */
  private val TestCredentialWithPrefix = CredentialsPrefix + TestCredential

  /** The resolved value of the test credential key. */
  private val TestCredentialValue = "superS3cr3T*:-}"
end CredentialsResolverSpec

/**
  * Test class for [[CredentialsResolver]].
  */
class CredentialsResolverSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("CredentialsResolverSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import CredentialsResolverSpec.*

  /**
    * Creates a mock credentials loader service that returns some default
    * credentials plus the test credential.
    *
    * @return the mock credentials loader service
    */
  private def createLoaderMock(): CredentialsResolver.CredentialsLoader =
    val defaultEntries = (1 to 16).map: idx =>
      CredentialsServiceImpl.CredentialEntry(s"cred_$idx", Secret(s"secret_$idx"))
    val credentialsEntries = CredentialsServiceImpl.CredentialEntry(
      TestCredential,
      Secret(TestCredentialValue)
    ) :: defaultEntries.toList
    val loader = mock[CredentialsResolver.CredentialsLoader]
    when(loader.loadCredentials(CredentialsPath, CredentialsSecret))
      .thenReturn(Future.successful(credentialsEntries))
    loader

  /**
    * Obtains a resolver function that is initialized with the test credential
    * storage.
    *
    * @return the initialized resolver function
    */
  private def fetchInitializedResolverFunc(): ResolverFunc =
    CredentialsResolver.createResolver(Some(TestCredentialsConfig), createLoaderMock())

  "CredentialsResolver" should "return a suitable resolver function if no configuration is available" in :
    val loader = mock[CredentialsResolver.CredentialsLoader]
    val resolverFunc = CredentialsResolver.createResolver(None, loader)

    val credentials = List("cred1", "cred2", "moreCredentials", "verySecretCredential", "foo", "bar", "baz")
    Future.sequence(credentials.map(resolverFunc)) map : resolvedCredentials =>
      verifyNoInteractions(loader)
      resolvedCredentials should contain theSameElementsInOrderAs credentials

  it should "return a resolver function that resolves a credential with a matching prefix" in :
    val resolverFunc = fetchInitializedResolverFunc()

    resolverFunc(TestCredentialWithPrefix) map : resolved =>
      resolved should be(TestCredentialValue)

  it should "return a resolver function that does not resolve a credential without a prefix" in :
    val resolverFunc = fetchInitializedResolverFunc()

    resolverFunc(TestCredential) map : resolved =>
      resolved should be(TestCredential)

  it should "return a resolver function that fails for an unresolvable credential" in :
    val unknownCredential = "thisKeyDoesNotExist"
    val resolverFunc = fetchInitializedResolverFunc()

    recoverToExceptionIf[CredentialsResolver.UnresolvableCredentialException]:
      resolverFunc(CredentialsPrefix + unknownCredential)
    .map: exception =>
      exception.key should be(unknownCredential)
      exception.getMessage should be(s"Cannot resolve credential '$unknownCredential'.")

  it should "return a resolver function that propagates the exception from the storage" in :
    val loaderException = new IllegalStateException("Test exception: Cannot load credentials file.")
    val loader = mock[CredentialsResolver.CredentialsLoader]
    when(loader.loadCredentials(CredentialsPath, CredentialsSecret))
      .thenReturn(Future.failed(loaderException))
    val resolverFunc = CredentialsResolver.createResolver(Some(TestCredentialsConfig), loader)

    recoverToExceptionIf[IllegalStateException]:
      resolverFunc(TestCredentialWithPrefix)
    .map: exception =>
      exception should be(loaderException)

  "toSecretResolver" should "resolve secrets" in :
    val secretResolverFunc = CredentialsResolver.toSecretResolver(fetchInitializedResolverFunc())

    secretResolverFunc(Secret(TestCredentialWithPrefix)) map : resolved =>
      resolved.secret should be(TestCredentialValue)

  it should "return the original secret if the prefix is not matching" in :
    val secretToResolve = Secret(TestCredential)
    val secretResolverFunc = CredentialsResolver.toSecretResolver(fetchInitializedResolverFunc())

    secretResolverFunc(secretToResolve) map : resolved =>
      resolved should be(secretToResolve)
