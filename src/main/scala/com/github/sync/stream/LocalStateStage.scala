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

import akka.stream.{Attributes, FanInShape2, Inlet, Outlet, Shape}
import akka.stream.stage.{GraphStage, GraphStageLogic, StageLogging}
import com.github.sync.SyncTypes
import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder}
import com.github.sync.stream.BaseMergeStage.Input
import com.github.sync.stream.LocalStateStage.{ElementWithDelta, LocalStateStageLogic}

import java.time.Instant

private object LocalStateStage:
  /**
    * An enumeration defining the ways a local element may have changed between
    * two sync operations. This information is required to determine the
    * required bidirectional sync operations or to detect conflicts.
    */
  enum ChangeType:
    case Unchanged
    case Changed
    case Created
    case Removed

  /**
    * A data class representing a local file system element together with its
    * [[ChangeType]]. Instances of this class are used as input for the local
    * side of a bidirectional sync process.
    *
    * @param element       the current element
    * @param changeType    the way this element has changed locally
    * @param lastLocalTime the time of the element recorded from the last run
    */
  case class ElementWithDelta(element: FsElement,
                              changeType: ChangeType,
                              lastLocalTime: Instant)

  /**
    * Extracts the time of the last change from the given element. This time is
    * only defined for files, but not for folders.
    *
    * @param element the element in question
    * @return the time of the last change of this element
    */
  private def changeTimeFromElement(element: FsElement): Instant =
    element match
      case file: FsFile => file.lastModified
      case _ => null

  /**
    * The internal class representing the logic and the state of the local
    * state stage.
    *
    * @param shape    the shape
    * @param in1      the inlet for the first input source
    * @param in2      the inlet for the second input source
    * @param out      the outlet of this stage
    * @param syncTime the time of the sync process
    */
  private class LocalStateStageLogic(shape: Shape,
                                     in1: Inlet[FsElement],
                                     in2: Inlet[FsElement],
                                     out: Outlet[ElementWithDelta],
                                     syncTime: Instant)
    extends GraphStageLogic(shape),
      BaseMergeStage[FsElement, FsElement, ElementWithDelta](in1, in2, out),
      ElementMergeStage[FsElement, FsElement, ElementWithDelta](in1, in2, out),
      StageLogging :
    /**
      * A data class representing the state of this stage class.
      *
      * @param mergeFunc      the active ''MergeFunc''
      * @param currentElement the current element
      */
    case class StageState(override val mergeFunc: MergeFunc,
                          override val currentElement: Option[FsElement]) extends ElementMergeState :
      override def withMergeFunc(f: MergeFunc): StageState = copy(mergeFunc = f)

      protected override def updateCurrentElement(e: Option[MergeElement]): MergeState = copy(currentElement = e)

    override type MergeState = StageState

    /**
      * The function producing the ''EmitData'' object signalling the end of
      * the stream.
      */
    private val EmitDataComplete: MergeState => EmitData = _ => EmitData(Nil, Nil, complete = true)

    /**
      * The merge function to be used when the input source with the local
      * state is complete.
      */
    private val stateCompleteMergeFunc = inputSourceComplete(emitLocalStateComplete)(EmitDataComplete)

    /**
      * The merge function to be used when the input source with the elements
      * is complete.
      */
    private val elementsCompleteMergeFunc = inputSourceComplete(emitElementsComplete)(EmitDataComplete)

    /**
      * The merge function that waits for elements from both input sources.
      * This is also set as the initial merge functiion.
      */
    private val waitMergeFunc = waitForElements(syncElements, elementsCompleteMergeFunc, stateCompleteMergeFunc)

    /** Holds the state of this stage. */
    private var stageState = StageState(waitMergeFunc, None)

    override protected def state: MergeState = stageState

    override protected def updateState(newState: MergeState): Unit = stageState = newState

    /**
      * A merge function that is active when both input sources deliver
      * elements. Here the actual logic is implemented: the current elements
      * from both inputs are compared, and the delta is calculated.
      *
      * @param state   the current sync state
      * @param input   defines the current input source
      * @param element the new element
      * @return data to emit and the next state
      */
    private def syncElements(state: StageState, input: Input, element: Option[FsElement]): (EmitData, StageState) =
      handleNoneElementDuringSync(state, input, element, elementsCompleteMergeFunc,
        stateCompleteMergeFunc) { (currentElem, stateElem) =>
        val uriDelta = SyncTypes.compareElementUris(currentElem, stateElem)
        if uriDelta == 0 then
          deltaToState(state, currentElem, stateElem)
        else if uriDelta > 0 then
          val delta = ElementWithDelta(stateElem, ChangeType.Removed, changeTimeFromElement(stateElem))
          (EmitData(List(delta), BaseMergeStage.Pull2), state.copy(currentElement = Some(currentElem)))
        else
          val delta = ElementWithDelta(currentElem, ChangeType.Created, syncTime)
          (EmitData(List(delta), BaseMergeStage.Pull1), state.copy(currentElement = Some(stateElem)))
      }

    /**
      * An emit function for the case that the input source for the local state
      * is already complete. All elements that are now encountered have been
      * newly created since the last sync process.
      *
      * @param state   the current merge state
      * @param element the new element
      * @return data to emit and the next state
      */
    private def emitLocalStateComplete(state: StageState, element: FsElement): (EmitData, StageState) =
      val delta = ElementWithDelta(element, ChangeType.Created, syncTime)
      (EmitData(List(delta), BaseMergeStage.Pull1), state)

    /**
      * An emit function for the case that the input source for the local
      * elements is already complete. Remaining elements in the local state
      * must have been removed.
      *
      * @param state   the current merge state
      * @param element the new element
      * @return data to emit and the next state
      */
    private def emitElementsComplete(state: StageState, element: FsElement): (EmitData, StageState) =
      val delta = ElementWithDelta(element, ChangeType.Removed, changeTimeFromElement(element))
      (EmitData(List(delta), BaseMergeStage.Pull2), state)

    /**
      * Computes a delta for the current element against its recorded state.
      * This function also takes corner cases into account, such as a folder
      * that has been converted to a file.
      *
      * @param state       the current merge state
      * @param currentElem the current element
      * @param stateElem   the element from the recorded state
      * @return data to emit and the next state
      */
    private def deltaToState(state: StageState, currentElem: FsElement, stateElem: FsElement): (EmitData, StageState) =
      val delta = if currentElem.isInstanceOf[FsFile] && stateElem.isInstanceOf[FsFolder] then
        ElementWithDelta(currentElem, ChangeType.Changed, syncTime)
      else
        val currentTime = changeTimeFromElement(currentElem)
        val stateTime = changeTimeFromElement(stateElem)
        val changeType = if currentTime == stateTime then ChangeType.Unchanged else ChangeType.Changed
        ElementWithDelta(currentElem, changeType, stateTime)

      emitAndPullBoth(List(delta), state)

    /**
      * Creates ''EmitData'' and an updated state in case that both inlets have
      * to be pulled.
      *
      * @param delta a sequence with element deltas
      * @param state the current sync state
      * @return data to emit and the next state
      */
    private def emitAndPullBoth(delta: List[ElementWithDelta], state: StageState): (EmitData, StageState) =
      (EmitData(delta, BaseMergeStage.PullBoth), state.copy(currentElement = None, mergeFunc = waitMergeFunc))

/**
  * A ''Stage'' that merges the elements in the local folder structure against
  * the state that was recorded from the last run.
  *
  * Via this stage it is possible to obtain additional information about the
  * local files and folders, such as whether they have been modified, newly
  * created, or removed since the last sync process. This information is needed
  * for bidirectional sync processes.
  *
  * @param syncTime the time of the current sync process; this is also used as
  *                 last local time for newly created elements
  */
private class LocalStateStage(syncTime: Instant)
  extends GraphStage[FanInShape2[FsElement, FsElement, ElementWithDelta]] :
  val out: Outlet[ElementWithDelta] = Outlet[ElementWithDelta]("LocalStateStage.out")
  val inElements: Inlet[FsElement] = Inlet[FsElement]("LocalStateStage.inElements")
  val inState: Inlet[FsElement] = Inlet[FsElement]("LocalStateStage.inState")

  override def shape: FanInShape2[FsElement, FsElement, ElementWithDelta] =
    new FanInShape2[FsElement, FsElement, ElementWithDelta](inElements, inState, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new LocalStateStageLogic(shape, inElements, inState, out, syncTime)
