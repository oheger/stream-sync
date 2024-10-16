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

import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.{FsElement, SyncAction, SyncConflictException, SyncElementResult, SyncOperation}
import com.github.sync.stream.LocalStateStage.{ChangeType, ElementWithDelta}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

object SyncStageSpec:
  /** Prefix for a remote ID. */
  private val RemoteID = "remote"

  /**
    * Convenience function to create an ''ElementWithDelta''. The last local
    * time is set to the element's time.
    *
    * @param element    the element
    * @param changeType the change type
    * @return the ''ElementWithDelta''
    */
  private def deltaElem(element: FsElement, changeType: ChangeType): ElementWithDelta =
    ElementWithDelta(element, changeType, element.modifiedTime(null))

  /**
    * Creates a successful result for a single sync operation.
    *
    * @param op       the operation
    * @param optLocal the optional original local element
    * @return the result
    */
  private def createResult(op: SyncOperation, optLocal: Option[ElementWithDelta]): SyncStage.SyncStageResult =
    SyncStage.SyncStageResult(Right(List(op)), optLocal)

  /**
    * Creates a conflict result for two conflicting sync operations.
    *
    * @param localOp  the local operation
    * @param remoteOp the remote operation
    * @param optLocal the optional original local element
    * @return the conflict result
    */
  private def createConflict(localOp: SyncOperation, remoteOp: SyncOperation, optLocal: Option[ElementWithDelta]):
  SyncStage.SyncStageResult =
    val conflictException = SyncConflictException(List(localOp), List(remoteOp))
    SyncStage.SyncStageResult(Left(conflictException), optLocal)

/**
  * Test class for ''SyncStage''.
  */
