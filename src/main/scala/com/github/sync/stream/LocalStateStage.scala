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
import com.github.cloudfiles.core.http.UriEncodingHelper
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
    extends GraphStageLogic(shape) with BaseMergeStage[FsElement, FsElement, ElementWithDelta](in1, in2, out)
      with StageLogging :
    /**
      * A data class representing the state of this stage class.
      *
      * @param mergeFunc   the active ''MergeFunc''
      * @param currentElem the current element
      */
    case class StageState(override val mergeFunc: MergeFunc,
                          currentElem: FsElement) extends BaseMergeState

    override type MergeState = StageState

    /** Holds the state of this stage. */
    private var stageState = StageState(waitForElements, null)

    override protected def state: MergeState = stageState

    override protected def updateState(newState: MergeState): Unit = stageState = newState

    /**
      * A merge function to wait until elements from both sources are received.
      * This function is set initially. It switches to another function when for
      * both input elements elements are available or one of the input ports is
      * already finished.
      *
      * @param state   the current sync state
      * @param input   defines the current input source
      * @param element the new element
      * @return data to emit and the next state
      */
    private def waitForElements(state: StageState, input: Input, element: FsElement): (EmitData, StageState) =
      handleNullElementDuringSync(state, input, element) getOrElse {
        if state.currentElem == null then
          (EmitNothing, state.copy(currentElem = element))
        else syncElements(state.copy(mergeFunc = syncElements), input, element)
      }

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
    private def syncElements(state: StageState, input: Input, element: FsElement): (EmitData, StageState) =
      handleNullElementDuringSync(state, input, element) getOrElse {
        val (currentElem, stateElem) = currentElementPair(state, input, element)
        val uriDelta = compareElementUris(currentElem, stateElem)
        if uriDelta == 0 then
          deltaToState(state, currentElem, stateElem)
        else if uriDelta > 0 then
          val delta = ElementWithDelta(stateElem, ChangeType.Removed, changeTimeFromElement(stateElem))
          (EmitData(List(delta), BaseMergeStage.Pull2), state.copy(currentElem = currentElem))
        else
          val delta = ElementWithDelta(currentElem, ChangeType.Created, syncTime)
          (EmitData(List(delta), BaseMergeStage.Pull1), state.copy(currentElem = stateElem))
      }

    /**
      * A merge function that becomes active when the source for the local
      * state is finished. All elements that are now encountered have been
      * newly created since the last sync process.
      *
      * @param state   the current sync state
      * @param input   defines the current input source
      * @param element the new element
      * @return data to emit and the next state
      */
    private def localStateFinished(state: StageState, input: Input, element: FsElement): (EmitData, StageState) =
      def handleElement(s: StageState, elem: FsElement): (EmitData, StageState) =
        val delta = ElementWithDelta(elem, ChangeType.Created, syncTime)
        (EmitData(List(delta), BaseMergeStage.Pull1), s)

      handleNullElementOnFinishedSource(state, element)(handleElement) getOrElse
        handleElement(state, element)

    /**
      * A merge function that becomes active when the source for the local
      * file structure is finished. Remaining elements in the local state must
      * have been removed.
      *
      * @param state   the current sync state
      * @param input   defines the current input source
      * @param element the new element
      * @return data to emit and the next state
      */
    private def elementsFinished(state: StageState, input: Input, element: FsElement): (EmitData, StageState) =
      def handleElement(s: StageState, elem: FsElement): (EmitData, StageState) =
        val delta = ElementWithDelta(elem, ChangeType.Removed, changeTimeFromElement(elem))
        (EmitData(List(delta), BaseMergeStage.Pull2), s)

      handleNullElementOnFinishedSource(state, element)(handleElement) getOrElse
        handleElement(state, element)

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
      (EmitData(delta, BaseMergeStage.PullBoth), state.copy(currentElem = null, mergeFunc = waitForElements))

    /**
      * Deals with a null element while in sync mode. This means that one of
      * the input sources is now complete. Depending on the source affected,
      * the state is updated to switch to the correct finished merge function.
      * If the passed in element is not null, result is ''None''.
      *
      * @param state   the current sync state
      * @param input   defines the current input source
      * @param element the new element
      * @return an ''Option'' with data to change to the next state in case an
      *         input source is finished
      */
    private def handleNullElementDuringSync(state: StageState, input: Input, element: FsElement):
    Option[(EmitData, StageState)] =
      if element == null then
        val stateFunc = if input == Input.Inlet1 then elementsFinished _ else localStateFinished _
        Some(stateFunc(state.copy(mergeFunc = stateFunc), input, element))
      else None

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
    private def handleNullElementOnFinishedSource(state: StageState, element: FsElement)
                                                 (emitFunc: (StageState, FsElement) => (EmitData, StageState)):
    Option[(EmitData, StageState)] =
      if element == null then
        Option(state.currentElem).map { currentElem =>
          val (emit, next) = emitFunc(state, currentElem)
          Some((emit, next.copy(currentElem = null)))
        } getOrElse Some {
          if numberOfFinishedSources > 1 then
            (EmitData(Nil, Nil, complete = true), state)
          else (EmitNothing, state)
        }
      else None

    /**
      * Extracts the two elements to be compared to compute the current delta.
      * The element contained in the state can either be from the local file
      * system or from the state. This function makes sure that in the
      * resulting tuple the first element is from the file system and the
      * second one from the state.
      *
      * @param state   the current state
      * @param input   defines the current input source
      * @param element the new element
      * @return a tuple with the elements to be compared
      */
    private def currentElementPair(state: StageState, input: Input, element: FsElement):
    (FsElement, FsElement) =
      if input == Input.Inlet2 then (state.currentElem, element)
      else (element, state.currentElem)

    /**
      * Compares the given elements based on their URIs and returns an integer
      * value determining which one is before the other: a value less than zero
      * means that the first element is before the second element; a value
      * greater than zero means that the second element is before the first
      * element; the value 0 means that the elements are equivalent. This
      * comparison is needed when dealing with elements from two different
      * (ordered) sources, to find out which elements are available in both
      * sources or which are missing in either one.
      *
      * @param elem1 the first element
      * @param elem2 the second element
      * @return the result of the comparison
      */
    private def compareElementUris(elem1: FsElement, elem2: FsElement): Int =
      val (srcParent, srcName) = UriEncodingHelper.splitParent(elem1.relativeUri)
      val (dstParent, dstName) = UriEncodingHelper.splitParent(elem2.relativeUri)
      val deltaParent = srcParent.compareTo(dstParent)
      if deltaParent != 0 then deltaParent
      else srcName.compareTo(dstName)

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
