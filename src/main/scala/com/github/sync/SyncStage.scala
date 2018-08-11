/*
 * Copyright 2018 The Developers Team.
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

package com.github.sync

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FanInShape2, Inlet, Outlet}

object SyncStage {
  /**
    * A function to handle state transitions when an element from upstream
    * is received.
    *
    * The function is passed the old sync state, the element from upstream,
    * and the index of the inlet from which the element was received. It then
    * determines the actions to execute and returns an updated sync state.
    *
    * When the function is invoked with a '''null''' element, this means that
    * the upstream for this port is finished.
    */
  private type SyncFunc = (SyncState, SyncStage, Int, FsElement) => (EmitData, SyncState)

  /**
    * A class with details about elements to be emitted downstream.
    *
    * @param ops        the operations to be pushed downstream
    * @param pullInlets the inlets to be pulled
    * @param complete   flag whether the stream should be completed
    */
  private case class EmitData(ops: collection.immutable.Iterable[SyncOperation],
                              pullInlets: Iterable[Inlet[FsElement]],
                              complete: Boolean = false)

  /**
    * The state of the sync stage.
    *
    * This class holds state information that is required when processing a
    * sync stream.
    *
    * @param currentElem     a current element to be synced
    * @param syncFunc        the current sync function
    * @param finishedSources the number of sources that have been finished
    */
  private case class SyncState(currentElem: FsElement,
                               syncFunc: SyncFunc,
                               finishedSources: Int) {
    /**
      * Creates a new sync state with the specified sync function and directly
      * invokes this function with the updated state and the parameters
      * provided. This is useful when switching to another sync function.
      *
      * @param f       the new sync function
      * @param stage   the sync stage
      * @param portIdx the port index
      * @param element the new element
      * @return the result of the sync function
      */
    def updateAndCallSyncFunction(f: SyncFunc, stage: SyncStage, portIdx: Int, element: FsElement):
    (EmitData, SyncState) =
      f(copy(syncFunc = f), stage, portIdx, element)

    /**
      * Updates the current element of this state instance if necessary. If
      * there is no change in the current element, the state instance is
      * returned without changes.
      *
      * @param element the new current element
      * @return the updated ''SyncState'' instance
      */
    def updateCurrentElement(element: FsElement): SyncState =
      if (currentElem == element) this
      else copy(currentElem = element)
  }

  /** Constant for an ''EmitData'' object that requires no action. */
  private val EmitNothing = EmitData(Nil, Nil)

  /** The index for the inlet with the source structure. */
  private val IdxSource = 0

  /** The index for the inlet with the destination structure. */
  private val IdxDest = 1

  /**
    * Returns the inlet with the given index.
    *
    * @param portIdx the port index
    * @return the inlet with this index
    */
  private def inlet(stage: SyncStage, portIdx: Int): Inlet[FsElement] =
    if (portIdx == IdxSource) stage.inSource else stage.inDest

  /**
    * A sync function to wait until elements from both sources are received.
    * This function is set initially. It switches to another function when for
    * both input elements elements are available or one of the input ports is
    * already finished.
    *
    * @param state   the current sync state
    * @param stage   the stage
    * @param portIdx the port index
    * @param element the new element
    * @return data to emit and the next state
    */
  private def waitForElements(state: SyncState, stage: SyncStage, portIdx: Int,
                              element: FsElement): (EmitData, SyncState) =
    handleNullElementDuringSync(state, stage, portIdx, element) getOrElse {
      if (state.currentElem == null)
        (EmitNothing, state.copy(currentElem = element))
      else state.updateAndCallSyncFunction(syncElements, stage, portIdx, element)
    }

  /**
    * A sync function that is active when both input sources deliver elements.
    * Here the actual sync logic is implemented: the current elements from both
    * inputs are compared, and it is decided which sync operations need to be
    * performed.
    *
    * @param state   the current sync state
    * @param stage   the stage
    * @param portIdx the port index
    * @param element the new element
    * @return data to emit and the next state
    */
  private def syncElements(state: SyncState, stage: SyncStage, portIdx: Int,
                           element: FsElement): (EmitData, SyncState) =
    handleNullElementDuringSync(state, stage, portIdx, element) orElse {
      val (elemSource, elemDest) = extractSyncPair(state, portIdx, element)
      syncOperationForElements(elemSource, elemDest, state, stage)
    } getOrElse emitAndPullBoth(Nil, state, stage)

  /**
    * A sync function that becomes active when the source for the destination
    * structure is finished. All elements that are now encountered have to be
    * created in this structure.
    *
    * @param state   the current sync state
    * @param stage   the stage
    * @param portIdx the port index
    * @param element the new element
    * @return data to emit and the next state
    */
  private def destinationFinished(state: SyncState, stage: SyncStage, portIdx: Int,
                                  element: FsElement): (EmitData, SyncState) =
    handleNullElementOnFinishedSource(state, element) { elem =>
      EmitData(List(SyncOperation(elem, ActionCreate)), stage.PullSource)
    } getOrElse
      (EmitData(List(SyncOperation(element, ActionCreate)), stage.PullSource), state)

  /**
    * A sync function that becomes active when the source for the source
    * structure is finished. Remaining elements in the destination structure
    * now have to be removed.
    *
    * @param state   the current sync state
    * @param stage   the stage
    * @param portIdx the port index
    * @param element the new element
    * @return data to emit and the next state
    */
  private def sourceDirFinished(state: SyncState, stage: SyncStage, portIdx: Int,
                                element: FsElement): (EmitData, SyncState) =
    handleNullElementOnFinishedSource(state, element) { elem =>
      EmitData(List(SyncOperation(elem, ActionRemove)), stage.PullDest)
    } getOrElse
      (EmitData(List(SyncOperation(element, ActionRemove)), stage.PullDest), state)

  /**
    * Compares the given elements from the source and destination inlets and
    * returns an ''Option'' with data how these elements have to be handled.
    * If a sync operation is required for these elements, the resulting option
    * is defined; otherwise, the elements do not require an action, and can be
    * skipped.
    *
    * @param elemSource the element from the source inlet
    * @param elemDest   the element from the destination inlet
    * @param state      the current sync state
    * @param stage      the stage
    * @return an ''Option'' with data how to handle these elements
    */
  private def syncOperationForElements(elemSource: FsElement, elemDest: FsElement,
                                       state: SyncState, stage: SyncStage):
  Option[(EmitData, SyncState)] =
    if (elemSource.relativeUri < elemDest.relativeUri)
      Some((EmitData(List(SyncOperation(elemSource, ActionCreate)), stage.PullSource),
        state.updateCurrentElement(elemDest)))
    else if (elemSource.relativeUri > elemDest.relativeUri)
      Some((EmitData(List(SyncOperation(elemDest, ActionRemove)), stage.PullDest),
        state.updateCurrentElement(elemSource)))
    else (elemSource, elemDest) match {
      case (eSrc: FsFile, eDst: FsFile)
        if eSrc.lastModified != eDst.lastModified || eSrc.size != eDst.size =>
        Some(emitAndPullBoth(List(SyncOperation(eSrc, ActionOverride)), state, stage))
      case _ => None
    }

  /**
    * Creates ''EmitData'' and an updated state in case that both inlets have
    * to be pulled.
    *
    * @param op    a sequence with sync operations
    * @param state the current sync state
    * @param stage the stage
    * @return data to emit and the next state
    */
  private def emitAndPullBoth(op: List[SyncOperation], state: SyncState, stage: SyncStage):
  (EmitData, SyncState) =
    (EmitData(op, stage.PullBoth), state.copy(currentElem = null, syncFunc = waitForElements))

  /**
    * Deals with a null element while in sync mode. This means that one of the
    * input sources is now complete. Depending on the source affected, the
    * state is updated to switch to the correct finished sync function. If the
    * passed in element is not null, result is ''None''.
    *
    * @param state   the current sync state
    * @param stage   the stage
    * @param portIdx the port index
    * @param element the new element
    * @return an ''Option'' with data to change to the next state in case an
    *         input source is finished
    */
  private def handleNullElementDuringSync(state: SyncState, stage: SyncStage, portIdx: Int,
                                          element: FsElement): Option[(EmitData, SyncState)] =
    if (element == null) {
      val stateFunc = if (portIdx == IdxSource) sourceDirFinished _ else destinationFinished _
      Some(state.updateAndCallSyncFunction(stateFunc, stage, portIdx, element))
    } else None

  /**
    * Deals with a null element in a state where one source has been finished.
    * This function handles the cases that the state was newly entered or that
    * the stream can now be completed. If result is an empty option, a non null
    * element was passed that needs to be processed by the caller.
    *
    * @param state    the current sync state
    * @param element  the element to be handled
    * @param emitFunc function to generate emit data for an element
    * @return an option with emit data and the next state that is not empty if
    *         this function could handle the element
    */
  private def handleNullElementOnFinishedSource(state: SyncState, element: FsElement)
                                               (emitFunc: FsElement => EmitData):
  Option[(EmitData, SyncState)] =
    if (element == null) {
      if (state.finishedSources > 0)
        Some(EmitData(Nil, Nil, complete = true), state)
      else {
        val emit = Option(state.currentElem) map emitFunc getOrElse EmitNothing
        Some(emit, state.copy(finishedSources = 1))
      }
    } else None

  /**
    * Extracts the two elements to be compared for the current sync operation.
    * This function returns a tuple with the first element from the source
    * inlet and the second element from the destination inlet.
    *
    * @param state   the current sync state
    * @param portIdx the port index
    * @param element the new element
    * @return a tuple with the elements to be synced
    */
  private def extractSyncPair(state: SyncState, portIdx: Int, element: FsElement):
  (FsElement, FsElement) =
    if (portIdx == IdxDest) (state.currentElem, element)
    else (element, state.currentElem)
}

