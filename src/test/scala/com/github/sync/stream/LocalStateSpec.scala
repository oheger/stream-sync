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

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import com.github.sync.SyncTypes.{FsElement, FsFolder, SyncAction, SyncOperation}
import com.github.sync.log.ElementSerializer
import com.github.sync.stream.LocalState.{LocalElementState, affectsLocalState}
import com.github.sync.{AsyncTestHelper, FileTestHelper}
import org.scalatest.flatspec.{AnyFlatSpec, AnyFlatSpecLike}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.nio.file.Path

/**
  * Test class for ''LocalState''.
  */
class LocalStateSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike, BeforeAndAfterAll,
  BeforeAndAfterEach, Matchers, FileTestHelper, AsyncTestHelper :
  def this() = this(ActorSystem("LocalStateSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  import system.dispatcher

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

  /**
    * Reads a file with serialized local state elements.
    *
    * @param path the path to the file to read
    * @return a sequence with the elements extracted from the file
    */
  private def readLocalStateFile(path: Path): Seq[LocalElementState] =
    val lines = readDataFile(path).split(System.lineSeparator())
    lines.map(line => ElementSerializer.deserialize[LocalElementState](line).get)

  /**
    * Helper function to check whether a file with local state information
    * contains the expected elements.
    *
    * @param path        the path to the file with state information
    * @param expElements the expected elements
    */
  private def checkLocalStateFile(path: Path, expElements: Seq[LocalElementState]): Unit =
    val stateElements = readLocalStateFile(path)
    stateElements should contain theSameElementsInOrderAs expElements

  it should "create a sink to update the local state" in {
    val elements = (1 to 8) map { idx =>
      val element = if idx % 2 == 0 then AbstractStageSpec.createFile(idx)
      else AbstractStageSpec.createFolder(idx)
      LocalElementState(element, idx % 3 == 0)
    }
    val StreamName = "TestSyncStream"
    val source = Source(elements)

    val sink = LocalState.localStateSink(testDirectory, StreamName)
    val stateFileResult = futureResult(source.runWith(sink))
    val localStateFile = createPathInDirectory(StreamName + ".lst.tmp")
    stateFileResult.toAbsolutePath should be(localStateFile.toAbsolutePath)
    checkLocalStateFile(localStateFile, elements)
  }

  it should "create a sink that overrides an existing local state file" in {
    val elements = (1 to 4) map { idx =>
      LocalElementState(AbstractStageSpec.createFile(idx), removed = false)
    }
    val source = Source(elements)
    val StreamName = "TestStreamOverride"
    val localStateFile = writeFileContent(createPathInDirectory(StreamName + ".lst.tmp"), FileTestHelper.TestData)

    val sink = LocalState.localStateSink(testDirectory, StreamName)
    futureResult(source.runWith(sink))
    checkLocalStateFile(localStateFile, elements)
  }
