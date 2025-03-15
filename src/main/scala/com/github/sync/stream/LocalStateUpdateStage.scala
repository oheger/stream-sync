/*
 * Copyright 2018-2025 The Developers Team.
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

import com.github.sync.SyncTypes
import com.github.sync.SyncTypes.{FsElement, SyncAction, SyncConflictException, SyncOperation, SyncOperationResult}
import com.github.sync.stream.LocalState.affectsLocalState
import com.github.sync.stream.LocalStateStage.ElementWithDelta
import com.github.sync.stream.LocalStateUpdateStage.{LocalAffectingUpdate, NonLocalAffectingUpdate, StateUpdate, filterNones, hasNonDeferredOperations, updateOperationResults}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FanInShape2, Inlet, Outlet}

private object LocalStateUpdateStage:
  /**
    * Checks whether the given sequence contains at least one non-deferred
    * ''SyncOperation''.
    *
    * @param operations the sequence with operations to check
    * @return '''true''' if there is at least one non-deferred operation;
    *         '''false''' otherwise
    */
  private def hasNonDeferredOperations(operations: Seq[SyncOperation]): Boolean = operations.exists(!_.deferred)

  /**
    * Checks whether the given conflict contains at least one non-deferred
    * ''SyncOperation''.
    *
    * @param conflict the ''SyncConflictException'' to check
    * @return '''true''' if there is at least one non-deferred operation;
    *         '''false''' otherwise
    */
  private def hasNonDeferredOperations(conflict: SyncConflictException): Boolean =
    hasNonDeferredOperations(conflict.localOperations) || hasNonDeferredOperations(conflict.remoteOperations)

  /**
    * Convenience function to filter a list with ''Option'' elements to keep
    * only the values of the defined ones.
    *
    * @param list the list to process
    * @tparam T the type of the element values
    * @return the list containing only the defined values
    */
  private def filterNones[T](list: Iterable[Option[T]]): Iterable[T] =
    list.filter(_.isDefined).map(_.get)

  /**
    * Converts an optional ''ElementWithDelta'' representing an element from
    * the original local state to an element of the updated state.
    *
    * @param optLocalElement the optional original element
    * @return the optional element of the next state
    */
  private def toLocalState(optLocalElement: Option[ElementWithDelta]): Option[LocalState.LocalElementState] =
    optLocalElement map (elem => LocalState.LocalElementState(elem.element, removed = false))

  /**
    * Updates the map with operation results based on the given list of
    * completed operations. All defined completed operations are removed from
    * the map. Then, all operations referencing smaller elements are removed as
    * well. This is necessary for updates that require multiple operations,
    * such as a folder replaced by a file.
    *
    * @param results             the current map with operation results
    * @param completedOperations the completed operations
    * @return the updated map with operations
    */
  private def updateOperationResults(results: Map[SyncOperation, SyncOperationResult],
                                     completedOperations: Iterable[Option[SyncOperation]]):
  Map[SyncOperation, SyncOperationResult] =
    val definedOperations = filterNones(completedOperations)
    val completedMap = results -- definedOperations
    definedOperations.headOption.fold(completedMap) { firstOp =>
      completedMap filterNot (e => SyncTypes.compareElements(e._1.element, firstOp.element) < 0)
    }

  /**
    * A trait representing an update of the local state for a single element.
    * Instances of this trait are stored in [[LocalStateUpdateStage]] for the
    * elements that are currently processed. The purpose of this trait is
    * finding out when all required information is available and then obtain
    * the data to write the next local state. Concrete implementations handle
    * different kinds of state updates.
    */
  private sealed trait StateUpdate:
    /**
      * Checks whether all information for this update is available. The passed
      * in function can be used to query whether a result for a specific
      * operation is already available.
      *
      * @param opFunc the function to check for an operation result
      * @return a flag whether this update is now complete
      */
    def canUpdate(opFunc: SyncOperation => Option[SyncOperationResult]): Boolean

    /**
      * Returns the data to update the local state for the associated element.
      * This function is called if ''canUpdate()'' has returned '''true'''.
      * The passed in function allows querying the result for a specific
      * operation, which may be required to generate the update data. The
      * return value if a tuple with the element to emit downstream and an
      * optional operation this update refers to; this operation is then
      * considered complete and is removed from the set of current operations
      * maintained by [[LocalStateUpdateStage]].
      *
      * @param opFunc the function to query the result for an operation
      * @return data to process this update
      */
    def updateData(opFunc: SyncOperation => SyncOperationResult):
    (Option[LocalState.LocalElementState], Option[SyncOperation])

  /**
    * A specialized [[StateUpdate]] caused by an operation that affects a local
    * element.
    *
    * @param optLocalElement the original local element if available
    * @param operation       the associated operation
    */
  private case class LocalAffectingUpdate(optLocalElement: Option[ElementWithDelta],
                                          operation: SyncOperation) extends StateUpdate :
    override def canUpdate(opFunc: SyncOperation => Option[SyncOperationResult]): Boolean =
      opFunc(operation).isDefined

    override def updateData(opFunc: SyncOperation => SyncOperationResult):
    (Option[LocalState.LocalElementState], Option[SyncOperation]) =
      val result = opFunc(operation)
      val element = if result.optFailure.isEmpty then
        Some(LocalState.LocalElementState(result.op.element,
          removed = result.op.action == SyncAction.ActionLocalRemove))
      else
        toLocalState(optLocalElement)
      (element, Some(operation))

  /**
    * A specialized [[StateUpdate]] caused by an operation that does not affect
    * the local element it is associated with. Hence, the element can be output
    * as soon as all previous operations are complete.
    *
    * @param optLocalElement the local element if available
    */
  private case class NonLocalAffectingUpdate(optLocalElement: Option[ElementWithDelta]) extends StateUpdate :
    override def canUpdate(opFunc: SyncOperation => Option[SyncOperationResult]): Boolean = true

    override def updateData(opFunc: SyncOperation => SyncOperationResult):
    (Option[LocalState.LocalElementState], Option[SyncOperation]) =
      (toLocalState(optLocalElement), None)
