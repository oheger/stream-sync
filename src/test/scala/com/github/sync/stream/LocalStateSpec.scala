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
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder, SyncAction, SyncOperation}
import com.github.sync.log.ElementSerializer
import com.github.sync.stream.LocalState.{LocalElementState, LocalStateFile, LocalStateFolder, affectsLocalState}
import com.github.sync.{AsyncTestHelper, FileTestHelper, SyncTypes}
import org.scalatest.flatspec.{AnyFlatSpec, AnyFlatSpecLike}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.nio.file.{Files, Path, Paths}

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
    * Constructs a ''LocalStateFolder'' for the stream with the given name.
    *
    * @param streamName the stream name
    * @return the ''LocalStateFolder'' for this stream
    */
  private def stateFolder(streamName: String): LocalStateFolder =
    LocalStateFolder(testDirectory, streamName)

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
    val folder = stateFolder("TestSyncStream")
    val source = Source(elements)

    val sink = futureResult(LocalState.constructLocalStateSink(folder, promoteState = false))
    val stateFileResult = futureResult(source.runWith(sink))
    val localStateFile = createPathInDirectory(folder.streamName + ".lst.tmp")
    stateFileResult.toAbsolutePath should be(localStateFile.toAbsolutePath)
    checkLocalStateFile(localStateFile, elements)
  }

  it should "create a sink that overrides an existing local state file" in {
    val elements = (1 to 4) map { idx =>
      LocalElementState(AbstractStageSpec.createFile(idx), removed = false)
    }
    val source = Source(elements)
    val folder = stateFolder("TestStreamOverride")
    val localStateFile = writeFileContent(folder.resolve(LocalStateFile.Interrupted), FileTestHelper.TestData)

    val sink = futureResult(LocalState.constructLocalStateSink(folder, promoteState = false))
    futureResult(source.runWith(sink))
    checkLocalStateFile(localStateFile, elements)
  }

  it should "create a sink that promotes the temporary state to the final completed state" in {
    val elements = (1 to 8) map { idx =>
      val element = if idx % 2 == 0 then AbstractStageSpec.createFile(idx)
      else AbstractStageSpec.createFolder(idx)
      LocalElementState(element, idx % 3 == 0)
    }
    val folder = stateFolder("TestSyncStream")
    val source = Source(elements)

    val sink = futureResult(LocalState.constructLocalStateSink(folder))
    val stateFileResult = futureResult(source.runWith(sink))
    val localStateFile = createPathInDirectory(folder.streamName + ".lst")
    stateFileResult.toAbsolutePath should be(localStateFile.toAbsolutePath)
    checkLocalStateFile(localStateFile, elements)
    folder.resolveExisting(LocalStateFile.Interrupted) shouldBe empty
  }

  it should "create a sink that creates missing sub folders to the state file" in {
    val statePath = testDirectory.resolve(Paths.get("deeply", "nested", "state", "folder"))
    val folder = LocalStateFolder(statePath, "TestNewStream")
    val elements = List(LocalElementState(AbstractStageSpec.createFile(42), removed = false))
    val source = Source(elements)

    val sink = futureResult(LocalState.constructLocalStateSink(folder))
    val stateFilePath = futureResult(source.runWith(sink))
    checkLocalStateFile(stateFilePath, elements)
  }

  /**
    * Obtains the source for the local state of the given stream and returns a
    * sequence with its content.
    *
    * @param folder the folder containing the state files
    * @return a sequence with the elements from the stream's local state
    */
  private def readLocalStateSource(folder: LocalStateFolder): Seq[FsElement] =
    val source = futureResult(LocalState.constructLocalStateSource(folder))
    readLocalStateSource(source)

  /**
    * Reads the given ''Source'' with elements from the local state and returns
    * a sequence with its content.
    *
    * @param source the source
    * @return a sequence with the elements from this source
    */
  private def readLocalStateSource(source: Source[FsElement, Any]): Seq[FsElement] =
    val sink = Sink.fold[List[FsElement], FsElement](List.empty) { (list, elem) => elem :: list }
    futureResult(source.runWith(sink)).reverse

  /**
    * Writes a local state file for the given elements.
    *
    * @param folder   the folder containing the state files
    * @param elements the sequence of elements
    * @return the path to the file that was written
    */
  private def writeLocalStateFile(folder: LocalStateFolder, elements: Seq[LocalElementState]): Path =
    val elemSource = Source(elements)
    val sink = futureResult(LocalState.constructLocalStateSink(folder, promoteState = false))
    futureResult(elemSource.runWith(sink))

  it should "construct an empty source for a non-existing local state file" in {
    val folder = stateFolder("non-existing.lst")
    val source = futureResult(LocalState.constructLocalStateSource(folder))

    readLocalStateSource(source) shouldBe empty
  }

  it should "construct a source for an existing local state file" in {
    val elements = (1 to 16) map { idx =>
      val element = if idx % 2 == 0 then AbstractStageSpec.createFile(idx)
      else AbstractStageSpec.createFolder(idx)
      LocalElementState(element, removed = false)
    }
    val folder = stateFolder("StreamWithLocalState")
    writeLocalStateFile(folder, elements)
    folder.promoteToComplete(LocalStateFile.Interrupted)

    val stateElements = readLocalStateSource(folder)
    stateElements should contain theSameElementsInOrderAs elements.map(_.element)
  }

  it should "construct a source that filters out removed elements from the local state file" in {
    val elements = (1 to 8) map { idx =>
      val element = if idx % 2 == 0 then AbstractStageSpec.createFile(idx)
      else AbstractStageSpec.createFolder(idx)
      LocalElementState(element, removed = false)
    }
    val removedElements = elements map { stateElem =>
      val elem = stateElem.element match
        case file: FsFile => file.copy(id = file.id + "_removedFile")
        case folder: FsFolder => folder.copy(id = folder.id + "_removedFolder")
      LocalElementState(elem, removed = true)
    }
    val allElements = elements.zip(removedElements).foldRight(List.empty[LocalElementState]) { (p, list) =>
      p._1 :: p._2 :: list
    }
    val folder = stateFolder("StreamWithRemovedElementsInLocalState")
    writeLocalStateFile(folder, allElements)
    folder.promoteToComplete(LocalStateFile.Interrupted)

    val stateElements = readLocalStateSource(folder)
    stateElements should contain theSameElementsInOrderAs elements.map(_.element)
  }

  it should "construct a source that resumes an interrupted sync stream" in {
    val orgElements = (1 to 32) map { idx =>
      LocalElementState(AbstractStageSpec.createFile(idx), removed = false)
    }
    val interruptedElements = (1 to 20) map { idx =>
      val deltaTime = if idx % 2 == 0 then 42 else 0
      val removed = idx > 16
      LocalElementState(AbstractStageSpec.createFile(idx, deltaTime = deltaTime), removed)
    }
    val folder = stateFolder("TestStreamThatWasInterrupted")
    writeLocalStateFile(folder, orgElements)
    folder.promoteToComplete(LocalStateFile.Interrupted)
    writeLocalStateFile(folder, interruptedElements)
    val expectedElements = interruptedElements.slice(0, 16) ++ orgElements.drop(20)

    val stateElements = readLocalStateSource(folder)
    stateElements should contain theSameElementsInOrderAs expectedElements.map(_.element)
    folder.resolveExisting(LocalStateFile.Interrupted) shouldBe empty
    folder.resolveExisting(LocalStateFile.Resuming) shouldBe empty
    folder.resolveExisting(LocalStateFile.Complete) shouldBe defined
  }

  it should "construct a source that resumes an interrupted stream with a missing local state file" in {
    val elements = (1 to 16) map { idx =>
      val element = if idx % 2 == 0 then AbstractStageSpec.createFile(idx)
      else AbstractStageSpec.createFolder(idx)
      LocalElementState(element, removed = false)
    }
    val folder = stateFolder("TestStreamThatWasInterruptedWithoutLocalState")
    writeLocalStateFile(folder, elements)

    val stateElements = readLocalStateSource(folder)
    stateElements should contain theSameElementsInOrderAs elements.map(_.element)
  }

  it should "construct a source that resumes an interrupted stream if the interrupted file is empty" in {
    val elements = (1 to 8) map { idx =>
      LocalElementState(AbstractStageSpec.createFile(idx), removed = false)
    }
    val folder = stateFolder("TestStreamThatWasDirectlyInterrupted")
    writeLocalStateFile(folder, elements)
    folder.promoteToComplete(LocalStateFile.Interrupted)
    writeFileContent(folder.resolve(LocalStateFile.Interrupted), "")

    val stateElements = readLocalStateSource(folder)
    stateElements should contain theSameElementsInOrderAs elements.map(_.element)
    folder.resolveExisting(LocalStateFile.Interrupted) shouldBe empty
  }
