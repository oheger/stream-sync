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

import com.github.sync.SyncTypes.{FsElement, FsFile, SyncAction, SyncConflictException, SyncElementResult, SyncOperation, SyncOperationResult}
import com.github.sync.stream.LocalStateStage.ElementWithDelta
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.io.IOException
import java.time.Instant
import scala.concurrent.duration.*

object LocalStateUpdateStageSpec:
  /** A test file used by test cases. */
  private val TestElement = AbstractStageSpec.createFile(111)

  /**
    * Generates a test sync operation based on the given properties.
    *
    * @param action   the action
    * @param element  the element affected
    * @param deferred the deferred flag
    * @return the test sync operation
    */
  private def testOperation(action: SyncAction, element: FsElement = TestElement, deferred: Boolean = false):
  SyncOperation =
    SyncOperation(element, action, element.level, element.id, deferred)

  /**
    * Generates a successful sync result that contains only the given
    * operation.
    *
    * @param op the operation
    * @return the result containing this operation
    */
  private def syncResult(op: SyncOperation): SyncElementResult = Right(List(op))

  /**
    * Generates a test ''ElementWithDelta'' based on the given properties.
    *
    * @param element    the referenced element
    * @param changeType the change type
    * @return the resulting ''ElementWithDelta''
    */
  private def testElementWithDelta(element: FsElement = TestElement,
                                   changeType: LocalStateStage.ChangeType = LocalStateStage.ChangeType.Changed):
  ElementWithDelta =
    ElementWithDelta(element, changeType, Instant.EPOCH)

  /**
    * Generates a ''Source'' for the given elements that issues its items with
    * a slight delay. This is used to test that input at the ports is processed
    * correctly, even if it arrives in unexpected order.
    *
    * @param items the items to be emitted by the source
    * @tparam T the type of the items
    * @return the source emitting these items with a delay
    */
  private def delayedSource[T](items: List[T]): Source[T, NotUsed] =
    Source(items).delay(50.millis)

/**
  * Test class for ''LocalStateUpdateStage''.
  */
