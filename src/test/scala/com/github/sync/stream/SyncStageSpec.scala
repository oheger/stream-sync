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

package com.github.sync.stream

import akka.actor.ActorSystem
import akka.stream.ClosedShape
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.testkit.TestKit
import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.{FsElement, SyncAction, SyncConflictException, SyncElementResult, SyncOperation}
import com.github.sync.stream.LocalStateStage.ChangeType
import com.github.sync.stream.LocalStateStage.ElementWithDelta
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

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
    * @param op the operation
    * @return the result
    */
  private def createResult(op: SyncOperation): SyncElementResult = Right(List(op))

  /**
    * Creates a conflict result for two conflicting sync operations.
    *
    * @param localOp  the local operation
    * @param remoteOp the remote operation
    * @return the conflict result
    */
  private def createConflict(localOp: SyncOperation, remoteOp: SyncOperation): SyncElementResult =
    val conflictException = SyncConflictException(List(localOp), List(remoteOp))
    Left(conflictException)

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
      createResult(op)
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
      createResult(op)
    }

    val result = runStage(new SyncStage, localElements, Nil)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "handle a stream that does not yield changes" in {
    val remoteElements = List(createFolder(1), createFile(2), createFolder(3))
    val localElements = remoteElements map { elem =>
      deltaElem(elem, ChangeType.Unchanged)
    }
    val expectedResults = remoteElements map { elem =>
      val op = SyncOperation(elem, SyncAction.ActionNoop, elem.level, elem.id)
      createResult(op)
    }

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a local create operation" in {
    val remoteElements = List(createFile(1, RemoteID), createFile(2, RemoteID))
    val localElements = List(deltaElem(createFile(2), ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(remoteElements.head, SyncAction.ActionLocalCreate,
      remoteElements.head.level, remoteElements.head.id)),
      createResult(SyncOperation(localElements.head.element, SyncAction.ActionNoop,
        localElements.head.element.level, remoteElements(1).id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a remote create operation" in {
    val remoteElements = List(createFolder(2, RemoteID))
    val localElements = List(deltaElem(createFolder(1), ChangeType.Created),
      deltaElem(createFolder(2), ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(localElements.head.element, SyncAction.ActionCreate,
      localElements.head.element.level, localElements.head.element.id)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements.head.id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a remote delete operation" in {
    val remoteElements = List(createFile(1, RemoteID), createFolder(2, RemoteID))
    val localElements = List(deltaElem(createFile(1), ChangeType.Removed),
      deltaElem(createFolder(2), ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(remoteElements.head, SyncAction.ActionRemove,
      remoteElements.head.level, remoteElements.head.id)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a local delete operation" in {
    val remoteElements = List(createFolder(2, RemoteID))
    val localElements = List(deltaElem(createFile(1), ChangeType.Unchanged),
      deltaElem(createFolder(2), ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(localElements.head.element, SyncAction.ActionLocalRemove,
      localElements.head.element.level, localElements.head.element.id)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements.head.id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a remote override operation" in {
    val remoteElements = List(createFile(1, RemoteID), createFolder(2, RemoteID))
    val localElements = List(ElementWithDelta(createFile(1, deltaTime = 1), ChangeType.Changed,
      remoteElements.head.modifiedTime(null)),
      deltaElem(createFolder(2), ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(localElements.head.element, SyncAction.ActionOverride,
      localElements.head.element.level, remoteElements.head.id)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a local override operation" in {
    val remoteElements = List(createFile(1, RemoteID, deltaTime = 1), createFolder(2, RemoteID))
    val localElements = List(deltaElem(createFile(1), ChangeType.Unchanged),
      deltaElem(createFolder(2), ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(remoteElements.head, SyncAction.ActionLocalOverride,
      remoteElements.head.level, localElements.head.element.id)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a Noop for elements with equal URIs and modified times" in {
    val remoteElements = List(createFile(1, RemoteID), createFolder(2, RemoteID))
    val localElements = List(deltaElem(createFile(1), ChangeType.Changed),
      deltaElem(createFolder(2), ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(localElements.head.element, SyncAction.ActionNoop,
      localElements.head.element.level, remoteElements.head.id)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "not generate an operation if both elements have been removed" in {
    val remoteElements = List(createFolder(2, RemoteID))
    val localElements = List(deltaElem(createFile(1), ChangeType.Removed),
      deltaElem(createFolder(2), ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
      localElements(1).element.level, remoteElements.head.id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a conflict if both elements have been changed" in {
    val remoteElements = List(createFile(1, RemoteID, deltaTime = 1), createFolder(2, RemoteID))
    val localElements = List(deltaElem(createFile(1, deltaTime = 2), ChangeType.Changed),
      deltaElem(createFolder(2), ChangeType.Unchanged))
    val expectedResults = List(createConflict(SyncOperation(remoteElements.head, SyncAction.ActionLocalOverride,
      remoteElements.head.level, localElements.head.element.id),
      SyncOperation(localElements.head.element, SyncAction.ActionOverride, localElements.head.element.level,
        remoteElements.head.id)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a conflict if a remote file was removed and the local one changed" in {
    val remoteElements = List(createFolder(2, RemoteID))
    val localElements = List(deltaElem(createFile(1), ChangeType.Changed),
      deltaElem(createFolder(2), ChangeType.Unchanged))
    val expectedResults = List(createConflict(SyncOperation(localElements.head.element, SyncAction.ActionLocalRemove,
      localElements.head.element.level, localElements.head.element.id),
      SyncOperation(localElements.head.element, SyncAction.ActionCreate, localElements.head.element.level,
        localElements.head.element.id)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements.head.id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a conflict if a remote file was changed and the local one removed" in {
    val remoteElements = List(createFile(1, RemoteID, deltaTime = 1), createFolder(2, RemoteID))
    val localElements = List(deltaElem(createFile(1), ChangeType.Removed),
      deltaElem(createFolder(2), ChangeType.Unchanged))
    val expectedResults = List(createConflict(SyncOperation(remoteElements.head, SyncAction.ActionLocalOverride,
      remoteElements.head.level, localElements.head.element.id),
      SyncOperation(remoteElements.head, SyncAction.ActionRemove, remoteElements.head.level, remoteElements.head.id)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "generate a conflict if a remote and a local file with the same path were created" in {
    val remoteElements = List(createFile(1, RemoteID, deltaTime = 1), createFolder(2, RemoteID))
    val localElements = List(deltaElem(createFile(1), ChangeType.Created),
      deltaElem(createFolder(2), ChangeType.Unchanged))
    val expectedResults = List(createConflict(SyncOperation(remoteElements.head, SyncAction.ActionLocalOverride,
      remoteElements.head.level, localElements.head.element.id),
      SyncOperation(localElements.head.element, SyncAction.ActionOverride, localElements.head.element.level,
        remoteElements.head.id)),
      createResult(SyncOperation(localElements(1).element, SyncAction.ActionNoop,
        localElements(1).element.level, remoteElements(1).id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }

  it should "correctly evaluate the change type of local elements if the remote source is complete" in {
    val changedFile = createFile(1)
    val removedFile = createFile(2)
    val unchangedFolder = createFolder(3)
    val localElements = List(deltaElem(changedFile, ChangeType.Changed),
      deltaElem(removedFile, ChangeType.Removed),
      deltaElem(unchangedFolder, ChangeType.Unchanged))
    val expectedResults = List(createConflict(
      SyncOperation(changedFile, SyncAction.ActionLocalRemove, changedFile.level, changedFile.id),
      SyncOperation(changedFile, SyncAction.ActionCreate, changedFile.level, changedFile.id)),
      createResult(SyncOperation(unchangedFolder, SyncAction.ActionLocalRemove, unchangedFolder.level,
        unchangedFolder.id)))

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
    val localElements = List(deltaElem(removedFolder1, ChangeType.Unchanged),
      deltaElem(localFolder, ChangeType.Unchanged),
      deltaElem(removedFolder2, ChangeType.Unchanged),
      deltaElem(localFile, ChangeType.Unchanged),
      deltaElem(removedChild1, ChangeType.Unchanged),
      deltaElem(removedChild2, ChangeType.Unchanged),
      deltaElem(localChildFile, ChangeType.Unchanged),
      deltaElem(removedChild3, ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level,
      remoteFolder.id)),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id)),
      Right(List(SyncOperation(removedChild2, SyncAction.ActionLocalRemove, removedChild2.level, removedChild2.id),
        SyncOperation(removedChild1, SyncAction.ActionLocalRemove, removedChild1.level, removedChild1.id),
        SyncOperation(removedFolder1, SyncAction.ActionLocalRemove, removedFolder1.level, removedFolder1.id))),
      createResult(SyncOperation(localChildFile, SyncAction.ActionNoop, localChildFile.level, remoteChildFile.id)),
      Right(List(SyncOperation(removedChild3, SyncAction.ActionLocalRemove, removedChild3.level, removedChild3.id),
        SyncOperation(removedFolder2, SyncAction.ActionLocalRemove, removedFolder2.level, removedFolder2.id))))

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
    val localElements = List(deltaElem(removedFolder, ChangeType.Unchanged),
      deltaElem(localFile, ChangeType.Unchanged),
      deltaElem(unchangedChild, ChangeType.Unchanged),
      deltaElem(changedChild, ChangeType.Changed))
    val expectedResults = List(createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level,
      remoteFile.id)),
      Left(SyncConflictException(localOperations = List(SyncOperation(changedChild, SyncAction.ActionLocalRemove,
        changedChild.level, changedChild.id),
        SyncOperation(unchangedChild, SyncAction.ActionLocalRemove, unchangedChild.level, unchangedChild.id),
        SyncOperation(removedFolder, SyncAction.ActionLocalRemove, removedFolder.level, removedFolder.id)),
        remoteOperations = List(SyncOperation(changedChild, SyncAction.ActionCreate, changedChild.level,
          changedChild.id)))))

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
    val localElements = List(deltaElem(removedFolder1, ChangeType.Unchanged),
      deltaElem(localFolder, ChangeType.Unchanged),
      deltaElem(unchangedChild, ChangeType.Unchanged),
      deltaElem(newChild, ChangeType.Created),
      deltaElem(removedFolder2, ChangeType.Created))
    val expectedResults = List(createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level,
      remoteFolder.id)),
      Left(SyncConflictException(localOperations = List(SyncOperation(newChild, SyncAction.ActionLocalRemove,
        newChild.level, newChild.id),
        SyncOperation(unchangedChild, SyncAction.ActionLocalRemove, unchangedChild.level, unchangedChild.id),
        SyncOperation(removedFolder1, SyncAction.ActionLocalRemove, removedFolder1.level, removedFolder1.id)),
        remoteOperations = List(SyncOperation(newChild, SyncAction.ActionCreate, newChild.level,
          newChild.id)))),
      createResult(SyncOperation(removedFolder2, SyncAction.ActionCreate, removedFolder2.level,
        removedFolder2.id)))

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
    val localElements = List(deltaElem(localRemovedFolder1, ChangeType.Removed),
      deltaElem(localRemovedFolder2, ChangeType.Removed),
      deltaElem(localRemainingFolder, ChangeType.Unchanged),
      deltaElem(localRemovedChild1, ChangeType.Removed),
      deltaElem(localRemovedChild2, ChangeType.Removed),
      deltaElem(localRemainingChild, ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(localRemainingFolder, SyncAction.ActionNoop,
      localRemainingFolder.level, remainingFolder.id)),
      Right(List(SyncOperation(removedChild1, SyncAction.ActionRemove, removedChild1.level, removedChild1.id),
        SyncOperation(removedFolder1, SyncAction.ActionRemove, removedFolder1.level, removedFolder1.id))),
      Right(List(SyncOperation(removedChild2, SyncAction.ActionRemove, removedChild2.level, removedChild2.id),
        SyncOperation(removedFolder2, SyncAction.ActionRemove, removedFolder2.level, removedFolder2.id))),
      createResult(SyncOperation(localRemainingChild, SyncAction.ActionNoop, localRemainingChild.level,
        remainingChild.id)))

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
    val expectedResults = List(Left(SyncConflictException(localOperations = List(SyncOperation(conflictChild,
      SyncAction.ActionLocalCreate, conflictChild.level, conflictLocalChild.id)),
      remoteOperations = List(SyncOperation(conflictChild, SyncAction.ActionRemove, conflictChild.level,
        conflictChild.id), SyncOperation(removedChild, SyncAction.ActionRemove, removedChild.level, removedChild.id),
        SyncOperation(removedFolder, SyncAction.ActionRemove, removedFolder.level, removedFolder.id)))))

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
    val expectedResults = List(Left(SyncConflictException(localOperations = List(SyncOperation(newElement,
      SyncAction.ActionLocalCreate, newElement.level, newElement.id)),
      remoteOperations = List(SyncOperation(removedChild, SyncAction.ActionRemove, removedChild.level,
        removedChild.id),
        SyncOperation(newElement, SyncAction.ActionRemove, newElement.level, newElement.id),
        SyncOperation(removedFolder, SyncAction.ActionRemove, removedFolder.level, removedFolder.id)))))

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
    val expectedResults = List(Left(SyncConflictException(localOperations = List(SyncOperation(newElement,
      SyncAction.ActionLocalCreate, newElement.level, newElement.id)),
      remoteOperations = List(SyncOperation(newElement, SyncAction.ActionRemove, newElement.level, newElement.id),
        SyncOperation(removedChild, SyncAction.ActionRemove, removedChild.level, removedChild.id),
        SyncOperation(removedFolder, SyncAction.ActionRemove, removedFolder.level, removedFolder.id)))))

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
    val localElements = List(deltaElem(removedLocalFolder, ChangeType.Removed),
      deltaElem(localFolder, ChangeType.Unchanged),
      deltaElem(removedLocalChild, ChangeType.Removed),
      deltaElem(localChild, ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level,
      remoteFolder.id)),
      Right(List(SyncOperation(removedFolder, SyncAction.ActionRemove, removedFolder.level,
        removedFolder.id))),
      createResult(SyncOperation(localChild, SyncAction.ActionNoop, localChild.level, remoteChild.id)))

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
    val localElements = List(deltaElem(replacedFolder, ChangeType.Unchanged),
      deltaElem(localFolder, ChangeType.Unchanged),
      deltaElem(child, ChangeType.Unchanged),
      deltaElem(localFile, ChangeType.Unchanged))
    val expectedResults = List(createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level,
      remoteFolder.id)),
      Right(List(SyncOperation(child, SyncAction.ActionLocalRemove, child.level, child.id),
        SyncOperation(replacedFolder, SyncAction.ActionLocalRemove, replacedFolder.level, replacedFolder.id),
        SyncOperation(replacementFile, SyncAction.ActionLocalCreate, replacementFile.level, replacementFile.id))),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id)))

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
    val localElements = List(deltaElem(replacedFile, ChangeType.Unchanged),
      deltaElem(localFolder, ChangeType.Unchanged),
      deltaElem(localFile, ChangeType.Unchanged))
    val expectedResults = List(Right(List(SyncOperation(replacedFile, SyncAction.ActionLocalRemove,
      replacedFile.level, replacedFile.id),
      SyncOperation(replacementFolder, SyncAction.ActionLocalCreate, replacementFolder.level,
        replacementFolder.id))),
      createResult(SyncOperation(localFolder, SyncAction.ActionNoop, localFolder.level, remoteFolder.id)),
      createResult(SyncOperation(newFile, SyncAction.ActionLocalCreate, newFile.level, newFile.id)),
      createResult(SyncOperation(localFile, SyncAction.ActionNoop, localFile.level, remoteFile.id)))

    val result = runStage(new SyncStage, localElements, remoteElements)
    result should contain theSameElementsInOrderAs expectedResults
  }
