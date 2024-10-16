/*
 * Copyright 2018-2024 The Developers Team.
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

import com.github.sync.SyncTypes.{FsFile, FsFolder, SyncAction, SyncOperation}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class RemovedFolderStateSpec extends AnyFlatSpec, Matchers :
  "RemovedFolderState" should "return None if no root folder is found for an element" in {
    val element = FsFolder("someID", "/someUri", 11)

    RemovedFolderState.Empty.findRoot(element) should be(None)
  }

  it should "find the root folder of an element" in {
    val root = FsFolder("root", "/root", 1)
    val otherRoot = FsFolder("other", "/another/root", 2)
    val element = FsFile("file", "/root/path/to/file.txt", 3, Instant.now(), 100)
    val roots = RemovedFolderState(Set(otherRoot.toNormalizedFolder, root.toNormalizedFolder), List.empty)

    roots.findRoot(element) should be(Some(root.toNormalizedFolder))
  }

  it should "handle a removed file under a removed root folder" in {
    val file = FsFile("f1", "/some/path/file.txt", 2, Instant.now(), 1500)
    val root = FsFolder("root", "/some", 1)
    val expOp = SyncOperation(file, SyncAction.ActionRemove, root.level, file.id)
    val state = RemovedFolderState.Empty

    val (ops, nextState) = state.handleRemoveElement(file, SyncAction.ActionRemove, Some(root.toNormalizedFolder))
    nextState should be theSameInstanceAs state
    ops should contain only expOp
  }

  it should "handle a removed file not under a removed root folder" in {
    val file = FsFile("fDel", "/the/removed/file.doc", 2, Instant.now(), 2000)
    val expOp = SyncOperation(file, SyncAction.ActionLocalRemove, file.level, file.id)
    val state = RemovedFolderState.Empty

    val (ops, nextState) = state.handleRemoveElement(file, SyncAction.ActionLocalRemove, None)
    nextState should be theSameInstanceAs state
    ops should contain only expOp
  }

  it should "handle a removed folder under a removed root folder" in {
    val folder = FsFolder("subFolder", "/path/to/delete/deeply/nested/sub", 5)
    val root = FsFolder("root", "/path/", 1)
    val rootOp = SyncOperation(root, SyncAction.ActionLocalRemove, root.level, root.id)
    val expOp = SyncOperation(folder, SyncAction.ActionLocalRemove, root.level, folder.id)
    val state = RemovedFolderState(Set.empty, List(rootOp))

    val (ops, nextState) = state.handleRemoveElement(folder, SyncAction.ActionLocalRemove,
      Some(root.toNormalizedFolder))
    ops shouldBe empty
    nextState.deferredOperations should contain only(expOp, rootOp)
    nextState.roots shouldBe empty
  }

  it should "handle a new removed root folder" in {
    val otherRoot = FsFolder("otherRemovedRoot", "/other/path/to/delete", 3)
    val otherOp = SyncOperation(otherRoot, SyncAction.ActionRemove, otherRoot.level, otherRoot.id)
    val FolderPath = "/path/to/delete"
    val folder = FsFolder("removedRoot", FolderPath, 2)
    val expOp = SyncOperation(folder, SyncAction.ActionRemove, folder.level, folder.id)
    val state = RemovedFolderState(Set(otherRoot.toNormalizedFolder), List(otherOp))

    val (ops, nextState) = state.handleRemoveElement(folder, SyncAction.ActionRemove, None)
    ops shouldBe empty
    nextState.deferredOperations should contain only(expOp, otherOp)
    nextState.roots should contain only(folder.toNormalizedFolder, otherRoot.toNormalizedFolder)
  }

  it should "allow adding a folder manually" in {
    val otherRoot = FsFolder("existingRemovedRoot", "/other/path/to/delete", 3).toNormalizedFolder
    val FolderPath = "/explicit/folder/to/add"
    val folder = FsFolder("irregularFolder", FolderPath, 3)
    val state = RemovedFolderState(Set(otherRoot), List.empty)

    val nextState = state.addRoot(folder)
    nextState.deferredOperations shouldBe empty
    nextState.roots should contain only(otherRoot, folder.toNormalizedFolder)
  }

  it should "allow adding a normalized folder manually" in {
    val otherRoot = FsFolder("existingRemovedRoot", "/other/path/to/delete", 3).toNormalizedFolder
    val FolderPath = "/explicit/norm/folder/to/add"
    val folder = FsFolder("irregularFolder", FolderPath, 3).toNormalizedFolder
    val state = RemovedFolderState(Set(otherRoot), List.empty)

    val nextState = state.addRoot(folder)
    nextState.deferredOperations shouldBe empty
    nextState.roots should contain only(otherRoot, folder)
  }
