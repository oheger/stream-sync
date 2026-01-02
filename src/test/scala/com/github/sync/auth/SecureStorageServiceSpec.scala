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
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

/**
  * Test class for [[SecureStorageService]].
  */
class SecureStorageServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("SecureStorageServiceSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Returns a [[Source]] that produces the given data (in multiple chunks).
    *
    * @param data the data for the source
    * @return the [[Source]] producing this data
    */
  private def dataSource(data: Array[Byte]): Source[ByteString, Any] =
    Source(ByteString(data).grouped(32).toList)

  /**
    * Collects the data from the given [[Source]] as a byte string.
    *
    * @param source the source to process
    * @return a [[Future]] with the data collected from this source
    */
  private def runSource(source: Source[ByteString, Any]): Future[ByteString] =
    source.runFold(ByteString.empty)(_ ++ _)

  "SecureStorageService" should "support a round-trip of encrypting and decrypting data" in :
    val secret = Secret("my-encryption-key")
    val source = dataSource(FileTestHelper.testBytes())
    val encryptSource = SecureStorageService.encryptSource(source, secret)

    runSource(encryptSource) flatMap : encrypted =>
      val cryptData = encrypted.toArray
      cryptData should not be FileTestHelper.testBytes()
      val source2 = dataSource(cryptData)
      val decryptSource = SecureStorageService.decryptSource(source2, secret)
      runSource(decryptSource) map : decrypted =>
        decrypted.toArray should be(FileTestHelper.testBytes())
