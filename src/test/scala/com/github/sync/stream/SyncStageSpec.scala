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

  it should "handle an empty source of remote elements" in {
    val localElements = List(deltaElem(createFile(1), ChangeType.Unchanged),
      deltaElem(createFolder(2), ChangeType.Unchanged),
      deltaElem(createFile(3), ChangeType.Unchanged))
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
