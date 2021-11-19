/*
 * Copyright 2018-2021 The Developers Team.
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

import com.github.sync.SyncTypes.{FsFile, FsFolder}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''SyncTypes''.
  */
class SyncTypesSpec extends AnyFlatSpec, Matchers :
  "SyncTypes" should "compare two element URIs that are equal" in {
    val elem1 = FsFile("id1", "/path/to/element", 3, null, 1000)
    val elem2 = FsFolder("id2", elem1.relativeUri, 3)

    SyncTypes.compareElementUris(elem1, elem2) should be(0)
  }

  it should "compare two element URIs if the first one is before" in {
    val elem1 = FsFolder("id1", "/path/to/element1", 3)
    val elem2 = FsFile("id2", "/path/to/element2", 3, null, 1234)

    SyncTypes.compareElementUris(elem1, elem2) should be < 0
  }

  it should "compare two element URIs if the second one is before" in {
    val elem1 = FsFolder("id1", "/path/to/element2", 3)
    val elem2 = FsFile("id2", "/path/to/element1", 3, null, 1234)

    SyncTypes.compareElementUris(elem1, elem2) should be > 0
  }

  it should "correctly compare two element URIs on different level" in {
    val elem1 = FsFolder("id1", "b", 1)
    val elem2 = FsFolder("id2", "a/subA", 2)

    SyncTypes.compareElementUris(elem1, elem2) should be < 0
  }
