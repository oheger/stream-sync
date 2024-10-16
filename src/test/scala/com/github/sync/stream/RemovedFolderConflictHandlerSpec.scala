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

import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.sync.SyncTypes.{FsElement, FsFolder, SyncAction, SyncConflictException, SyncElementResult, SyncOperation}
import com.github.sync.stream.BaseMergeStage.MergeEmitData
import com.github.sync.stream.RemovedFolderConflictHandler.ConflictFunc
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object RemovedFolderConflictHandlerSpec:
  /**
    * A data class simulating state of a sync stage.
    *
    * @param data    simulated data
    * @param handler the conflict handler handler
    */
  private case class TestSyncState(data: Int, handler: RemovedFolderConflictHandler[TestSyncState])

  /**
    * The function to update the handler in the current sync state.
    */
  private val stateUpdateFunc: RemovedFolderConflictHandler.StateUpdateFunc[TestSyncState] =
    (state, handler) => state.copy(state.data + 1, handler)

  /**
    * An update function that throws an exception. This can be used if no
    * update of the state is expected.
    */
  private val noUpdateFunc: RemovedFolderConflictHandler.StateUpdateFunc[TestSyncState] =
    (_, _) => throw UnsupportedOperationException("Unexpected invocation")

  /**
    * Creates a test handler instance.
    *
    * @param folderState  the initial folder state
    * @param operations   the initial map with remove operations
    * @param updateFunc   the function to update the folder state
    * @param conflictFunc the function to generate conflict exceptions
    * @param noopDeferred the noopForDeferredElement flag
    * @return the new test instance
    */
  private def createHandler(folderState: RemovedFolderState = RemovedFolderState.Empty,
                            operations: Map[NormalizedFolder,
                              RemovedFolderConflictHandler.RemoveOperationState] = Map.empty,
                            updateFunc: RemovedFolderConflictHandler.StateUpdateFunc[TestSyncState] = stateUpdateFunc,
                            conflictFunc: ConflictFunc = RemovedFolderConflictHandler.RemoteConflictFunc,
                            noopDeferred: Boolean = false):
  RemovedFolderConflictHandler[TestSyncState] =
    RemovedFolderConflictHandler(folderState, operations, SyncAction.ActionRemove, updateFunc, conflictFunc,
      noopDeferred)

  /**
    * The function to produce results emitted by a test handler instance.
    *
    * @param state    the current state
    * @param elements the elements in the result
    * @return the result with this data
    */
  private def resultFunc(state: TestSyncState, elements: List[SyncElementResult]):
  RemovedFolderConflictHandler.HandlerResult[TestSyncState] =
    (MergeEmitData(elements, BaseMergeStage.Pull1), state)

  /**
    * Convenience function to create a ''SyncOperation'' for a given element
    * and action.
    *
    * @param element  the element affected by the operation
    * @param action   the action on the element
    * @param deferred the deferred flag
    * @return the ''SyncOperation''
    */
  private def createOp(element: FsElement, action: SyncAction, deferred: Boolean = false): SyncOperation =
    SyncOperation(element, action, element.level, element.id, deferred)

  /**
    * Convenience function to create a sync result with a single operation on
    * the given element.
    *
    * @param element  the element affected by the operation
    * @param action   the action on the element
    * @param deferred the deferred flag
    * @return the sync result
    */
  private def resultForOp(element: FsElement, action: SyncAction, deferred: Boolean = false): SyncElementResult =
    Right(List(createOp(element, action, deferred)))

  extension (h: RemovedFolderConflictHandler[TestSyncState])

  /**
    * Convenience function to invoke a conflict handler with an element
    * affected by a remove folder operation. In this case, there should be no
    * invocation of the handler function.
    */
    private def handleRemovedElement(state: TestSyncState, element: FsElement, conflict: => List[SyncOperation]):
    RemovedFolderConflictHandler.HandlerResult[TestSyncState] =
      h.handleElement(state, element, resultFunc, conflict) {
        throw UnsupportedOperationException("Unexpected invocation")
      }

  extension (op: SyncOperation)

  /**
    * Converts a ''SyncOperation'' to a deferred one.
    */
    def toDeferred: SyncOperation = op.copy(deferred = true)

/**
  * Test class for ''RemovedFolderConflictHandler''.
  */