end LocalStateUpdateStage

/**
  * A ''Stage'' that updates the local state of a sync process.
  *
  * This stage receives input from a [[SyncStage]] and from a stage that
  * applies sync operations. Based on this information, it produces a stream of
  * elements that represent the updated state of a sync process - i.e. a
  * snapshot of the elements available locally after the process has finished.
  *
  * The applied sync operations need to be taken into account, since the new
  * local state depends on their success: depending on the type of sync
  * actions, local elements have been manipulated or not.
  *
  * It is important that the new local elements are written in the correct
  * order. However, the incoming sync operations may not necessarily be in this
  * order.
  */
private class LocalStateUpdateStage
  extends GraphStage[FanInShape2[SyncStage.SyncStageResult, SyncOperationResult, LocalState.LocalElementState]] :
  val out: Outlet[LocalState.LocalElementState] = Outlet[LocalState.LocalElementState]("LocalStateUpdateStage.out")
  val inSync: Inlet[SyncStage.SyncStageResult] = Inlet[SyncStage.SyncStageResult]("LocalStateUpdateStage.inSync")
  val inOps: Inlet[SyncOperationResult] = Inlet[SyncOperationResult]("LocalStateUpdateStage.inOps")

  override def shape: FanInShape2[SyncStage.SyncStageResult, SyncOperationResult, LocalState.LocalElementState] =
    new FanInShape2[SyncStage.SyncStageResult, SyncOperationResult, LocalState.LocalElementState](inSync, inOps, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    /** Stores the results of operations that are currently processed. */
    private var operationResults = Map.empty[SyncOperation, SyncOperationResult]

    /** Stores the updates that are currently processed. */
    private var currentUpdates = Vector.empty[StateUpdate]

    /** Flag whether the source with sync results is complete. */
    private var syncResultsComplete = false

    /** Flag whether the source with sync operations is complete. */
    private var operationsComplete = false

    setHandler(inSync, new InHandler {
      override def onPush(): Unit =
        handleSyncResult(grab(inSync))
        pull(inSync)

      override def onUpstreamFinish(): Unit =
        syncResultsComplete = true
        sourceCompleted()
    })

    setHandler(inOps, new InHandler {
      override def onPush(): Unit =
        val opResult = grab(inOps)
        if opResult.op.affectsLocalState then
          operationResults += opResult.op -> opResult
          emitCompletedOperations()
        pull(inOps)

      override def onUpstreamFinish(): Unit =
        operationsComplete = true
        sourceCompleted()
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {}
    })

    override def preStart(): Unit =
      pull(inSync)
      pull(inOps)

    /**
      * Handles a sync result. If this result requires an update, it is
      * processed in the correct order.
      *
      * @param syncResult the current sync result
      */
    private def handleSyncResult(syncResult: SyncStage.SyncStageResult): Unit =
      updateForResult(syncResult) foreach { update =>
        currentUpdates = currentUpdates :+ update
        emitCompletedOperations()
      }

    /**
      * Returns a [[StateUpdate]] object for the given sync result. If this
      * result does not require any update, ''None'' is returned.
      *
      * @param syncResult the current sync result
      * @return an ''Option'' with the update for this result
      */
    private def updateForResult(syncResult: SyncStage.SyncStageResult): Option[StateUpdate] =
      syncResult.elementResult match
        case Right(operations) =>
          operations.filterNot(_.deferred).lastOption match
            case Some(op) =>
              if op.affectsLocalState then
                Some(LocalAffectingUpdate(syncResult.optLocalElement, op))
              else
                Some(NonLocalAffectingUpdate(syncResult.optLocalElement))
            case _ => None

        case Left(conflict) if hasNonDeferredOperations(conflict) =>
          Some(NonLocalAffectingUpdate(syncResult.optLocalElement))

        case _ => None

    /**
      * Checks whether all input sources have been completed. If so, completes
      * the whole stage.
      */
    private def sourceCompleted(): Unit =
      if syncResultsComplete && operationsComplete then
        completeStage()

    /**
      * Generates the output for the sync operations that have already been
      * completed.
      */
    private def emitCompletedOperations(): Unit =
      val (complete, incomplete) = currentUpdates span (_.canUpdate(operationResults.get))
      val (emitData, operations) = complete.map { update =>
        update.updateData(operationResults.apply)
      }.unzip

      emitMultiple(out, filterNones(emitData).toVector)
      currentUpdates = incomplete
      operationResults = updateOperationResults(operationResults, operations)
  }
