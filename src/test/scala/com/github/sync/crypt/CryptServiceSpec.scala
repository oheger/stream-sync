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

import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.{FsFile, FsFolder, IterateResult, SyncFolderData}
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''CryptService''.
  */
class CryptServiceSpec extends FlatSpec with Matchers with AsyncTestHelper {
  "CryptService" should "return a transformation function that adapts file sizes" in {
    val files = List(FsFile("/f1.txt", 2, Instant.now(), 180106),
      FsFile("/f2.doc", 2, Instant.now(), 180209),
      FsFile("/f3.dat", 2, Instant.now(), 180228))
    val expFiles = files.map { f =>
      f.copy(size = DecryptOpHandler.processedSize(f.size))
    }
    val result = IterateResult(FsFolder("/aFolder", 2), files, List.empty[SyncFolderData])

    val transformer = CryptService.cryptTransformer()
    val transResult = futureResult(transformer.transform(result))
    transResult.currentFolder should be(result.currentFolder)
    transResult.folders should be(result.folders)
    transResult.files should be(expFiles)
  }
}
