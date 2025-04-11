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

import com.github.sync.SyncTypes.FsElement
import com.github.sync.stream.BaseMergeStage.Input
import org.apache.pekko.stream.stage.StageLogging
import org.apache.pekko.stream.{Inlet, Outlet}

/**
  * A trait defining common logic for implementations of ''GraphStageLogic''
  * that need to merge two sources of elements.
  *
  * This trait extends its base trait assuming that the merge state contains a
  * current element, which needs to be compared with a counterpart from the
  * other input source. It provides merge functions for typical use cases of
  * such a scenario: to wait until elements from both input sources have been
  * received or to handle the case that one of the input sources is already
  * completed. Subclasses can parametrize these merge functions and use it;
  * they also need to add their own custom comparison logic.
  *
  * @param in1 the inlet for the first input source
  * @param in2 the inlet for the second input source
  * @param out the outlet of this stage
  * @tparam ELEMENT1 the element type of the first input source
  * @tparam ELEMENT2 the element type of the second input source
  */
trait ElementMergeStage[ELEMENT1, ELEMENT2, OUTELEMENT](in1: Inlet[ELEMENT1],
                                                        in2: Inlet[ELEMENT2],
                                                        out: Outlet[OUTELEMENT])
  extends BaseMergeStage[ELEMENT1, ELEMENT2, OUTELEMENT]:
  this: StageLogging =>

  /**
    * A trait defining additional requirements for the merge state class used
    * by a subclass.
    *
    * The state must manage a current element and also needs some update
    * functionality.
    */
  trait ElementMergeState extends BaseMergeState:
    /**
      * Returns a copy of the current state that uses the given merge function.
      *
      * @param f the new merge function
      * @return the state with the updated merge function
      */
    def withMergeFunc(f: MergeFunc): MergeState

    /**
      * Returns the current element of the merge process. This can be
      * ''None'' if no element has been received yet.
      *
      * @return the optional current element of the merge process
      */
    def currentElement: Option[MergeElement]

    /**
      * Returns a copy of the current state - if necessary - that uses the
      * given element as current element. If this state already contains this
      * element, it is returned as is.
      *
      * @param e the optional new current element
      * @return the state with this current element
      */
    def withCurrentElement(e: Option[MergeElement]): MergeState =
      if currentElement == e then this.asInstanceOf[MergeState]
      else updateCurrentElement(e)

    /**
      * Creates a state object with the specified merge function and
      * directly invokes this function with the updated state and the
      * parameters provided. This is useful when switching to another merge
      * function.
      *
      * @param f       the new merge function
      * @param input   defines the current input source
      * @param element the optional new element
      * @return the result of the merge function
      */
    def updateAndCallMergeFunc(f: MergeFunc, input: Input, element: Option[MergeElement]): (EmitData, MergeState) =
      f(withMergeFunc(f), input, element)

    /**
      * Returns a copy of the current state that uses the given element as
      * current element.
      *
      * @param e the optional new current element
      * @return the state with the updated current element
      */
    protected def updateCurrentElement(e: Option[MergeElement]): MergeState
  end ElementMergeState

  override type MergeState <: ElementMergeState

  /**
    * Returns a merge function to wait until elements from both sources are
    * received. The ''MergeFunc'' to switch to when elements from both sources
    * are available is provided; also the functions that handle the case that
    * one of the input sources is completed.
    *
    * @param syncFunc         the function to switch to when elements from both
    *                         input sources are available
    * @param src1CompleteFunc the function to switch to when source 1 is
    *                         complete
    * @param src2CompleteFunc the function to switch to when source 2 is
    *                         complete
    * @return the ''MergeFunc'' that waits for input elements
    */
  protected def waitForElements(syncFunc: => MergeFunc, src1CompleteFunc: => MergeFunc,
                                src2CompleteFunc: => MergeFunc): MergeFunc =
    (state, input, element) =>
      if element.isDefined && state.currentElement.isDefined then
        state.updateAndCallMergeFunc(syncFunc, input, element)
      else if element.isDefined then
        (EmitNothing, state.withCurrentElement(element))
      else
        handleFinishedSource(state, input, src1CompleteFunc, src2CompleteFunc)

  /**
    * Returns a ''MergeFunc'' for the case that one of the input sources is
    * already complete. The function applies the passed ''emitFunc'' on each
    * element to obtain the data to be emitted, until the remaining input
    * source is completed as well.
    *
    * @param emitFunc     function to generate emit data for an element
    * @param completeFunc function to generate the final ''EmitData'' if the
    *                     whole stream is complete
    * @return the ''MergeFunc'' for a completed input source
    */
  protected def inputSourceComplete(emitFunc: (MergeState, MergeElement) => (EmitData, MergeState))
                                   (completeFunc: MergeState => EmitData): MergeFunc =
    (state, _, element) =>
      handleNoneElementOnFinishedSource(state, element)(emitFunc)(completeFunc) getOrElse
        emitFunc(state, element.get)

  /**
    * Deals with a ''None'' element while in sync mode. This means that one of
    * the input sources is now complete. Depending on the source affected,
    * the state is updated to switch to the correct finished merge function.
    * If the passed in element is not ''None'', the passed in function is
    * invoked to compute the result for a pair of elements to merge. Note that
    * this function expects that the current element of the merge state is
    * defined.
    *
    * @param state   the current merge state
    * @param input   defines the current input source
    * @param element the optional new element
    * @param f       a function to handle two defined elements
    * @return an ''Option'' with data to change to the next state in case an
    *         input source is finished
    */
  protected def handleNoneElementDuringSync(state: MergeState, input: Input, element: Option[MergeElement],
                                            src1CompleteFunc: => MergeFunc, src2CompleteFunc: => MergeFunc)
                                           (f: ((MergeElement, MergeElement)) => (EmitData, MergeState)):
  (EmitData, MergeState) =
    extractMergePair(state, input, element) map f getOrElse {
      handleFinishedSource(state, input, src1CompleteFunc, src2CompleteFunc)
    }

  private def handleFinishedSource(state: MergeState, input: Input,
                                   src1CompleteFunc: => MergeFunc, src2CompleteFunc: => MergeFunc):
  (EmitData, MergeState) =
    val mergeFunc = if input == Input.Inlet1 then src1CompleteFunc else src2CompleteFunc
    state.updateAndCallMergeFunc(mergeFunc, input, None)

  /**
    * Deals with a ''None'' element in a state where one source has been
    * finished. This function handles the cases that the state was newly
    * entered or that the stream can now be completed. If result is an empty
    * option, a defined element was passed that needs to be processed by the
    * caller.
    *
    * @param state        the current merge state
    * @param element      the optional element to be handled
    * @param emitFunc     function to generate emit data for an element
    * @param completeFunc function to generate the final ''EmitData'' if the
    *                     whole stream is complete
    * @return an option with emit data and the next state that is not empty if
    *         this function could handle the element
    */
  private def handleNoneElementOnFinishedSource(state: MergeState, element: Option[MergeElement])
                                               (emitFunc: (MergeState, MergeElement) => (EmitData, MergeState))
                                               (completeFunc: MergeState => EmitData): Option[(EmitData, MergeState)] =
    if element.isEmpty then
      state.currentElement.map { current =>
        val (emit, next) = emitFunc(state, current)
        Some((emit, next.withCurrentElement(None)))
      } getOrElse Some {
        if numberOfFinishedSources > 1 then
          (completeFunc(state), state)
        else (EmitNothing, state)
      }
    else None

  /**
    * Extracts the two elements to be compared for the current merge operation.
    * This function returns an optional tuple with the first element from the
    * first inlet and the second element from the second inlet. (As the current
    * element in the state can be from either input source, it is not directly
    * clear from which source the elements stem.) If either of the elements
    * involved is undefined, result is ''None''.
    *
    * @param state   the current merge state
    * @param input   defines the current input source
    * @param element the new element
    * @return an optional tuple with the elements to be merged
    */
  private def extractMergePair(state: MergeState, input: Input, element: Option[MergeElement]):
  Option[(MergeElement, MergeElement)] =
    for
      stateElem <- state.currentElement
      currentElem <- element
    yield
      if input == Input.Inlet2 then (stateElem, currentElem)
      else (currentElem, stateElem)
