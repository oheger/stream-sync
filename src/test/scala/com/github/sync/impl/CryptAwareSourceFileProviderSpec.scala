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

package com.github.sync.impl

import com.github.sync.crypt.EncryptOpHandler
import com.github.sync.{SourceFileProvider, SyncTypes}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Test class for ''CryptAwareSourceFileProvider''. This class only tests a
  * subset of the functionality implemented by this class. The remaining part
  * is handled by integration tests.
  */
class CryptAwareSourceFileProviderSpec extends FlatSpec with Matchers with MockitoSugar {
  /**
    * Returns a ''SourceFileProvider'' that can be wrapped by a test instance.
    * Using a real provider object has the advantage that some methods of
    * this class are directly tested together with the class under test.
    *
    * @return the wrapped provider instance
    */
  private def createWrappedProvider(): SourceFileProvider =
    (_: SyncTypes.FsFile) => Future.failed(new UnsupportedOperationException("Unexpected invocation"))

  "A CryptAwareSourceFileProver" should "delegate the shutdown() method" in {
    val wrapped = mock[SourceFileProvider]
    val provider = CryptAwareSourceFileProvider(wrapped, None, None)

    provider.shutdown()
    verify(wrapped).shutdown()
  }

  it should "increase the file size if there is encryption" in {
    val FileSize = 20190325182010L
    val expSize = EncryptOpHandler.processedSize(FileSize)
    val provider = CryptAwareSourceFileProvider(createWrappedProvider(), None, Some("testPWD"))

    provider.fileSize(FileSize) should be(expSize)
  }

  it should "decrease the file size again if there is decryption" in {
    val FileSize = 20190325182200L
    val provider = CryptAwareSourceFileProvider(createWrappedProvider(), Some("decPwd"), Some("encPWD"))

    provider.fileSize(FileSize) should be(FileSize)
  }
}