class RemovedFolderConflictHandlerSpec extends AnyFlatSpec, Matchers :

  import RemovedFolderConflictHandlerSpec.*

  "RemovedFolderConflictHandler" should "invoke the handler if the element is not affected by a remove operation" in {
    val element = AbstractStageSpec.createFile(1)
    val handler = createHandler(updateFunc = noUpdateFunc)
    val oldState = TestSyncState(1, handler)
    val newState = TestSyncState(2, handler)
    val result = resultForOp(element, SyncAction.ActionOverride)
    val emitData = BaseMergeStage.MergeEmitData(List(result), BaseMergeStage.Pull1)

    val (resEmitData, resState) = handler.handleElement(oldState, element, resultFunc, Nil) {
      (emitData, newState)
    }
    resEmitData should be(emitData)
    resState should be(newState)
  }

  it should "pass a conflict result from the handler to downstream" in {
    val element = AbstractStageSpec.createFile(1)
    val handler = createHandler(updateFunc = noUpdateFunc)
    val oldState = TestSyncState(1, handler)
    val newState = TestSyncState(2, handler)
    val result = Left(SyncConflictException(Nil, Nil))
    val emitData = BaseMergeStage.MergeEmitData(List(result), BaseMergeStage.Pull1)

    val (resEmitData, resState) = handler.handleElement(oldState, element, resultFunc, Nil) {
      (emitData, newState)
    }
    resEmitData should be(emitData)
    resState should be(newState)
  }

  it should "detect and initialize a new root folder remove operation" in {
    val element = AbstractStageSpec.createFolder(1)
    val expOp = createOp(element, SyncAction.ActionRemove, deferred = true)
    val handler = createHandler()
    val result = resultForOp(element, SyncAction.ActionRemove)
    val emitData = BaseMergeStage.MergeEmitData(List(result), BaseMergeStage.Pull1)

    val (resEmitData, resState) = handler.handleElement(TestSyncState(0, null), element, resultFunc, Nil) {
      (emitData, TestSyncState(1, null))
    }
    resEmitData.elements shouldBe empty
    resEmitData.pullInlets should be(emitData.pullInlets)

    resState.data should be(2)
    resState.handler.folderState.roots should contain(element.toNormalizedFolder)
    val operationState = resState.handler.operations(element.toNormalizedFolder)
    operationState.deferredOperations should contain only expOp
    operationState.lastFolder should be(element.toNormalizedFolder)
  }

  it should "detect and initialize a new root folder remove operation and issue a noop action" in {
    val element = AbstractStageSpec.createFolder(1)
    val expOp = createOp(element, SyncAction.ActionRemove, deferred = true)
    val expNoop = createOp(element, SyncAction.ActionNoop)
    val handler = createHandler(noopDeferred = true)
    val result = resultForOp(element, SyncAction.ActionRemove)
    val emitData = BaseMergeStage.MergeEmitData(List(result), BaseMergeStage.Pull1)

    val (resEmitData, resState) = handler.handleElement(TestSyncState(0, null), element, resultFunc, Nil) {
      (emitData, TestSyncState(1, null))
    }
    resEmitData.elements should contain only Right(List(expNoop))
    resEmitData.pullInlets should be(emitData.pullInlets)

    resState.data should be(2)
    resState.handler.folderState.roots should contain(element.toNormalizedFolder)
    val operationState = resState.handler.operations(element.toNormalizedFolder)
    operationState.deferredOperations should contain only expOp
    operationState.lastFolder should be(element.toNormalizedFolder)
  }

  it should "detect and initialize multiple root folder remove operations in a handler result" in {
    val root0 = AbstractStageSpec.createFolder(100)
    val root1 = AbstractStageSpec.createFolder(1)
    val root2 = AbstractStageSpec.createFolder(2)
    val removeOp1 = createOp(root1, SyncAction.ActionRemove)
    val removeOp2 = createOp(root2, SyncAction.ActionRemove)
    val otherOp = createOp(AbstractStageSpec.createFile(10), SyncAction.ActionNoop)
    val result: SyncElementResult = Right(List(removeOp1, otherOp, removeOp2))
    val oldState = TestSyncState(0, null)
    val emitData = BaseMergeStage.MergeEmitData(List(result), BaseMergeStage.Pull2)
    val initFolderState = RemovedFolderState.Empty.addRoot(root0)
    val handler = createHandler(folderState = initFolderState)

    val (resEmitData, resState) = handler.handleElement(oldState, root1, resultFunc, Nil) {
      (emitData, oldState)
    }
    resEmitData.elements should contain only Right(List(otherOp))
    resEmitData.pullInlets should be(emitData.pullInlets)

    val resHandler = resState.handler
    resHandler.folderState.roots should have size 3
    List(root0, root1, root2) foreach { folder =>
      resHandler.folderState.roots should contain(folder.toNormalizedFolder)
    }
    resHandler.operations(root1.toNormalizedFolder).deferredOperations should contain only removeOp1.toDeferred
    resHandler.operations(root2.toNormalizedFolder).deferredOperations should contain only removeOp2.toDeferred
  }

  it should "detect an element affected by a remove folder operation" in {
    val removedFolder = AbstractStageSpec.createFolder(1).toNormalizedFolder
    val removeFolderOp = createOp(removedFolder.folder, SyncAction.ActionRemove)
    val removedChild = AbstractStageSpec.createFile(2, optParent = Some(removedFolder.folder))
    val expOp = createOp(removedChild, SyncAction.ActionRemove, deferred = true)
    val initFolderState = RemovedFolderState.Empty.addRoot(removedFolder)
    val removeOpState = RemovedFolderConflictHandler.RemoveOperationState(List(removeFolderOp), Nil, removedFolder)
    val handler = createHandler(folderState = initFolderState,
      operations = Map(removedFolder -> removeOpState))

    val (resEmitData, resState) = handler.handleRemovedElement(TestSyncState(0, null), removedChild, Nil)
    resEmitData.elements shouldBe empty
    resEmitData.pullInlets should be(BaseMergeStage.Pull1)
    resState.handler.operations(removedFolder)
      .deferredOperations should contain theSameElementsInOrderAs List(expOp, removeFolderOp)
  }

  it should "detect an element affected by a remove folder operation and issue a noop action" in {
    val removedFolder = AbstractStageSpec.createFolder(1).toNormalizedFolder
    val removeFolderOp = createOp(removedFolder.folder, SyncAction.ActionRemove)
    val removedChild = AbstractStageSpec.createFile(2, optParent = Some(removedFolder.folder))
    val expNoop = createOp(removedChild, SyncAction.ActionNoop)
    val expOp = createOp(removedChild, SyncAction.ActionRemove, deferred = true)
    val initFolderState = RemovedFolderState.Empty.addRoot(removedFolder)
    val removeOpState = RemovedFolderConflictHandler.RemoveOperationState(List(removeFolderOp), Nil, removedFolder)
    val handler = createHandler(folderState = initFolderState, noopDeferred = true,
      operations = Map(removedFolder -> removeOpState))

    val (resEmitData, resState) = handler.handleRemovedElement(TestSyncState(0, null), removedChild, Nil)
    resEmitData.elements should contain only Right(List(expNoop))
    resEmitData.pullInlets should be(BaseMergeStage.Pull1)
    resState.handler.operations(removedFolder)
      .deferredOperations should contain theSameElementsInOrderAs List(expOp, removeFolderOp)
  }

  it should "record the latest subfolder in a remove folder operation" in {
    val rootFolder = AbstractStageSpec.createFolder(1).toNormalizedFolder
    val removeFolderOp = createOp(rootFolder.folder, SyncAction.ActionRemove)
    val subFolder = AbstractStageSpec.createFolder(20, optParent = Some(rootFolder.folder))
    val deepFolder = AbstractStageSpec.createFolder(index = 30, optParent = Some(subFolder)).toNormalizedFolder
    val initFolderState = RemovedFolderState.Empty.addRoot(rootFolder)
    val removeOpState = RemovedFolderConflictHandler.RemoveOperationState(List(removeFolderOp), Nil, rootFolder)
    val handler = createHandler(folderState = initFolderState,
      operations = Map(rootFolder -> removeOpState))

    val (_, resState1) = handler.handleRemovedElement(TestSyncState(0, null), subFolder, Nil)
    val (_, resState2) = handler.handleRemovedElement(resState1, deepFolder.folder, Nil)
    val operationState = resState2.handler.operations(rootFolder)
    operationState.lastFolder should be(deepFolder)
  }

  it should "yield the results of a remove folder operation when it is complete" in {
    val rootFolder1 = AbstractStageSpec.createFolder(1).toNormalizedFolder
    val rootFolder2 = AbstractStageSpec.createFolder(2).toNormalizedFolder
    val laterElement = AbstractStageSpec.createFile(100,
      optParent = Some(AbstractStageSpec.createFolder(3)))
    val rootFolder3 = AbstractStageSpec.createFolder(4).toNormalizedFolder
    val removeOpState1 = RemovedFolderConflictHandler.RemoveOperationState(lastFolder = rootFolder1,
      deferredOperations = List(createOp(AbstractStageSpec.createFile(10), SyncAction.ActionRemove),
        createOp(AbstractStageSpec.createFile(11), SyncAction.ActionRemove)), conflictOperations = Nil)
    val removedOpState2 = RemovedFolderConflictHandler.RemoveOperationState(lastFolder = rootFolder2,
      deferredOperations = List(createOp(AbstractStageSpec.createFolder(20), SyncAction.ActionRemove)),
      conflictOperations = Nil)
    val removedOpState3 = RemovedFolderConflictHandler.RemoveOperationState(lastFolder = rootFolder3,
      deferredOperations = List(createOp(AbstractStageSpec.createFolder(30), SyncAction.ActionRemove)),
      conflictOperations = Nil)
    val initFolderState = RemovedFolderState(Set(rootFolder1, rootFolder2, rootFolder3), Nil)
    val operations = Map(rootFolder1 -> removeOpState1, rootFolder2 -> removedOpState2,
      rootFolder3 -> removedOpState3)
    val orgResult = (BaseMergeStage.MergeEmitData(List(resultForOp(laterElement, SyncAction.ActionNoop)),
      BaseMergeStage.PullBoth), TestSyncState(1, null))
    val handler = createHandler(folderState = initFolderState, operations = operations)

    val (resEmitData, resState) = handler.handleElement(TestSyncState(0, null), laterElement, resultFunc,
      Nil)(orgResult)
    resEmitData.elements should contain theSameElementsAs List(Right(removeOpState1.deferredOperations),
      Right(removedOpState2.deferredOperations), orgResult._1.elements.head)
    resEmitData.elements.last should be(orgResult._1.elements.head)
    resState.handler.operations.keys should contain only rootFolder3
    resState.handler.folderState.roots should contain only rootFolder3
  }

  it should "yield the results of all ongoing remove folder operations when the stage is complete" in {
    val rootFolder1 = AbstractStageSpec.createFolder(1).toNormalizedFolder
    val rootFolder2 = AbstractStageSpec.createFolder(2).toNormalizedFolder
    val removeOpState1 = RemovedFolderConflictHandler.RemoveOperationState(lastFolder = rootFolder1,
      deferredOperations = List(createOp(AbstractStageSpec.createFile(10), SyncAction.ActionRemove),
        createOp(AbstractStageSpec.createFile(11), SyncAction.ActionRemove)), conflictOperations = Nil)
    val removedOpState2 = RemovedFolderConflictHandler.RemoveOperationState(lastFolder = rootFolder2,
      deferredOperations = List(createOp(AbstractStageSpec.createFolder(20), SyncAction.ActionRemove)),
      conflictOperations = Nil)
    val initFolderState = RemovedFolderState(Set(rootFolder1, rootFolder2), Nil)
    val operations = Map(rootFolder1 -> removeOpState1, rootFolder2 -> removedOpState2)
    val orgResult = (BaseMergeStage.MergeEmitData(Nil, BaseMergeStage.PullBoth, complete = true),
      TestSyncState(1, null))
    val handler = createHandler(folderState = initFolderState, operations = operations)

    val results = handler.remainingResults()
    results should contain theSameElementsAs List(Right(removeOpState1.deferredOperations),
      Right(removedOpState2.deferredOperations))
  }

  it should "return an empty list of remaining results if no operations are ongoing" in {
    val handler = createHandler()

    handler.remainingResults() shouldBe empty
  }

  it should "detect a conflict during a remove operation" in {
    val rootFolder = AbstractStageSpec.createFolder(1).toNormalizedFolder
    val removeFolderOp = createOp(rootFolder.folder, SyncAction.ActionRemove)
    val conflictOpEx = createOp(AbstractStageSpec.createFile(11), SyncAction.ActionLocalOverride)
    val removeOpState = RemovedFolderConflictHandler.RemoveOperationState(lastFolder = rootFolder,
      deferredOperations = List(removeFolderOp), conflictOperations = List(conflictOpEx))
    val element = AbstractStageSpec.createFile(20, optParent = Some(rootFolder.folder))
    val expOp = createOp(element, SyncAction.ActionRemove, deferred = true)
    val conflictOp = createOp(element, SyncAction.ActionLocalCreate)
    val initFolderState = RemovedFolderState(Set(rootFolder), Nil)
    val operations = Map(rootFolder -> removeOpState)
    val handler = createHandler(folderState = initFolderState, operations = operations)

    val (resEmitData, resState) = handler.handleRemovedElement(TestSyncState(0, null), element, List(conflictOp))
    resEmitData.elements shouldBe empty
    resEmitData.pullInlets should be(BaseMergeStage.Pull1)
    val resOpState = resState.handler.operations(rootFolder)
    resOpState.deferredOperations should contain theSameElementsInOrderAs List(expOp, removeFolderOp)
    resOpState.conflictOperations should contain only(conflictOp, conflictOpEx)
  }

  it should "return a conflict result for a remove operation if conflicting operations were found" in {
    val deferredOps = List(createOp(AbstractStageSpec.createFile(1), SyncAction.ActionLocalCreate),
      createOp(AbstractStageSpec.createFolder(3), SyncAction.ActionLocalRemove))
    val conflictOps = List(createOp(AbstractStageSpec.createFolder(3), SyncAction.ActionCreate),
      createOp(AbstractStageSpec.createFile(1), SyncAction.ActionOverride))
    val expConflict = SyncConflictException(localOperations = conflictOps, remoteOperations = deferredOps)
    val rootFolder = AbstractStageSpec.createFolder(0).toNormalizedFolder
    val element = AbstractStageSpec.createFolder(20, optParent = Some(AbstractStageSpec.createFolder(2)))
    val removeOpState = RemovedFolderConflictHandler.RemoveOperationState(deferredOperations = deferredOps,
      conflictOperations = conflictOps, lastFolder = rootFolder)
    val initFolderState = RemovedFolderState(Set(rootFolder), Nil)
    val operations = Map(rootFolder -> removeOpState)
    val handler = createHandler(folderState = initFolderState, operations = operations)

    val (resEmitData, _) = handler.handleElement(TestSyncState(0, null), element, resultFunc, Nil) {
      (BaseMergeStage.MergeEmitData(Nil, BaseMergeStage.Pull2), TestSyncState(1, null))
    }
    resEmitData.elements should contain only Left(expConflict)
  }

  it should "return a conflict as remaining result if conflicting operations were found" in {
    val deferredOps = List(createOp(AbstractStageSpec.createFile(1), SyncAction.ActionLocalCreate),
      createOp(AbstractStageSpec.createFolder(3), SyncAction.ActionLocalRemove))
    val conflictOps = List(createOp(AbstractStageSpec.createFolder(3), SyncAction.ActionCreate),
      createOp(AbstractStageSpec.createFile(1), SyncAction.ActionOverride))
    val expConflict = SyncConflictException(remoteOperations = conflictOps, localOperations = deferredOps)
    val rootFolder = AbstractStageSpec.createFolder(0).toNormalizedFolder
    val element = AbstractStageSpec.createFolder(20, optParent = Some(AbstractStageSpec.createFolder(2)))
    val removeOpState = RemovedFolderConflictHandler.RemoveOperationState(deferredOperations = deferredOps,
      conflictOperations = conflictOps, lastFolder = rootFolder)
    val initFolderState = RemovedFolderState(Set(rootFolder), Nil)
    val operations = Map(rootFolder -> removeOpState)
    val handler = createHandler(folderState = initFolderState, operations = operations,
      conflictFunc = RemovedFolderConflictHandler.LocalConflictFunc)

    val result = handler.remainingResults()
    result should contain only Left(expConflict)
  }

  it should "return the same result if there are no completed remove folder operations" in {
    val orgResult = (BaseMergeStage.MergeEmitData(List(resultForOp(AbstractStageSpec.createFile(1),
      SyncAction.ActionCreate)), BaseMergeStage.Pull1), TestSyncState(12, null))
    val handler = createHandler()

    val next = handler.addCompletedResults(AbstractStageSpec.createFolder(2), orgResult)
    next should be theSameInstanceAs orgResult
  }

  it should "update a result with completed remove folder operations" in {
    val rootFolder1 = AbstractStageSpec.createFolder(1).toNormalizedFolder
    val rootFolder2 = AbstractStageSpec.createFolder(2).toNormalizedFolder
    val rootFolder3 = AbstractStageSpec.createFolder(3).toNormalizedFolder
    val removeOpState1 = RemovedFolderConflictHandler.RemoveOperationState(lastFolder = rootFolder1,
      deferredOperations = List(createOp(AbstractStageSpec.createFile(10), SyncAction.ActionRemove),
        createOp(AbstractStageSpec.createFile(11), SyncAction.ActionRemove)), conflictOperations = Nil)
    val removedOpState2 = RemovedFolderConflictHandler.RemoveOperationState(lastFolder = rootFolder2,
      deferredOperations = List(createOp(AbstractStageSpec.createFolder(20), SyncAction.ActionRemove)),
      conflictOperations = List(createOp(AbstractStageSpec.createFolder(20), SyncAction.ActionLocalCreate)))
    val removedOpState3 = RemovedFolderConflictHandler.RemoveOperationState(lastFolder = rootFolder3,
      deferredOperations = List(createOp(rootFolder3.folder, SyncAction.ActionRemove)), conflictOperations = Nil)
    val initFolderState = RemovedFolderState(Set(rootFolder1, rootFolder2, rootFolder3), Nil)
    val operations = Map(rootFolder1 -> removeOpState1,
      rootFolder2 -> removedOpState2,
      rootFolder3 -> removedOpState3)
    val element = AbstractStageSpec.createFile(30, optParent = Some(rootFolder3.folder))
    val orgResults = List(Right(List(createOp(AbstractStageSpec.createFile(42), SyncAction.ActionLocalCreate))))
    val orgResult = (BaseMergeStage.MergeEmitData(orgResults, BaseMergeStage.PullBoth),
      TestSyncState(1, null))
    val expectedResults = List(Right(removeOpState1.deferredOperations),
      Left(SyncConflictException(localOperations = removedOpState2.conflictOperations,
        remoteOperations = removedOpState2.deferredOperations))) ::: orgResults
    val handler = createHandler(folderState = initFolderState, operations = operations)

    val (resEmit, resState) = handler.addCompletedResults(element, orgResult)
    resEmit.pullInlets should be(orgResult._1.pullInlets)
    resEmit.elements.toList should contain theSameElementsInOrderAs expectedResults
    val resHandler = resState.handler
    resHandler.folderState.roots should contain only rootFolder3
    resHandler.operations.keys should contain only rootFolder3
  }

  it should "detect a folder that was converted to a file" in {
    val orgFolder = AbstractStageSpec.createFolder(1)
    val newFile = AbstractStageSpec.createFile(1)
    val orgSyncOps = List(createOp(orgFolder, SyncAction.ActionRemove),
      createOp(newFile, SyncAction.ActionCreate))
    val orgResult = (BaseMergeStage.MergeEmitData(List(Right(orgSyncOps)), BaseMergeStage.Pull1),
      TestSyncState(1, null))
    val handler = createHandler()

    val (resEmit, resState) = handler.handleElement(TestSyncState(0, null), orgFolder, resultFunc, Nil)(orgResult)
    resEmit.pullInlets should be(orgResult._1.pullInlets)
    resEmit.elements shouldBe empty
    val resHandler = resState.handler
    resHandler.folderState.roots should contain only orgFolder.toNormalizedFolder
    val opState = resHandler.operations(orgFolder.toNormalizedFolder)
    opState.conflictOperations shouldBe empty
    opState.deferredOperations should contain theSameElementsInOrderAs orgSyncOps.map(_.toDeferred)
  }

  it should "detect a folder that was converted to a file and issue a noop action" in {
    val orgFolder = AbstractStageSpec.createFolder(1)
    val newFile = AbstractStageSpec.createFile(1)
    val orgSyncOps = List(createOp(orgFolder, SyncAction.ActionRemove), createOp(newFile, SyncAction.ActionCreate))
    val expNoop = createOp(orgFolder, SyncAction.ActionNoop)
    val orgResult = (BaseMergeStage.MergeEmitData(List(Right(orgSyncOps)), BaseMergeStage.Pull1),
      TestSyncState(1, null))
    val handler = createHandler(noopDeferred = true)

    val (resEmit, resState) = handler.handleElement(TestSyncState(0, null), orgFolder, resultFunc, Nil)(orgResult)
    resEmit.pullInlets should be(orgResult._1.pullInlets)
    resEmit.elements should contain only Right(List(expNoop))
    val resHandler = resState.handler
    resHandler.folderState.roots should contain only orgFolder.toNormalizedFolder
    val opState = resHandler.operations(orgFolder.toNormalizedFolder)
    opState.conflictOperations shouldBe empty
    opState.deferredOperations should contain theSameElementsInOrderAs orgSyncOps.map(_.toDeferred)
  }