/**
  * A special stage that generates sync operations for two input sources.
  *
  * This stage expects that the input sources generate streams of ordered
  * ''FsElement'' objects. It then compares the elements from its inputs and
  * produces corresponding ''SyncOperation'' objects as output.
  *
  * By processing the generated ''SyncOperation'' objects, the destination
  * structure can be synced with the source structure.
  */
class SyncStage extends GraphStage[FanInShape2[FsElement, FsElement, SyncOperation]] {
  val out: Outlet[SyncOperation] = Outlet[SyncOperation]("SyncStage.out")
  val inSource: Inlet[FsElement] = Inlet[FsElement]("SyncStage.inSource")
  val inDest: Inlet[FsElement] = Inlet[FsElement]("SyncStage.inDest")

  /** Convenience field to indicate that the source inlet should be pulled. */
  val PullSource: List[Inlet[FsElement]] = List(inSource)

  /** Convenience field to indicate that the dest inlet should be pulled. */
  val PullDest: List[Inlet[FsElement]] = List(inDest)

  /** Convenience field to indicate that both inlets should be pulled. */
  val PullBoth: List[Inlet[FsElement]] = List(inSource, inDest)

  import SyncStage._

  override def shape: FanInShape2[FsElement, FsElement, SyncOperation] =
    new FanInShape2[FsElement, FsElement, SyncOperation](inSource, inDest, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var state = SyncState(null, waitForElements, 0)

      setHandler(inSource, new InHandler {
        override def onPush(): Unit = {
          updateState(IdxSource, grab(inSource))
        }

        override def onUpstreamFinish(): Unit = {
          updateState(IdxSource, null)
        }
      })

      setHandler(inDest, new InHandler {
        override def onPush(): Unit = {
          updateState(IdxDest, grab(inDest))
        }

        override def onUpstreamFinish(): Unit = {
          updateState(IdxDest, null)
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {}
      })

      override def preStart(): Unit = {
        pull(inSource)
        pull(inDest)
      }

      /**
        * Updates the current state on receiving an element.
        *
        * @param portIdx the port index
        * @param element the element (may be '''null''' for completion)
        */
      private def updateState(portIdx: Int, element: FsElement): Unit = {
        val (data, next) = state.syncFunc(state, SyncStage.this, portIdx, element)
        state = next
        if (data.complete) complete(out)
        else {
          emitMultiple(out, data.ops)
          data.pullInlets foreach pull
        }
      }
    }
}