class LocalStateUpdateStageSpec(testSystem: ActorSystem) extends AbstractStageSpec(testSystem) :
  def this() = this(ActorSystem("LocalStateUpdateStageSpec"))

  import LocalStateUpdateStageSpec.*

  "LocalStateUpdateStage" should "directly output elements not affected by operations" in {
    val localElement = testElementWithDelta()
    val result = SyncStage.SyncStageResult(syncResult(testOperation(SyncAction.ActionCreate)), Some(localElement))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, List(result), Nil)
    nextState should contain only LocalState.LocalElementState(TestElement, removed = false)
  }

  it should "handle an unavailable local element in a sync result" in {
    val result = SyncStage.SyncStageResult(syncResult(testOperation(SyncAction.ActionCreate)), None)
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, List(result), Nil)
    nextState shouldBe empty
  }

  it should "not output data for a result containing only deferred operations" in {
    val operations = (1 to 4).map { idx =>
      val element = AbstractStageSpec.createFile(idx)
      testOperation(SyncAction.ActionLocalOverride, element = element, deferred = true)
    }
    val result = SyncStage.SyncStageResult(Right(operations), Some(testElementWithDelta()))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, List(result), Nil)
    nextState shouldBe empty
  }

  it should "output the original element in case of a conflict result with non-deferred operations" in {
    val conflict = SyncConflictException(localOperations = List(testOperation(SyncAction.ActionLocalOverride)),
      remoteOperations = List(testOperation(SyncAction.ActionOverride, AbstractStageSpec.createFolder(2))))
    val result = SyncStage.SyncStageResult(Left(conflict), Some(testElementWithDelta()))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, List(result), Nil)
    nextState should contain only LocalState.LocalElementState(TestElement, removed = false)
  }

  it should "handle an unavailable local element in a conflict result" in {
    val conflict = SyncConflictException(localOperations = List(testOperation(SyncAction.ActionLocalOverride)),
      remoteOperations = List(testOperation(SyncAction.ActionOverride, AbstractStageSpec.createFolder(2))))
    val result = SyncStage.SyncStageResult(Left(conflict), None)
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, List(result), Nil)
    nextState shouldBe empty
  }

  it should "output a modified local element in case of an override operation" in {
    val localElement = AbstractStageSpec.createFile(1)
    val remoteElement = AbstractStageSpec.createFile(1, idType = "remote", deltaTime = 10)
    val operation = testOperation(SyncAction.ActionLocalOverride, remoteElement)
    val opResult = SyncOperationResult(operation, optFailure = None)
    val syncSource = Source(List(SyncStage.SyncStageResult(syncResult(operation),
      Some(testElementWithDelta(localElement)))))
    val opSource = delayedSource(List(opResult))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, syncSource, opSource)
    nextState should contain only LocalState.LocalElementState(remoteElement, removed = false)
  }

  it should "output a modified local element in case of an override operation if elements come in different order" in {
    val localElement = AbstractStageSpec.createFile(1)
    val remoteElement = AbstractStageSpec.createFile(1, idType = "remote", deltaTime = 10)
    val operation = testOperation(SyncAction.ActionLocalOverride, remoteElement)
    val opResult = SyncOperationResult(operation, optFailure = None)
    val syncSource = delayedSource(List(SyncStage.SyncStageResult(syncResult(operation),
      Some(testElementWithDelta(localElement)))))
    val opSource = Source(List(opResult))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, syncSource, opSource)
    nextState should contain only LocalState.LocalElementState(remoteElement, removed = false)
  }

  it should "keep the order of sync results if operation results arrive later" in {
    val localElement1 = AbstractStageSpec.createFile(1)
    val remoteElement = AbstractStageSpec.createFile(1, idType = "remote", deltaTime = 10)
    val operation = testOperation(SyncAction.ActionLocalOverride, remoteElement)
    val opResult = SyncOperationResult(operation, optFailure = None)
    val result1 = SyncStage.SyncStageResult(syncResult(operation), Some(testElementWithDelta(localElement1)))
    val localElement2 = testElementWithDelta()
    val result2 = SyncStage.SyncStageResult(syncResult(testOperation(SyncAction.ActionCreate)), Some(localElement2))
    val syncSource = Source(List(result1, result2))
    val opSource = delayedSource(List(opResult))
    val expState = List(LocalState.LocalElementState(remoteElement, removed = false),
      LocalState.LocalElementState(TestElement, removed = false))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, syncSource, opSource)
    nextState should contain theSameElementsInOrderAs expState
  }

  it should "keep the order of conflict results if operation results arrive later" in {
    val localElement1 = AbstractStageSpec.createFile(1)
    val remoteElement = AbstractStageSpec.createFile(1, idType = "remote", deltaTime = 10)
    val operation = testOperation(SyncAction.ActionLocalOverride, remoteElement)
    val opResult = SyncOperationResult(operation, optFailure = None)
    val result1 = SyncStage.SyncStageResult(syncResult(operation), Some(testElementWithDelta(localElement1)))
    val conflict = SyncConflictException(localOperations = List(testOperation(SyncAction.ActionLocalOverride)),
      remoteOperations = List(testOperation(SyncAction.ActionOverride, AbstractStageSpec.createFolder(2))))
    val result2 = SyncStage.SyncStageResult(Left(conflict), Some(testElementWithDelta()))
    val syncSource = Source(List(result1, result2))
    val opSource = delayedSource(List(opResult))
    val expState = List(LocalState.LocalElementState(remoteElement, removed = false),
      LocalState.LocalElementState(TestElement, removed = false))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, syncSource, opSource)
    nextState should contain theSameElementsInOrderAs expState
  }

  it should "keep the order of results if operation results arrive in different order" in {
    val localElement1 = AbstractStageSpec.createFile(1)
    val remoteElement1 = AbstractStageSpec.createFile(1, idType = "remote", deltaTime = 10)
    val operation1 = testOperation(SyncAction.ActionLocalOverride, remoteElement1)
    val opResult1 = SyncOperationResult(operation1, optFailure = None)
    val result1 = SyncStage.SyncStageResult(syncResult(operation1), Some(testElementWithDelta(localElement1)))
    val localElement2 = AbstractStageSpec.createFile(2)
    val remoteElement2 = AbstractStageSpec.createFile(2, idType = "remote", deltaTime = 20)
    val operation2 = testOperation(SyncAction.ActionLocalOverride, remoteElement2)
    val opResult2 = SyncOperationResult(operation2, optFailure = None)
    val result2 = SyncStage.SyncStageResult(syncResult(operation2), Some(testElementWithDelta(localElement2)))
    val expState = List(LocalState.LocalElementState(remoteElement1, removed = false),
      LocalState.LocalElementState(remoteElement2, removed = false))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, List(result1, result2), List(opResult2, opResult1))
    nextState should contain theSameElementsInOrderAs expState
  }

  it should "remove completed updates from the internal state" in {
    val localElement1 = AbstractStageSpec.createFile(1)
    val remoteElement1 = AbstractStageSpec.createFile(1, idType = "remote", deltaTime = 10)
    val operation1 = testOperation(SyncAction.ActionLocalOverride, remoteElement1)
    val opResult1 = SyncOperationResult(operation1, optFailure = None)
    val result1 = SyncStage.SyncStageResult(syncResult(operation1), Some(testElementWithDelta(localElement1)))
    val localElement2 = AbstractStageSpec.createFile(2)
    val remoteElement2 = AbstractStageSpec.createFile(2, idType = "remote", deltaTime = 20)
    val operation2 = testOperation(SyncAction.ActionLocalOverride, remoteElement2)
    val opResult2 = SyncOperationResult(operation2, optFailure = None)
    val result2 = SyncStage.SyncStageResult(syncResult(operation2), Some(testElementWithDelta(localElement2)))
    val expState = List(LocalState.LocalElementState(remoteElement1, removed = false),
      LocalState.LocalElementState(remoteElement2, removed = false))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, List(result1, result2), List(opResult1, opResult2))
    nextState should contain theSameElementsInOrderAs expState
  }

  it should "remove processed operation results from the internal state" in {
    val localElement = AbstractStageSpec.createFile(1)
    val remoteElement = AbstractStageSpec.createFile(1, idType = "remote", deltaTime = 10)
    val operation = testOperation(SyncAction.ActionLocalOverride, remoteElement)
    val opResult = SyncOperationResult(operation, optFailure = None)
    val result = SyncStage.SyncStageResult(syncResult(operation), Some(testElementWithDelta(localElement)))
    val syncSource = delayedSource(List(result, result))
    val opSource = Source(List(opResult))
    val expState = List(LocalState.LocalElementState(remoteElement, removed = false))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, syncSource, opSource)
    nextState should contain theSameElementsAs expState
  }

  it should "correctly handle a failed sync operation" in {
    val localElement = AbstractStageSpec.createFile(1)
    val remoteElement = AbstractStageSpec.createFile(1, idType = "remote", deltaTime = 10)
    val operation = testOperation(SyncAction.ActionLocalOverride, remoteElement)
    val opResult = SyncOperationResult(operation, optFailure = Some(new IOException("Failed")))
    val result = SyncStage.SyncStageResult(syncResult(operation), Some(testElementWithDelta(localElement)))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, List(result), List(opResult))
    nextState should contain only LocalState.LocalElementState(localElement, removed = false)
  }

  it should "correctly handle a failed sync operation without a local element" in {
    val remoteElement = AbstractStageSpec.createFile(1, idType = "remote", deltaTime = 10)
    val operation = testOperation(SyncAction.ActionLocalCreate, remoteElement)
    val opResult = SyncOperationResult(operation, optFailure = Some(new IOException("Failed")))
    val result = SyncStage.SyncStageResult(syncResult(operation), None)
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, List(result), List(opResult))
    nextState shouldBe empty
  }

  it should "handle an operation to remove a local element" in {
    val localElement = AbstractStageSpec.createFolder(1)
    val operation = testOperation(SyncAction.ActionLocalRemove, localElement)
    val opResult = SyncOperationResult(operation, optFailure = None)
    val result = SyncStage.SyncStageResult(syncResult(operation), Some(testElementWithDelta(localElement)))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, List(result), List(opResult))
    nextState should contain only LocalState.LocalElementState(localElement, removed = true)
  }

  it should "handle an operation to create a local element" in {
    val remoteFile = AbstractStageSpec.createFile(1, idType = "remote")
    val operation = testOperation(SyncAction.ActionLocalCreate, remoteFile)
    val opResult = SyncOperationResult(operation, optFailure = None)
    val result = SyncStage.SyncStageResult(syncResult(operation), None)
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, List(result), List(opResult))
    nextState should contain only LocalState.LocalElementState(remoteFile, removed = false)
  }

  it should "handle an operation that replaces a local folder by a file" in {
    val localFolder = AbstractStageSpec.createFolder(1)
    val remoteFile = AbstractStageSpec.createFile(1, idType = "remote")
    val opDelete = testOperation(SyncAction.ActionLocalRemove, localFolder)
    val opCreate = testOperation(SyncAction.ActionLocalCreate, remoteFile)
    val opDeferred = testOperation(SyncAction.ActionLocalRemove, deferred = true)
    val result = SyncStage.SyncStageResult(Right(List(opDelete, opCreate, opDeferred)),
      Some(testElementWithDelta(localFolder)))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, List(result),
      List(SyncOperationResult(opDelete, optFailure = None), SyncOperationResult(opCreate, optFailure = None)))
    nextState should contain only LocalState.LocalElementState(remoteFile, removed = false)
  }

  it should "remove no longer needed operation results from the internal state" in {
    val localFolder = AbstractStageSpec.createFolder(0)
    val remoteFile = AbstractStageSpec.createFile(1, idType = "remote")
    val opDelete = testOperation(SyncAction.ActionLocalRemove, localFolder)
    val opCreate = testOperation(SyncAction.ActionLocalCreate, remoteFile)
    val opDeferred = testOperation(SyncAction.ActionLocalRemove, deferred = true)
    val result1 = SyncStage.SyncStageResult(Right(List(opDelete, opCreate, opDeferred)),
      Some(testElementWithDelta(localFolder)))
    val result2 = SyncStage.SyncStageResult(syncResult(opDelete), Some(testElementWithDelta(localFolder)))
    val stage = new LocalStateUpdateStage

    val nextState = runStage(stage, delayedSource(List(result1, result2)),
      Source(List(SyncOperationResult(opDelete, optFailure = None), SyncOperationResult(opCreate, optFailure = None))))
    nextState should contain only LocalState.LocalElementState(remoteFile, removed = false)
  }
