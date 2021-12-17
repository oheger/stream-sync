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

import com.github.sync.SyncTypes.{FsElement, FsFolder, SyncAction, SyncConflictException, SyncElementResult, SyncOperation, SyncOperationResult}
import com.github.sync.stream.RemovedFolderConflictHandler.{ConflictFunc, HandlerResult, RemoveOperationState, StateUpdateFunc}

private object RemovedFolderConflictHandler:
  /**
    * A function type to update the current conflict handler in the merge state
    * of the client stage. The handler calls this function if its internal
    * state has changed to save this information in the stage's state.
    *
    * Note: The state itself is an internal type of the stage. Therefore, it is
    * referenced here as a type parameter.
    */
  type StateUpdateFunc[S] = (S, RemovedFolderConflictHandler[S]) => S

  /**
    * Type alias for the result of the handler function. This is equivalent to
    * the ''MergeResult'' type of the stage, but it has to be expressed more
    * explicitly from outside of the stage.
    */
  type HandlerResult[S] = (BaseMergeStage.MergeEmitData[SyncElementResult], S)

  /**
    * Type alias for a function that produces a ''SyncConflictException'' from
    * a list of deferred operations (the first parameter) and conflict
    * operations (the second parameter). The handler is used for both sides of
    * the sync operation and therefore does not know, whether deferred
    * operations are executed on the local or remote side.
    */
  type ConflictFunc = (List[SyncOperation], List[SyncOperation]) => SyncConflictException

  /**
    * A ''ConflictFunc'' that treats deferred operations as operations to
    * execute on the remote side.
    */
  final val RemoteConflictFunc: ConflictFunc =
    (deferred, conflicts) => SyncConflictException(conflicts, deferred)

  /**
    * A ''ConflictFunc'' that treas deferred operations as operations to
    * execute on the local side.
    */
  final val LocalConflictFunc: ConflictFunc =
    (deferred, conflicts) => SyncConflictException(deferred, conflicts)

  /**
    * A data class holding information about the state of a single remove
    * folder operation. The class stores the data required to handle the
    * operation, including conflict detection, deferred operations, etc.
    *
    * @param deferredOperations the operations to defer until the successful
    *                           completion of the remove operation
    * @param conflictOperations operations causing conflicts with the remove
    *                           operation identified so far
    * @param lastFolder         stores the last (sub) folder encountered for
    *                           a root folder to remove; this information is
    *                           used to find out when all elements of this root
    *                           folder has been processed
    */
  case class RemoveOperationState(deferredOperations: List[SyncOperation],
                                  conflictOperations: List[SyncOperation],
                                  lastFolder: NormalizedFolder):
    /**
      * Returns a copy of this state with the properties storing operations
      * updated. A single operation is added to the tracked deferred
      * operations; conflicting operations are updated as well.
      *
      * @param op        the deferred operation to add
      * @param conflicts conflicting operations
      * @return the copy containing this operation
      */
    def addDeferredOperation(op: SyncOperation, conflicts: List[SyncOperation]): RemoveOperationState =
      copy(deferredOperations = op :: deferredOperations,
        conflictOperations = conflicts ::: conflictOperations)

/**
  * A class storing state and providing functionality to deal with sync
  * operations that involve removed folders.
  *
  * For bidirectional sync operations, the removal of folders is surprisingly
  * difficult to handle. The operation cannot simple be executed on the
  * opposite side, but it has to be assured first that there are no conflicts
  * caused by changes on this side. A conflict would be for instance  a changed
  * or new file in the tree spawned by the folder removed on the other side.
  * Such a conflict, however, can not be detected directly, but only becomes
  * apparent when traversing the whole folder structure. (The fact that BFS
  * order is used when iterating over the structures does not help to simplify
  * things; this makes it much harder to determine when the whole content of a
  * folder has been processed.)
  *
  * This class stores a rather complex state to keep track ongoing folder
  * remove operations; this also includes a [[RemovedFolderState]] object. The
  * purpose is to determine whether an element encountered during the sync
  * process is a child of a root folder (directly or indirectly) which has been
  * removed. If so, it needs to be checked whether this element causes some
  * kind of conflict. After processing the whole content of the removed root
  * folder, it can be determine whether the operation can be triggered now,
  * actually emitting the sync operations causing the removal of all elements
  * in that folder. Or, if conflicts were found, a complex conflict exception,
  * containing all involved operations, needs to be constructed.
  *
  * Note that folder remove operations need to be tracked for both the local
  * and the remote side of the sync process separately.
  *
  * @param folderState      the current state of removed folders
  * @param operations       a map with information about operations in progress
  * @param removeActionType the action type for remove operations
  * @param pullInlets       the inlets to pull when emitting an element
  * @param stateUpdate      a function to update the current sync state
  * @param conflictFunc     a function to generate conflict exceptions
  * @tparam S the type of the sync state
  */
