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
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Path, Paths}
import scala.concurrent.{ExecutionContext, Future}

/**
  * Test class for [[CredentialsService]].
  */
class CredentialsServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with FileTestHelper:
  def this() = this(ActorSystem("CredentialsServiceSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  "loadCredentialsOrEmpty()" should "load the credentials file if it exists" in :
    val credentials = List("credentials1", "credentials2", "moreCredentials", "topSecretCredentials")
    val credentialsFile = createDataFile()
    val testSecret = Secret("the-test-secret")

    val credentialsService = new CredentialsService[String]:
      override def loadCredentials(path: Path, secret: Secret)
                                  (using ec: ExecutionContext, system: ActorSystem): Future[List[String]] =
        path should be(credentialsFile)
        secret should be(testSecret)
        Future.successful(credentials)

      override def storeCredentials(path: Path, secret: Secret, credentials: Iterable[String])
                                   (using ec: ExecutionContext, system: ActorSystem): Future[Done] =
        throw new UnsupportedOperationException("Unexpected call.")

    credentialsService.loadCredentialsOrEmpty(credentialsFile, testSecret) map : result =>
      result should be(credentials)

  it should "return an empty list if the credentials file does not exist" in :
    val credentialsFile = Paths.get("non", "existing", "file.crypt")

    val credentialsService = new CredentialsService[String]:
      override def loadCredentials(path: Path, secret: Secret)
                                  (using ec: ExecutionContext, system: ActorSystem): Future[List[String]] =
        throw new UnsupportedOperationException("Unexpected call.")

      override def storeCredentials(path: Path, secret: Secret, credentials: Iterable[String])
                                   (using ec: ExecutionContext, system: ActorSystem): Future[Done] =
        throw new UnsupportedOperationException("Unexpected call.")

    credentialsService.loadCredentialsOrEmpty(credentialsFile, Secret("???")) map : result =>
      result shouldBe empty  
      