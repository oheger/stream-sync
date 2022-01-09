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
  * Test class for ''NormalizedFolder''.
  */
class NormalizedFolderSpec extends AnyFlatSpec, Matchers :
  "NormalizedFolder" should "have a normalized URI" in {
    val path = "/the/folder/path"
    val folder = FsFolder("folderID", path, 3)

    val normFolder = NormalizedFolder(folder)
    normFolder.folder should be(folder)
    normFolder.normalizedUri should be(path + "/")
  }

  it should "create a correct instance for an already normalized folder URI" in {
    val path = "/the/already/normalized/folder/path/"
    val folder = FsFolder("normID", path, 5)

    val normFolder = NormalizedFolder(folder)
    normFolder.folder should be(folder)
    normFolder.normalizedUri should be(path)
  }

  "isInTree" should "correctly detect elements belonging to the tree of a folder" in {
    val folder = AbstractStageSpec.createFolder(1)
    val directChildFile = AbstractStageSpec.createFile(10, optParent = Some(folder))
    val directChildFolder = AbstractStageSpec.createFolder(11, optParent = Some(folder))
    val subChildFile = AbstractStageSpec.createFile(20, optParent = Some(directChildFolder))
    val subChildFolder = AbstractStageSpec.createFolder(21, optParent = Some(directChildFolder))
    val subSubFolder = AbstractStageSpec.createFolder(30, optParent = Some(subChildFolder))
    val elements = List(directChildFile, directChildFolder, subChildFile, subChildFolder, subSubFolder)
    val normFolder = NormalizedFolder(folder)

    elements forall normFolder.isInTree shouldBe true
  }

  it should "correctly detect elements not belonging to the tree of a folder" in {
    val folder = FsFolder("fid", "/folder1/path", 2)
    val elements = List(FsFolder("id0", "/before", 1),
      FsFile("id1", "/folder0/file.txt", 2, Instant.now(), 1000),
      FsFolder("id2", "/folder2", 1),
      FsFolder("id3", "/before/deeply/nested/folder", 4),
      FsFolder("id4", "/folder10/other-path", 2))
    val normFolder = NormalizedFolder(folder)

    elements exists normFolder.isInTree shouldBe false
  }

  it should "correctly detect non-child elements whose name prefix match" in {
    val folder = FsFolder("fid", "/folder1", 1)
    val nonChildFolder = FsFolder("fid2", "/folder11", 1)
    val normFolder = NormalizedFolder(folder)

    normFolder.isInTree(nonChildFolder) shouldBe false
  }

  "isChildIterationComplete" should "return false for an element on the same level" in {
    val folder = FsFolder("fid", "/folder1", 1)
    val element = FsFolder("fid2", "/folder2", 1)
    val normFolder = NormalizedFolder(folder)

    normFolder.isChildIterationComplete(element) shouldBe false
  }

  it should "return true for an element on the next level after the folder's path" in {
    val folder = FsFolder("fid", "/folder1", 1)
    val element = FsFolder("fid2", "/folder2/first.txt", 2)
    val normFolder = NormalizedFolder(folder)

    normFolder.isChildIterationComplete(element) shouldBe true
  }

  it should "return false for an element on the next level before the folder's path" in {
    val folder = FsFolder("fid", "/folder1", 1)
    val element = FsFolder("fid2", "/folder0/zero", 2)
    val normFolder = NormalizedFolder(folder)

    normFolder.isChildIterationComplete(element) shouldBe false
  }

  it should "return false for a child element of the folder" in {
    val folder = FsFolder("fid", "/folder1", 1)
    val element = FsFolder("fid2", "/folder1/child", 2)
    val normFolder = NormalizedFolder(folder)

    normFolder.isChildIterationComplete(element) shouldBe false
  }

  it should "return true for an element one level below the children's level of the folder" in {
    val folder = FsFolder("fid", "/folder1", 1)
    val element = FsFolder("fid2", "/folder0/first/sub", 3)
    val normFolder = NormalizedFolder(folder)

    normFolder.isChildIterationComplete(element) shouldBe true
  }