private case class RemovedFolderConflictHandler[S](folderState: RemovedFolderState,
                                                   operations: Map[NormalizedFolder, RemoveOperationState],
                                                   removeActionType: SyncAction,
                                                   pullInlets: Set[BaseMergeStage.Input],
                                                   stateUpdate: StateUpdateFunc[S],
                                                   conflictFunc: ConflictFunc):
  /**
    * Handles the given element by performing the actions related to removal of
    * folders if required. This function checks whether the given element is
    * affected by a folder removal operation or is even itself a removed
    * folder. It updates its internal state accordingly.
    *
    * @param state       the state of the client stage
    * @param element     the current element to be handled
    * @param conflictOps a function to check whether the element is in a state
    *                    conflicting with a remove operation; if this is the
    *                    case, the function returns the conflicting operations
    * @param handlerFunc a function to handle elements not affected by a folder
    *                    removal operation
    * @return data to emit and the updated sync state
    */
  def handleElement(state: S, element: FsElement, conflictOps: => List[SyncOperation])
                   (handlerFunc: => HandlerResult[S]): HandlerResult[S] =
    folderState.findRoot(element) match
      case Some(root) =>
        handleElementInRemovedRoot(state, root, element, conflictOps)

      case None =>
        val completelyRemovedFolders = folderState.roots.filter(_.isChildIterationComplete(element))
        val handlerResult = handleNewRemovedFolders(state, handlerFunc)

        if completelyRemovedFolders.isEmpty then handlerResult
        else
          val deferredResults = resultsForDeferredOperations(completelyRemovedFolders)
          val nextFolderState = folderState.copy(roots = folderState.roots -- completelyRemovedFolders)
          val nextOperations = operations -- completelyRemovedFolders
          val result = handlerResult._1.copy(elements = deferredResults ::: handlerResult._1.elements.toList)
          (result, stateUpdate(handlerResult._2, copy(folderState = nextFolderState, operations = nextOperations)))

  /**
    * Returns a list with the current results of all ongoing remove operations.
    * This function is called when the client stage is complete. If there are
    * then still remove operations in progress, their accumulated results can
    * now be emitted.
    *
    * @return a list with the accumulated operations from the remove operations
    *         still in progress
    */
  def remainingResults(): List[SyncElementResult] = resultsForDeferredOperations(folderState.roots)

  /**
    * Handles an element that is in the tree spawned by a removed folder. The
    * state of this remove operation is updated accordingly.
    *
    * @param state       the state of the client stage
    * @param root        the removed root folder
    * @param element     the current element
    * @param conflictOps returns a list with conflicting operations
    * @return the result to return to the client stage
    */
  private def handleElementInRemovedRoot(state: S, root: NormalizedFolder, element: FsElement,
                                         conflictOps: => List[SyncOperation]): HandlerResult[S] =
    val removeOp = SyncOperation(element, removeActionType, element.level, element.id)
    val tempOperationState = operations(root).addDeferredOperation(removeOp, conflictOps)
    val nextOperationState = element match
      case folder: FsFolder => tempOperationState.copy(lastFolder = folder.toNormalizedFolder)
      case _ => tempOperationState
    val nextHandler = copy(operations = operations + (root -> nextOperationState))
    (BaseMergeStage.MergeEmitData(Nil, pullInlets), stateUpdate(state, nextHandler))

  /**
    * Checks whether the result of the handler function contains new removed
    * folders. If so, for each new folder, a [[RemoveOperationState]] object to
    * track its state is created, and the sync state is updated accordingly.
    *
    * @param state         the state of the client stage
    * @param handlerResult the result from the handler function
    * @return the result to return the client stage
    */
  private def handleNewRemovedFolders(state: S, handlerResult: HandlerResult[S]): HandlerResult[S] =
    handlerResult._1.elements match
      case List(Right(syncOperations: List[_])) =>
        val (removedFolders, ops) =
          syncOperations.foldRight((Map.empty[NormalizedFolder, RemoveOperationState], List.empty[SyncOperation])) {
            (syncOp, t) =>
              syncOp match
                case op@SyncOperation(element: FsFolder, action, _, _) if action == removeActionType =>
                  val normFolder = element.toNormalizedFolder
                  val operationState = RemoveOperationState(List(op), Nil, normFolder)
                  (t._1 + (normFolder -> operationState), t._2)
                case op: SyncOperation =>
                  (t._1, op :: t._2)
          }

        if removedFolders.nonEmpty then
          val nextFolderState = removedFolders.values.foldRight(folderState) { (operationState, state) =>
            state.addRoot(operationState.lastFolder)
          }
          val nextOperations = operations ++ removedFolders
          val nextHandler = copy(folderState = nextFolderState, operations = nextOperations)
          val elementsToEmit = if ops.isEmpty then Nil
          else List(Right(ops))
          (handlerResult._1.copy(elements = elementsToEmit), stateUpdate(state, nextHandler))
        else handlerResult

      case _ => handlerResult

  /**
    * Generates a list with ''SyncElementResult'' objects for the ongoing
    * remove folder operations identified by the given collection. This
    * function is called when it is time to actually emit the operations that
    * have been deferred while the remove operation was in progress.
    *
    * @param folders the removed folders
    * @return a list with the results generated from the remove operations
    */
  private def resultsForDeferredOperations(folders: Iterable[NormalizedFolder]): List[SyncElementResult] =
    folders.map { folder =>
      val operationState = operations(folder)
      if operationState.conflictOperations.isEmpty then
        Right(operations(folder).deferredOperations)
      else
        Left(conflictFunc(operationState.deferredOperations, operationState.conflictOperations))
    }.toList
