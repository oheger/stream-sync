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

package com.github.sync.crypt

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.{FsFile, FsFolder, IterateResult, SyncFolderData}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object CryptServiceSpec {
  /** A key used for crypt operations. */
  private val SecretKey = CryptStage.keyFromString("A_Secr3t.Key!")
}

/**
  * Test class for ''CryptService''.
  */
class CryptServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with BeforeAndAfterAll
  with Matchers with AsyncTestHelper {
  def this() = this(ActorSystem("CryptServiceSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import CryptServiceSpec._
  import system.dispatcher

  /** The object to materialize streams. */
  implicit val mat: ActorMaterializer = ActorMaterializer()

  "CryptService" should "return a transformation function that adapts file sizes" in {
    val files = List(FsFile("/f1.txt", 2, Instant.now(), 180106),
      FsFile("/f2.doc", 2, Instant.now(), 180209),
      FsFile("/f3.dat", 2, Instant.now(), 180228))
    val expFiles = files.map { f =>
      f.copy(size = DecryptOpHandler.processedSize(f.size))
    }
    val result = IterateResult(FsFolder("/aFolder", 2), files, List.empty[SyncFolderData[Unit]])

    val transformer = CryptService.cryptTransformer()
    val transResult = futureResult(transformer.transform(result, ()))._1
    transResult.currentFolder should be(result.currentFolder)
    transResult.folders should be(result.folders)
    transResult.files should be(expFiles)
  }

  it should "encrypt names" in {
    val Name = "A test name"

    val encName = futureResult(CryptService.encryptName(SecretKey, Name))
    encName should not be Name
  }

  it should "use a proper encoding for encrypted names" in {
    val Name = "ThisIsANameThatIsGoingToBeEncrypted.test"

    val encName = futureResult(CryptService.encryptName(SecretKey, Name))
    encName.filterNot { c =>
      c.isLetterOrDigit || c == '-' || c == '_' || c == '='
    } should be("")
  }

  it should "support a round-trip of encrypting and decrypting names" in {
    val Name = "ThisNameWillBeEncryptedAndDecrypted.test"

    val processedName = futureResult(CryptService.encryptName(SecretKey, Name)
      .flatMap(n => CryptService.decryptName(SecretKey, n)))
    processedName should be(Name)
  }
}
