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

package com.github.sync.auth

import com.github.cloudfiles.core.http.Secret
import com.github.sync.FileTestHelper
import com.github.sync.auth.CredentialsServiceImpl.CredentialEntry
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Framing
import org.apache.pekko.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.io.IOException
import java.nio.file.{Files, Paths}

object CredentialsServiceImplSpec:
  /** The secret that is used to encrypt data. */
  private val CryptSecret = Secret("secret-for-encryption")

  /**
    * Converts the given list of credential entries to a list of key-value
    * pairs. This is needed to compare lists of entries, because [[Secret]]
    * objects have no built-in ''equals()'' method.
    *
    * @param entries the entries to convert
    * @return a list with tuples corresponding to the entries
    */
  private def credentialsTuples(entries: List[CredentialEntry]): List[(String, String)] =
    entries.map(e => e.key -> e.value.secret)
end CredentialsServiceImplSpec

/**
  * Test class for [[CredentialsServiceImpl]].
  */
class CredentialsServiceImplSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with FileTestHelper:
  def this() = this(ActorSystem("CredentialsServiceImplSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  import CredentialsServiceImplSpec.*

  "CredentialsServiceImpl" should "save an encrypted file with credentials" in :
    val credentials = List(
      CredentialEntry("testEntry", Secret("foo")),
      CredentialEntry("otherEntry", Secret("bar"))
    )
    val credentialsFile = createPathInDirectory("credentials.json.crypt")

    CredentialsServiceImpl.storeCredentials(credentialsFile, CryptSecret, credentials) map : _ =>
      val content = new String(Files.readAllBytes(credentialsFile))
      content should not include "foo"
      content should not include "bar"

  it should "load an encrypted file with credentials" in :
    val credentials = List(
      CredentialEntry("testEntry", Secret("foo")),
      CredentialEntry("otherEntry", Secret("bar")),
      CredentialEntry("complexEntry", Secret("a very(!) \"strong':) *secret+ value#"))
    )
    val credentialsFile = createPathInDirectory("credentials.json.crypt")

    CredentialsServiceImpl.storeCredentials(credentialsFile, CryptSecret, credentials) flatMap : _ =>
      CredentialsServiceImpl.loadCredentials(credentialsFile, CryptSecret) map : credentials2 =>
        credentialsTuples(credentials2) should be(credentialsTuples(credentials))

  it should "override an existing credentials file" in :
    val credentials = List(
      CredentialEntry("testEntry", Secret("foo")),
      CredentialEntry("otherEntry", Secret("bar")),
      CredentialEntry("complexEntry", Secret("a very(!) \"strong':) *secret+ value#"))
    )
    val credentialsFile = createPathInDirectory("credentials.json.crypt")

    CredentialsServiceImpl.storeCredentials(credentialsFile, CryptSecret, credentials.take(1)) flatMap : _ =>
      CredentialsServiceImpl.storeCredentials(credentialsFile, CryptSecret, credentials) flatMap : _ =>
        CredentialsServiceImpl.loadCredentials(credentialsFile, CryptSecret) map : credentials2 =>
          credentialsTuples(credentials2) should be(credentialsTuples(credentials))

  it should "fail for a non-existing credentials file" in :
    recoverToSucceededIf[IOException]:
      CredentialsServiceImpl.loadCredentials(Paths.get("non", "existing", "credentials.crypt"), CryptSecret)

  it should "fail for an invalid decryption secret" in :
    val credentials = List(CredentialEntry("non-recoverable", Secret("lost")))
    val credentialsFile = createPathInDirectory("lost-secret.json.crypt")

    CredentialsServiceImpl.storeCredentials(credentialsFile, CryptSecret, credentials) flatMap : _ =>
      recoverToExceptionIf[Framing.FramingException]:
        CredentialsServiceImpl.loadCredentials(credentialsFile, Secret("wrongValue"))
      .map: exception =>
        exception.getMessage should include("Invalid JSON")
