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

import com.github.sync.SyncTypes.{FsElement, FsFolder, SyncAction, SyncOperation}
import com.github.sync.log.ElementSerializer
import com.github.sync.stream.LocalState.{LocalElementState, affectsLocalState}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''LocalState''.
  */
class LocalStateSpec extends AnyFlatSpec, Matchers :
  /**
    * Helper function to check the ''affectsLocalState'' extension function.
    *
    * @param action         the action of the test operation
    * @param expectedResult the expected result
    */
  private def checkAffectsLocalState(action: SyncAction, expectedResult: Boolean): Unit =
    val op = SyncOperation(FsFolder("f1", "/a/folder", 2), action, 2, "f2")
    op.affectsLocalState shouldBe expectedResult

  "LocalState" should "detect that a SyncOperation with ActionNoop does not affect the local state" in {
    checkAffectsLocalState(SyncAction.ActionNoop, expectedResult = false)
  }

  it should "detect that a SyncOperation with ActionRemove does not affect the local state" in {
    checkAffectsLocalState(SyncAction.ActionRemove, expectedResult = false)
  }

  it should "detect that a SyncOperation with ActionCreate does not affect the local state" in {
    checkAffectsLocalState(SyncAction.ActionCreate, expectedResult = false)
  }

  it should "detect that a SyncOperation with ActionOverride does not affect the local state" in {
    checkAffectsLocalState(SyncAction.ActionOverride, expectedResult = false)
  }

  it should "detect that a SyncOperation with ActionLocalRemove affects the local state" in {
    checkAffectsLocalState(SyncAction.ActionLocalRemove, expectedResult = true)
  }

  it should "detect that a SyncOperation with ActionLocalCreate affects the local state" in {
    checkAffectsLocalState(SyncAction.ActionLocalCreate, expectedResult = true)
  }

  it should "detect that a SyncOperation with ActionLocalOverride affects the local state" in {
    checkAffectsLocalState(SyncAction.ActionLocalOverride, expectedResult = true)
  }

  /**
    * Checks whether the given element can be correctly serialized and
    * deserialized in local state.
    *
    * @param element the element
    * @param removed the removed flag
    */
  private def checkSerializationRoundTripForLocalState(element: FsElement, removed: Boolean): Unit =
    val state = LocalElementState(element, removed)
    val ser = ElementSerializer.serialize(state).utf8String
    val state2 = ElementSerializer.deserialize[LocalElementState](ser).get
    state2 should be(state)

  it should "support serialization and deserialization of a file in local state" in {
    val file = AbstractStageSpec.createFile(1)

    checkSerializationRoundTripForLocalState(file, removed = false)
  }

  it should "support serialization and deserialization of a folder in local state" in {
    val folder = AbstractStageSpec.createFolder(2)

    checkSerializationRoundTripForLocalState(folder, removed = true)
  }