class SyncStageSpec(testSystem: ActorSystem) extends AbstractStageSpec(testSystem) :
  def this() = this(ActorSystem("SyncStageSpec"))

  import AbstractStageSpec.*
  import SyncStageSpec.*

  "SyncStage" should "handle empty input sources" in {
    val result = runStage(new SyncStage, Source.empty, Source.empty)

    result shouldBe empty
  }

  it should "handle an empty source of local elements" in {
    val remoteElements = List(createFile(1), createFolder(2), createFile(3))
    val expectedResults = remoteElements.map { elem =>
      val op = SyncOperation(elem, SyncAction.ActionLocalCreate, elem.level, elem.id)
      createResult(op, None)
    }

    val result = runStage(new SyncStage, Nil, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "handle an empty source of remote elements if new local elements have been created" in {
    val localElements = List(deltaElem(createFile(1), ChangeType.Created),
      deltaElem(createFolder(2), ChangeType.Created),
      deltaElem(createFile(3), ChangeType.Created))
    val expectedResults = localElements map { elem =>
      val op = SyncOperation(elem.element, SyncAction.ActionCreate, elem.element.level, elem.element.id)
      createResult(op, Some(elem))
    }

    val result = runStage(new SyncStage, localElements, Nil)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "handle a stream that does not yield changes" in {
    val remoteElements = List(createFolder(1), createFile(2), createFolder(3))
    val localElements = remoteElements map { elem =>
      deltaElem(elem, ChangeType.Unchanged)
    }
    val expectedResults = remoteElements.zip(localElements) map { (remoteElem, localElem) =>
      val op = SyncOperation(remoteElem, SyncAction.ActionNoop, remoteElem.level, remoteElem.id)
      createResult(op, Some(localElem))
    }

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a local create operation" in {
    val remoteElements = List(createFile(1, RemoteID), createFile(2, RemoteID))
    val localElements = List(deltaElem(createFile(2), ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(remoteElements.head, SyncAction.ActionLocalCreate,
      remoteElements.head.level, remoteElements.head.id), None),
      createResult(SyncOperation(localElements.head.element, SyncAction.ActionNoop,
        localElements.head.element.level, remoteElements(1).id), Some(localElements.head)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a remote create operation" in {
    val localFolder1 = deltaElem(createFolder(1), ChangeType.Created)
    val localFolder2 = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFolder(2, RemoteID))
    val localElements = List(localFolder1, localFolder2)
    val expectedResults = List(createResult(SyncOperation(localElements.head.element, SyncAction.ActionCreate,
      localElements.head.element.level, localElements.head.element.id), Some(localFolder1)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements.head.id), Some(localFolder2)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a remote delete operation" in {
    val localFile = deltaElem(createFile(1), ChangeType.Removed)
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFile(1, RemoteID), createFolder(2, RemoteID))
    val localElements = List(localFile, localFolder)
    val expectedResults = List(createResult(SyncOperation(remoteElements.head, SyncAction.ActionRemove,
      remoteElements.head.level, remoteElements.head.id), Some(localFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id), Some(localFolder)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a local delete operation" in {
    val removedFile = deltaElem(createFile(1), ChangeType.Unchanged)
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFolder(2, RemoteID))
    val localElements = List(removedFile, localFolder)
    val expectedResults = List(createResult(SyncOperation(localElements.head.element, SyncAction.ActionLocalRemove,
      localElements.head.element.level, localElements.head.element.id), Some(removedFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements.head.id), Some(localFolder)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a remote override operation" in {
    val remoteElements = List(createFile(1, RemoteID), createFolder(2, RemoteID))
    val changedFile = ElementWithDelta(createFile(1, deltaTime = 1), ChangeType.Changed,
      remoteElements.head.modifiedTime(null))
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val localElements = List(changedFile, localFolder)
    val expectedResults = List(createResult(SyncOperation(localElements.head.element, SyncAction.ActionOverride,
      localElements.head.element.level, remoteElements.head.id), Some(changedFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id), Some(localFolder)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a local override operation" in {
    val overriddenFile = deltaElem(createFile(1), ChangeType.Unchanged)
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFile(1, RemoteID, deltaTime = 1), createFolder(2, RemoteID))
    val localElements = List(overriddenFile, localFolder)
    val expectedResults = List(createResult(SyncOperation(remoteElements.head, SyncAction.ActionLocalOverride,
      remoteElements.head.level, localElements.head.element.id), Some(overriddenFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id), Some(localFolder)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a Noop for elements with equal URIs and modified times" in {
    val localFile = deltaElem(createFile(1), ChangeType.Changed)
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFile(1, RemoteID), createFolder(2, RemoteID))
    val localElements = List(localFile, localFolder)
    val expectedResults = List(createResult(SyncOperation(localElements.head.element, SyncAction.ActionNoop,
      localElements.head.element.level, remoteElements.head.id), Some(localFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id), Some(localFolder)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a Noop for elements with equal URIs and modified times with a delta below the threshold" in {
    val ignoreDelta = IgnoreTimeDelta(1.second)
    val localFile = deltaElem(createFile(1), ChangeType.Changed)
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFile(1, RemoteID, deltaTime = 1), createFolder(2, RemoteID))
    val localElements = List(localFile, localFolder)
    val expectedResults = List(createResult(SyncOperation(localElements.head.element, SyncAction.ActionNoop,
      localElements.head.element.level, remoteElements.head.id), Some(localFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id), Some(localFolder)))

    val result = runStage(new SyncStage(ignoreDelta), localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "not override a local file if the delta of modified times is below the threshold" in {
    val ignoreDelta = IgnoreTimeDelta(10.seconds)
    val localFile = deltaElem(createFile(1), ChangeType.Unchanged)
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFile(1, RemoteID, deltaTime = ignoreDelta.deltaSec),
      createFolder(2, RemoteID))
    val localElements = List(localFile, localFolder)
    val expectedResults = List(createResult(SyncOperation(localElements.head.element, SyncAction.ActionNoop,
      localElements.head.element.level, remoteElements.head.id), Some(localFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id), Some(localFolder)))

    val result = runStage(new SyncStage(ignoreDelta), localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "not generate an operation if both elements have been removed" in {
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFolder(2, RemoteID))
    val localElements = List(deltaElem(createFile(1), ChangeType.Removed),
      localFolder)
    val expectedResults = List(createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
      localElements(1).element.level, remoteElements.head.id), Some(localFolder)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a conflict if both elements have been changed" in {
    val changedFile = deltaElem(createFile(1, deltaTime = 2), ChangeType.Changed)
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFile(1, RemoteID, deltaTime = 1), createFolder(2, RemoteID))
    val localElements = List(changedFile, localFolder)
    val expectedResults = List(createConflict(SyncOperation(remoteElements.head, SyncAction.ActionLocalOverride,
      remoteElements.head.level, localElements.head.element.id),
      SyncOperation(localElements.head.element, SyncAction.ActionOverride, localElements.head.element.level,
        remoteElements.head.id), Some(changedFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id), Some(localFolder)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "not generate a conflict if both elements have been changed within an accepted delta" in {
    val remoteFile = createFile(1, RemoteID, deltaTime = 1)
    val changedFile = deltaElem(createFile(1, deltaTime = 10), ChangeType.Changed)
      .copy(lastLocalTime = remoteFile.lastModified.minusSeconds(2))
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(remoteFile, createFolder(2, RemoteID))
    val localElements = List(changedFile, localFolder)
    val expectedResults = List(createResult(SyncOperation(localElements.head.element, SyncAction.ActionOverride,
      localElements.head.element.level, remoteElements.head.id), Some(changedFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id), Some(localFolder)))

    val result = runStage(new SyncStage(IgnoreTimeDelta(2.seconds)), localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a conflict if a remote file was removed and the local one changed" in {
    val changedFile = deltaElem(createFile(1), ChangeType.Changed)
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFolder(2, RemoteID))
    val localElements = List(changedFile, localFolder)
    val expectedResults = List(createConflict(SyncOperation(localElements.head.element, SyncAction.ActionLocalRemove,
      localElements.head.element.level, localElements.head.element.id),
      SyncOperation(localElements.head.element, SyncAction.ActionCreate, localElements.head.element.level,
        localElements.head.element.id), Some(changedFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements.head.id), Some(localFolder)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a conflict if a remote file was changed and the local one removed" in {
    val removedFile = deltaElem(createFile(1), ChangeType.Removed)
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFile(1, RemoteID, deltaTime = 1), createFolder(2, RemoteID))
    val localElements = List(removedFile, localFolder)
    val expectedResults = List(createConflict(SyncOperation(remoteElements.head, SyncAction.ActionLocalCreate,
      remoteElements.head.level, remoteElements.head.id),
      SyncOperation(remoteElements.head, SyncAction.ActionRemove, remoteElements.head.level, remoteElements.head.id),
      Some(removedFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id), Some(localFolder)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "not generate a conflict if a remote file was changed in an accepted delta and the local one removed" in {
    val removedFile = deltaElem(createFile(1), ChangeType.Removed)
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFile(1, RemoteID, deltaTime = 1), createFolder(2, RemoteID))
    val localElements = List(removedFile, localFolder)
    val expectedResults = List(createResult(SyncOperation(remoteElements.head, SyncAction.ActionRemove,
      remoteElements.head.level, remoteElements.head.id), Some(removedFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id), Some(localFolder)))

    val result = runStage(new SyncStage(IgnoreTimeDelta(1.second)), localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a conflict if a remote and a local file with the same path were created" in {
    val createdFile = deltaElem(createFile(1), ChangeType.Created)
    val localFolder = deltaElem(createFolder(2), ChangeType.Unchanged)
    val remoteElements = List(createFile(1, RemoteID, deltaTime = 1), createFolder(2, RemoteID))
    val localElements = List(createdFile, localFolder)
    val expectedResults = List(createConflict(SyncOperation(remoteElements.head, SyncAction.ActionLocalOverride,
      remoteElements.head.level, localElements.head.element.id),
      SyncOperation(localElements.head.element, SyncAction.ActionOverride, localElements.head.element.level,
        remoteElements.head.id), Some(createdFile)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id), Some(localFolder)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "correctly evaluate the change type of local elements if the remote source is complete" in {
    val changedFile = createFile(1)
    val removedFile = createFile(2)
    val unchangedFolder = createFolder(3)
    val deltaChanged = deltaElem(changedFile, ChangeType.Changed)
    val deltaUnchanged = deltaElem(unchangedFolder, ChangeType.Unchanged)
    val localElements = List(deltaChanged, deltaElem(removedFile, ChangeType.Removed), deltaUnchanged)
    val expectedResults = List(createConflict(
      SyncOperation(changedFile, SyncAction.ActionLocalRemove, changedFile.level, changedFile.id),
      SyncOperation(changedFile, SyncAction.ActionCreate, changedFile.level, changedFile.id), Some(deltaChanged)),
      createResult(SyncOperation(unchangedFolder, SyncAction.ActionNoop, unchangedFolder.level,
        unchangedFolder.id), Some(deltaUnchanged)),
      createResult(SyncOperation(unchangedFolder, SyncAction.ActionLocalRemove, unchangedFolder.level,
        unchangedFolder.id, deferred = true), None))

    val result = runStage(new SyncStage, localElements, Nil)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "handle the removal of remote folders" in {
    val removedFolder1 = createFolder(1)
    val removedFolder2 = createFolder(3)
    val removedChild1 = createFile(10, optParent = Some(removedFolder1))
    val removedChild2 = createFolder(11, optParent = Some(removedFolder1))
    val removedChild3 = createFile(30, optParent = Some(removedFolder2))
    val remoteFolder = createFolder(2, RemoteID)
    val localFolder = createFolder(2)
    val remoteChildFile = createFile(20, RemoteID, optParent = Some(remoteFolder))
    val localChildFile = createFile(20, optParent = Some(localFolder))
    val remoteFile = createFile(4, RemoteID)
    val localFile = createFile(4)
    val remoteElements = List(remoteFolder, remoteFile, remoteChildFile)
    val deltaRemovedFolder1 = deltaElem(removedFolder1, ChangeType.Unchanged)
    val deltaLocalFolder = deltaElem(localFolder, ChangeType.Unchanged)
    val deltaRemovedFolder2 = deltaElem(removedFolder2, ChangeType.Unchanged)
    val deltaLocalFile = deltaElem(localFile, ChangeType.Unchanged)
    val deltaRemovedChild1 = deltaElem(removedChild1, ChangeType.Unchanged)
    val deltaRemovedChild2 = deltaElem(removedChild2, ChangeType.Unchanged)
    val deltaLocalChildFile = deltaElem(localChildFile, ChangeType.Unchanged)
    val deltaRemovedChild3 = deltaElem(removedChild3, ChangeType.Unchanged)
    val localElements = List(deltaRemovedFolder1, deltaLocalFolder, deltaRemovedFolder2, deltaLocalFile,
      deltaRemovedChild1, deltaRemovedChild2, deltaLocalChildFile, deltaRemovedChild3)
    val expectedResults = List(createResult(SyncOperation(removedFolder1, SyncAction.ActionNoop,
      removedFolder1.level, removedFolder1.id), Some(deltaRemovedFolder1)),
      createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level, remoteFolder.id),
        Some(deltaLocalFolder)),
      createResult(SyncOperation(removedFolder2, SyncAction.ActionNoop, removedFolder2.level, removedFolder2.id),
        Some(deltaRemovedFolder2)),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id),
        Some(deltaLocalFile)),
      createResult(SyncOperation(removedChild1, SyncAction.ActionNoop, removedChild1.level, removedChild1.id),
        Some(deltaRemovedChild1)),
      createResult(SyncOperation(removedChild2, SyncAction.ActionNoop, removedChild2.level, removedChild2.id),
        Some(deltaRemovedChild2)),
      SyncStage.SyncStageResult(Right(List(SyncOperation(removedChild2, SyncAction.ActionLocalRemove,
        removedChild2.level, removedChild2.id, deferred = true),
        SyncOperation(removedChild1, SyncAction.ActionLocalRemove, removedChild1.level, removedChild1.id,
          deferred = true),
        SyncOperation(removedFolder1, SyncAction.ActionLocalRemove, removedFolder1.level, removedFolder1.id,
          deferred = true))), Some(deltaLocalChildFile)),
      createResult(SyncOperation(localChildFile, SyncAction.ActionNoop, localChildFile.level, remoteChildFile.id),
        Some(deltaLocalChildFile)),
      createResult(SyncOperation(removedChild3, SyncAction.ActionNoop, removedChild3.level, removedChild3.id),
        Some(deltaRemovedChild3)),
      SyncStage.SyncStageResult(Right(List(SyncOperation(removedChild3, SyncAction.ActionLocalRemove,
        removedChild3.level, removedChild3.id, deferred = true),
        SyncOperation(removedFolder2, SyncAction.ActionLocalRemove, removedFolder2.level, removedFolder2.id,
          deferred = true))), None))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "detect a conflict with a removed remote folder and a file changed locally" in {
    val removedFolder = createFolder(1)
    val remoteFile = createFile(2, RemoteID)
    val localFile = createFile(2)
    val unchangedChild = createFile(10, optParent = Some(removedFolder))
    val changedChild = createFile(11, optParent = Some(removedFolder))
    val remoteElements = List(remoteFile)
    val deltaRemovedFolder = deltaElem(removedFolder, ChangeType.Unchanged)
    val deltaLocalFile = deltaElem(localFile, ChangeType.Unchanged)
    val deltaUnchangedFile = deltaElem(unchangedChild, ChangeType.Unchanged)
    val deltaChangedChild = deltaElem(changedChild, ChangeType.Changed)
    val localElements = List(deltaRemovedFolder, deltaLocalFile, deltaUnchangedFile, deltaChangedChild)
    val expectedResults = List(createResult(SyncOperation(removedFolder, SyncAction.ActionNoop, removedFolder.level,
      removedFolder.id), Some(deltaRemovedFolder)),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id),
        Some(deltaLocalFile)),
      createResult(SyncOperation(unchangedChild, SyncAction.ActionNoop, unchangedChild.level, unchangedChild.id),
        Some(deltaUnchangedFile)),
      createResult(SyncOperation(changedChild, SyncAction.ActionNoop, changedChild.level, changedChild.id),
        Some(deltaChangedChild)),
      SyncStage.SyncStageResult(Left(SyncConflictException(localOperations = List(SyncOperation(changedChild, SyncAction.ActionLocalRemove,
        changedChild.level, changedChild.id, deferred = true),
        SyncOperation(unchangedChild, SyncAction.ActionLocalRemove, unchangedChild.level, unchangedChild.id,
          deferred = true),
        SyncOperation(removedFolder, SyncAction.ActionLocalRemove, removedFolder.level, removedFolder.id,
          deferred = true)),
        remoteOperations = List(SyncOperation(changedChild, SyncAction.ActionCreate, changedChild.level,
          changedChild.id)))), None))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "detect a conflict with a removed remote folder and a newly created file locally" in {
    val removedFolder1 = createFolder(1)
    val remoteFolder = createFolder(2, RemoteID)
    val localFolder = createFolder(2)
    val unchangedChild = createFile(10, optParent = Some(removedFolder1))
    val newChild = createFile(11, optParent = Some(removedFolder1))
    val removedFolder2 = createFolder(20, optParent = Some(localFolder))
    val remoteElements = List(remoteFolder)
    val deltaRemovedFolder1 = deltaElem(removedFolder1, ChangeType.Unchanged)
    val deltaLocalFolder = deltaElem(localFolder, ChangeType.Unchanged)
    val deltaUnchangedChild = deltaElem(unchangedChild, ChangeType.Unchanged)
    val deltaNewChild = deltaElem(newChild, ChangeType.Created)
    val deltaRemovedFolder2 = deltaElem(removedFolder2, ChangeType.Created)
    val localElements = List(deltaRemovedFolder1, deltaLocalFolder, deltaUnchangedChild, deltaNewChild,
      deltaRemovedFolder2)
    val expectedResults = List(createResult(SyncOperation(removedFolder1, SyncAction.ActionNoop,
      removedFolder1.level, removedFolder1.id), Some(deltaRemovedFolder1)),
      createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level,
        remoteFolder.id), Some(deltaLocalFolder)),
      createResult(SyncOperation(unchangedChild, SyncAction.ActionNoop, unchangedChild.level, unchangedChild.id),
        Some(deltaUnchangedChild)),
      createResult(SyncOperation(newChild, SyncAction.ActionNoop, newChild.level, newChild.id),
        Some(deltaNewChild)),
      SyncStage.SyncStageResult(Left(SyncConflictException(localOperations = List(SyncOperation(newChild,
        SyncAction.ActionLocalRemove, newChild.level, newChild.id, deferred = true),
        SyncOperation(unchangedChild, SyncAction.ActionLocalRemove, unchangedChild.level, unchangedChild.id,
          deferred = true),
        SyncOperation(removedFolder1, SyncAction.ActionLocalRemove, removedFolder1.level, removedFolder1.id,
          deferred = true)),
        remoteOperations = List(SyncOperation(newChild, SyncAction.ActionCreate, newChild.level,
          newChild.id)))), Some(deltaRemovedFolder2)),
      createResult(SyncOperation(removedFolder2, SyncAction.ActionCreate, removedFolder2.level,
        removedFolder2.id), Some(deltaRemovedFolder2)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "handle the removal of local folders" in {
    val removedFolder1 = createFolder(1, RemoteID)
    val removedFolder2 = createFolder(2, RemoteID)
    val remainingFolder = createFolder(3, RemoteID)
    val removedChild1 = createFile(10, RemoteID, optParent = Some(removedFolder1))
    val removedChild2 = createFile(20, RemoteID, optParent = Some(removedFolder2))
    val remainingChild = createFile(30, RemoteID, optParent = Some(remainingFolder))
    val localRemovedFolder1 = createFolder(1)
    val localRemovedFolder2 = createFolder(2)
    val localRemainingFolder = createFolder(3)
    val localRemovedChild1 = createFile(10, optParent = Some(localRemovedFolder1))
    val localRemovedChild2 = createFile(20, optParent = Some(localRemovedFolder2))
    val localRemainingChild = createFile(30, optParent = Some(localRemainingFolder))

    val remoteElements = List(removedFolder1, removedFolder2, remainingFolder, removedChild1, removedChild2,
      remainingChild)
    val deltaRemainingFolder = deltaElem(localRemainingFolder, ChangeType.Unchanged)
    val deltaLocalRemovedChild2 = deltaElem(localRemovedChild2, ChangeType.Removed)
    val deltaLocalRemainingChild = deltaElem(localRemainingChild, ChangeType.Unchanged)
    val localElements = List(deltaElem(localRemovedFolder1, ChangeType.Removed),
      deltaElem(localRemovedFolder2, ChangeType.Removed),
      deltaRemainingFolder,
      deltaElem(localRemovedChild1, ChangeType.Removed),
      deltaLocalRemovedChild2,
      deltaLocalRemainingChild)
    val expectedResults = List(createResult(SyncOperation(localRemainingFolder, SyncAction.ActionNoop,
      localRemainingFolder.level, remainingFolder.id), Some(deltaRemainingFolder)),
      SyncStage.SyncStageResult(Right(List(SyncOperation(removedChild1, SyncAction.ActionRemove, removedChild1.level,
        removedChild1.id, deferred = true),
        SyncOperation(removedFolder1, SyncAction.ActionRemove, removedFolder1.level, removedFolder1.id,
          deferred = true))), Some(deltaLocalRemainingChild)),
      SyncStage.SyncStageResult(Right(List(SyncOperation(removedChild2, SyncAction.ActionRemove, removedChild2.level,
        removedChild2.id, deferred = true),
        SyncOperation(removedFolder2, SyncAction.ActionRemove, removedFolder2.level, removedFolder2.id,
          deferred = true))), Some(deltaLocalRemainingChild)),
      createResult(SyncOperation(localRemainingChild, SyncAction.ActionNoop, localRemainingChild.level,
        remainingChild.id), Some(deltaLocalRemainingChild)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "detect a conflict with a removed local folder and a changed remote file" in {
    val removedFolder = createFolder(1, RemoteID)
    val removedChild = createFile(10, RemoteID, optParent = Some(removedFolder))
    val conflictChild = createFile(11, RemoteID, deltaTime = 1, optParent = Some(removedFolder))
    val removedLocalFolder = createFolder(1)
    val removedLocalChild = createFile(10, optParent = Some(removedLocalFolder))
    val conflictLocalChild = createFile(11, optParent = Some(removedLocalFolder))

    val remoteElements = List(removedFolder, removedChild, conflictChild)
    val localElements = List(deltaElem(removedLocalFolder, ChangeType.Removed),
      deltaElem(removedLocalChild, ChangeType.Removed),
      deltaElem(conflictLocalChild, ChangeType.Removed))
    val expectedResults = List(SyncStage.SyncStageResult(
      Left(SyncConflictException(localOperations = List(SyncOperation(conflictChild,
        SyncAction.ActionLocalCreate, conflictChild.level, conflictLocalChild.id)),
        remoteOperations = List(SyncOperation(conflictChild, SyncAction.ActionRemove, conflictChild.level,
          conflictChild.id, deferred = true),
          SyncOperation(removedChild, SyncAction.ActionRemove, removedChild.level, removedChild.id, deferred = true),
          SyncOperation(removedFolder, SyncAction.ActionRemove, removedFolder.level, removedFolder.id,
            deferred = true)))), None))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "detect a conflict with a removed local folder and a newly created remote element" in {
    val removedFolder = createFolder(1, RemoteID)
    val newElement = createFolder(10, optParent = Some(removedFolder))
    val removedChild = createFile(11, RemoteID, optParent = Some(removedFolder))
    val removedLocalFolder = createFolder(1)
    val removedLocalChild = createFile(11, optParent = Some(removedLocalFolder))

    val remoteElements = List(removedFolder, newElement, removedChild)
    val localElements = List(deltaElem(removedLocalFolder, ChangeType.Removed),
      deltaElem(removedLocalChild, ChangeType.Removed))
    val expectedResults = List(SyncStage.SyncStageResult(
      Left(SyncConflictException(localOperations = List(SyncOperation(newElement,
        SyncAction.ActionLocalCreate, newElement.level, newElement.id)),
        remoteOperations = List(SyncOperation(removedChild, SyncAction.ActionRemove, removedChild.level,
          removedChild.id, deferred = true),
          SyncOperation(newElement, SyncAction.ActionRemove, newElement.level, newElement.id, deferred = true),
          SyncOperation(removedFolder, SyncAction.ActionRemove, removedFolder.level, removedFolder.id,
            deferred = true)))), None))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "detect a conflict with a removed local folder and a newly created remote element at the stream end" in {
    val removedFolder = createFolder(1, RemoteID)
    val removedChild = createFile(10, RemoteID, optParent = Some(removedFolder))
    val newElement = createFolder(11, optParent = Some(removedFolder))
    val removedLocalFolder = createFolder(1)
    val removedLocalChild = createFile(10, optParent = Some(removedLocalFolder))

    val remoteElements = List(removedFolder, removedChild, newElement)
    val localElements = List(deltaElem(removedLocalFolder, ChangeType.Removed),
      deltaElem(removedLocalChild, ChangeType.Removed))
    val expectedResults = List(SyncStage.SyncStageResult(Left(SyncConflictException(localOperations = List(SyncOperation(newElement,
      SyncAction.ActionLocalCreate, newElement.level, newElement.id)),
      remoteOperations = List(SyncOperation(newElement, SyncAction.ActionRemove, newElement.level, newElement.id,
        deferred = true),
        SyncOperation(removedChild, SyncAction.ActionRemove, removedChild.level, removedChild.id, deferred = true),
        SyncOperation(removedFolder, SyncAction.ActionRemove, removedFolder.level, removedFolder.id,
          deferred = true)))), None))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "handle a removed remote element in a local folder removed operation" in {
    val removedFolder = createFolder(1, RemoteID)
    val remoteFolder = createFolder(2, RemoteID)
    val remoteChild = createFile(20, RemoteID, optParent = Some(remoteFolder))
    val removedLocalFolder = createFolder(1)
    val removedLocalChild = createFile(10, optParent = Some(removedLocalFolder))
    val localFolder = createFolder(2)
    val localChild = createFile(20, optParent = Some(localFolder))

    val remoteElements = List(removedFolder, remoteFolder, remoteChild)
    val deltaLocalFolder = deltaElem(localFolder, ChangeType.Unchanged)
    val deltaRemovedLocalChild = deltaElem(removedLocalChild, ChangeType.Removed)
    val deltaLocalChild = deltaElem(localChild, ChangeType.Unchanged)
    val localElements = List(deltaElem(removedLocalFolder, ChangeType.Removed), deltaLocalFolder,
      deltaRemovedLocalChild, deltaLocalChild)
    val expectedResults = List(createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level,
      remoteFolder.id), Some(deltaLocalFolder)),
      SyncStage.SyncStageResult(Right(List(SyncOperation(removedFolder, SyncAction.ActionRemove, removedFolder.level,
        removedFolder.id, deferred = true))), Some(deltaLocalChild)),
      createResult(SyncOperation(localChild, SyncAction.ActionNoop, localChild.level, remoteChild.id),
        Some(deltaLocalChild)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "handle a remote folder that was replaced by a file" in {
    val replacementFile = createFile(1, RemoteID)
    val remoteFolder = createFolder(2, RemoteID)
    val remoteFile = createFile(20, RemoteID, optParent = Some(remoteFolder))
    val replacedFolder = createFolder(1)
    val child = createFile(10, optParent = Some(replacedFolder))
    val localFolder = createFolder(2)
    val localFile = createFile(20, optParent = Some(localFolder))

    val remoteElements = List(replacementFile, remoteFolder, remoteFile)
    val deltaReplacedFolder = deltaElem(replacedFolder, ChangeType.Unchanged)
    val deltaLocalFolder = deltaElem(localFolder, ChangeType.Unchanged)
    val deltaChild = deltaElem(child, ChangeType.Unchanged)
    val deltaLocalFile = deltaElem(localFile, ChangeType.Unchanged)
    val localElements = List(deltaReplacedFolder, deltaLocalFolder, deltaChild, deltaLocalFile)
    val expectedResults = List(createResult(SyncOperation(replacedFolder, SyncAction.ActionNoop,
      replacedFolder.level, replacedFolder.id), Some(deltaReplacedFolder)),
      createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level, remoteFolder.id),
        Some(deltaLocalFolder)),
      createResult(SyncOperation(child, SyncAction.ActionNoop, child.level, child.id), Some(deltaChild)),
      SyncStage.SyncStageResult(Right(List(SyncOperation(child, SyncAction.ActionLocalRemove, child.level, child.id,
        deferred = true),
        SyncOperation(replacedFolder, SyncAction.ActionLocalRemove, replacedFolder.level, replacedFolder.id,
          deferred = true),
        SyncOperation(replacementFile, SyncAction.ActionLocalCreate, replacementFile.level, replacementFile.id,
          deferred = true))), Some(deltaLocalFile)),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id),
        Some(deltaLocalFile)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "handle a remote file that was replaced by a folder" in {
    val replacementFolder = createFolder(1, RemoteID)
    val remoteFolder = createFolder(2, RemoteID)
    val remoteFile = createFile(20, RemoteID, optParent = Some(remoteFolder))
    val replacedFile = createFile(1)
    val newFile = createFile(10, RemoteID, optParent = Some(replacementFolder))
    val localFolder = createFolder(2)
    val localFile = createFile(20, optParent = Some(localFolder))

    val remoteElements = List(replacementFolder, remoteFolder, newFile, remoteFile)
    val deltaLocalFolder = deltaElem(localFolder, ChangeType.Unchanged)
    val deltaLocalFile = deltaElem(localFile, ChangeType.Unchanged)
    val deltaReplacedFile = deltaElem(replacedFile, ChangeType.Unchanged)
    val localElements = List(deltaReplacedFile, deltaLocalFolder, deltaLocalFile)
    val expectedResults = List(SyncStage.SyncStageResult(Right(List(SyncOperation(replacedFile,
      SyncAction.ActionLocalRemove, replacedFile.level, replacedFile.id),
      SyncOperation(replacementFolder, SyncAction.ActionLocalCreate, replacementFolder.level,
        replacementFolder.id))), Some(deltaReplacedFile)),
      createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level, remoteFolder.id),
        Some(deltaLocalFolder)),
      createResult(SyncOperation(newFile, SyncAction.ActionLocalCreate, newFile.level, newFile.id), None),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id),
        Some(deltaLocalFile)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "handle a local folder that was replaced by a file" in {
    val replacementFile = createFile(1)
    val localFolder = createFolder(2)
    val localChild = createFile(10, optParent = Some(createFolder(1)))
    val localFile = createFile(20, optParent = Some(localFolder))
    val replacedFolder = createFolder(1, RemoteID)
    val remoteFolder = createFolder(2, RemoteID)
    val remoteFile = createFile(20, RemoteID, optParent = Some(remoteFolder))
    val child = createFile(10, RemoteID, optParent = Some(replacedFolder))

    val remoteElements = List(replacedFolder, remoteFolder, child, remoteFile)
    val deltaLocalFolder = deltaElem(localFolder, ChangeType.Unchanged)
    val deltaLocalFile = deltaElem(localFile, ChangeType.Unchanged)
    val localElements = List(deltaElem(replacementFile, ChangeType.TypeChanged), deltaLocalFolder,
      deltaElem(localChild, ChangeType.Removed), deltaLocalFile)
    val expectedResults = List(createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level,
      remoteFolder.id), Some(deltaLocalFolder)),
      SyncStage.SyncStageResult(Right(List(SyncOperation(child, SyncAction.ActionRemove, child.level, child.id,
        deferred = true),
        SyncOperation(replacedFolder, SyncAction.ActionRemove, replacedFolder.level, replacedFolder.id,
          deferred = true),
        SyncOperation(replacementFile, SyncAction.ActionCreate, replacementFile.level, replacementFile.id,
          deferred = true))), Some(deltaLocalFile)),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id),
        Some(deltaLocalFile)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "handle a local file that was replaced by a folder" in {
    val replacementFolder = createFolder(1)
    val localFolder = createFolder(2)
    val newFile = createFile(10, optParent = Some(replacementFolder))
    val localFile = createFile(20, optParent = Some(localFolder))
    val remoteFolder = createFolder(2, RemoteID)
    val remoteFile = createFile(20, RemoteID, optParent = Some(remoteFolder))
    val replacedFile = createFile(1, RemoteID)

    val remoteElements = List(replacedFile, remoteFolder, remoteFile)
    val deltaLocalFolder = deltaElem(localFolder, ChangeType.Unchanged)
    val deltaNewFile = deltaElem(newFile, ChangeType.Created)
    val deltaLocalFile = deltaElem(localFile, ChangeType.Unchanged)
    val deltaReplacementFolder = ElementWithDelta(replacementFolder, ChangeType.TypeChanged, replacedFile.lastModified)
    val localElements = List(deltaReplacementFolder,
      deltaLocalFolder, deltaNewFile, deltaLocalFile)
    val expectedResults = List(SyncStage.SyncStageResult(Right(List(SyncOperation(replacedFile, SyncAction.ActionRemove,
      replacedFile.level, replacedFile.id),
      SyncOperation(replacementFolder, SyncAction.ActionCreate, replacementFolder.level, replacementFolder.id))),
      Some(deltaReplacementFolder)),
      createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level, remoteFolder.id),
        Some(deltaLocalFolder)),
      createResult(SyncOperation(newFile, SyncAction.ActionCreate, newFile.level, newFile.id), Some(deltaNewFile)),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id),
        Some(deltaLocalFile)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "detect a conflict with a remote file changed to a folder if the local file was removed" in {
    val replacementFolder = createFolder(1, RemoteID)
    val remoteFile = createFile(2, RemoteID)
    val replacedFile = createFile(1)
    val localFile = createFile(2)

    val remoteElements = List(replacementFolder, remoteFile)
    val deltaReplacedFile = deltaElem(replacedFile, ChangeType.Removed)
    val deltaLocalFile = deltaElem(localFile, ChangeType.Unchanged)
    val localElements = List(deltaReplacedFile, deltaLocalFile)
    val expectedResults = List(createConflict(SyncOperation(replacementFolder, SyncAction.ActionLocalCreate,
      replacementFolder.level, replacementFolder.id),
      SyncOperation(replacementFolder, SyncAction.ActionRemove, replacementFolder.level, replacementFolder.id),
      Some(deltaReplacedFile)),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id),
        Some(deltaLocalFile)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  /**
    * Helper function to check whether a conflict is detected if a remote file
    * was converted to a folder and the local file was modified in some way.
    *
    * @param changeType the change type for the local file
    */
  private def checkConflictWithRemoteFileChangedToFolderAndLocalFile(changeType: ChangeType): Unit =
    val replacementFolder = createFolder(1, RemoteID)
    val remoteFile = createFile(2, RemoteID)
    val replacedFile = createFile(1)
    val localFile = createFile(2)

    val remoteElements = List(replacementFolder, remoteFile)
    val deltaLocalFile = deltaElem(localFile, ChangeType.Unchanged)
    val deltaReplacedFile = deltaElem(replacedFile, changeType)
    val localElements = List(deltaReplacedFile, deltaLocalFile)
    val expectedResults = List(SyncStage.SyncStageResult(
      Left(SyncConflictException(remoteOperations = List(SyncOperation(replacementFolder,
        SyncAction.ActionRemove, replacementFolder.level, replacementFolder.id),
        SyncOperation(replacedFile, SyncAction.ActionCreate, replacedFile.level, replacedFile.id)),
        localOperations = List(SyncOperation(replacedFile, SyncAction.ActionLocalRemove, replacedFile.level,
          replacedFile.id),
          SyncOperation(replacementFolder, SyncAction.ActionLocalCreate, replacementFolder.level,
            replacementFolder.id)))), Some(deltaReplacedFile)),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id),
        Some(deltaLocalFile)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults

  it should "detect a conflict with a remote file changed to a folder if the local file was changed" in {
    checkConflictWithRemoteFileChangedToFolderAndLocalFile(ChangeType.Changed)
  }

  it should "detect a conflict with a remote file changed to a folder if the local file was newly created" in {
    checkConflictWithRemoteFileChangedToFolderAndLocalFile(ChangeType.Created)
  }

  it should "detect a conflict with a local file changed to a folder if the remote file was removed" in {
    val replacementFolder = createFolder(1)
    val localFile = createFile(2)
    val remoteFile = createFile(2, RemoteID)

    val remoteElements = List(remoteFile)
    val deltaReplacementFolder = deltaElem(replacementFolder, ChangeType.TypeChanged)
    val deltaLocalFile = deltaElem(localFile, ChangeType.Unchanged)
    val localElements = List(deltaReplacementFolder, deltaLocalFile)
    val expectedResults = List(createConflict(SyncOperation(replacementFolder, SyncAction.ActionLocalRemove,
      replacementFolder.level, replacementFolder.id),
      SyncOperation(replacementFolder, SyncAction.ActionCreate, replacementFolder.level, replacementFolder.id),
      Some(deltaReplacementFolder)),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id),
        Some(deltaLocalFile)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "detect a conflict with a local file changed to a folder if the remote file was changed" in {
    val replacementFolder = createFolder(1)
    val localFolder = createFolder(2)
    val newFile = createFile(10, optParent = Some(replacementFolder))
    val localFile = createFile(20, optParent = Some(localFolder))
    val remoteFolder = createFolder(2, RemoteID)
    val remoteFile = createFile(20, RemoteID, optParent = Some(remoteFolder))
    val replacedFile = createFile(1, RemoteID, deltaTime = 1)

    val remoteElements = List(replacedFile, remoteFolder, remoteFile)
    val deltaLocalFolder = deltaElem(localFolder, ChangeType.Unchanged)
    val deltaNewFile = deltaElem(newFile, ChangeType.Created)
    val deltaLocalFile = deltaElem(localFile, ChangeType.Unchanged)
    val deltaReplacementFolder = deltaElem(replacementFolder, ChangeType.TypeChanged)
    val localElements = List(deltaReplacementFolder, deltaLocalFolder, deltaNewFile, deltaLocalFile)
    val expectedResults = List(SyncStage.SyncStageResult(
      Left(SyncConflictException(localOperations = List(SyncOperation(replacementFolder,
        SyncAction.ActionLocalRemove, replacementFolder.level, replacementFolder.id),
        SyncOperation(replacedFile, SyncAction.ActionLocalCreate, replacedFile.level, replacedFile.id)),
        remoteOperations = List(SyncOperation(replacedFile, SyncAction.ActionRemove, replacedFile.level,
          replacedFile.id),
          SyncOperation(replacementFolder, SyncAction.ActionCreate, replacementFolder.level, replacementFolder.id)))),
      Some(deltaReplacementFolder)),
      createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level, remoteFolder.id),
        Some(deltaLocalFolder)),
      createResult(SyncOperation(newFile, SyncAction.ActionCreate, newFile.level, newFile.id), Some(deltaNewFile)),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id),
        Some(deltaLocalFile)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }
  