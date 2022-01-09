/*
 * Copyright 2018-2022 The Developers Team.
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

package com.github.sync.stream

import com.github.sync.SyncTypes.{FsFile, FsFolder}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/**
  * Test class for the extension functions of the stream package.
  */
class ExtensionsSpec extends AnyFlatSpec, Matchers :
  "modifiedTime" should "return the time of a file" in {
    val FileModifiedTime = Instant.parse("2021-12-03T20:03:37Z")
    val file = FsFile("fid", "/some/file.txt", 2, FileModifiedTime, 1000)

    val time = file.modifiedTime(throw new UnsupportedOperationException("Unexpected evaluation"))
    time should be(FileModifiedTime)
  }

  it should "return the provided time of a folder" in {
    val FolderModifiedTime = Instant.parse("2021-12-03T20:07:12Z")
    val folder = FsFolder("folderId", "/some/folder/path", 3)

    val time = folder.modifiedTime(FolderModifiedTime)
    time should be(FolderModifiedTime)
  }

  "toNormalizedFolder" should "return a correct normalized folder" in {
    val folder = FsFolder("folderID", "/my/folder/path", 3)

    val normFolder = folder.toNormalizedFolder
    normFolder.folder should be(folder)
    normFolder.normalizedUri should be(folder.relativeUri + "/")
  }
