/*
 * Copyright 2018-2025 The Developers Team.
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

package com.github.sync

import com.github.sync.SyncTypes.{FsFile, FsFolder, SyncConflictException, SyncOperation}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/**
  * Test class for ''SyncTypes''.
  */
class SyncTypesSpec extends AnyFlatSpec, Matchers :
  "SyncTypes" should "compare two element URIs that are equal" in {
    val elem1 = FsFile("id1", "/path/to/element", 3, null, 1000)
    val elem2 = FsFolder("id2", elem1.relativeUri, 3)

    SyncTypes.compareElements(elem1, elem2) should be(0)
  }

  it should "compare two element URIs if the first one is before" in {
    val elem1 = FsFolder("id1", "/path/to/element1", 3)
    val elem2 = FsFile("id2", "/path/to/element2", 3, null, 1234)

    SyncTypes.compareElements(elem1, elem2) should be < 0
  }

  it should "compare two element URIs if the second one is before" in {
    val elem1 = FsFolder("id1", "/path/to/element2", 3)
    val elem2 = FsFile("id2", "/path/to/element1", 3, null, 1234)

    SyncTypes.compareElements(elem1, elem2) should be > 0
  }

  it should "correctly compare two element URIs on different level" in {
    val elem1 = FsFolder("id1", "b", 1)
    val elem2 = FsFolder("id2", "a/subA", 2)

    SyncTypes.compareElements(elem1, elem2) should be < 0
  }

  it should "generate a correct message for a SyncConflictException" in {
    val elem1 = FsFile("id1", "/a/test/file.txt", 3, Instant.parse("2021-11-21T21:11:37Z"), 123456L)
    val elem2 = FsFolder("id2", "/test/folder", 2)
    val elem3 = FsFile("id1", "/a/test/file.txt", 3, Instant.parse("2021-11-22T17:03:06Z"), 123457L)
    val op1 = SyncOperation(elem1, SyncTypes.SyncAction.ActionRemove, 3, "id1")
    val op2 = SyncOperation(elem2, SyncTypes.SyncAction.ActionCreate, 2, "id2")
    val op3 = SyncOperation(elem3, SyncTypes.SyncAction.ActionLocalOverride, 3, "id1")
    val localOps = List(op3)
    val remoteOps = List(op1, op2)

    val conflict = SyncConflictException(localOps, remoteOps)
    val message = conflict.getMessage
    message should include(elem1.relativeUri + ", " + elem2.relativeUri)
    message should include("operations local: " + localOps)
    message should include("operations remote: " + remoteOps)
  }
