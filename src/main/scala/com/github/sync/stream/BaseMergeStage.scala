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

import akka.event.LoggingAdapter
import akka.stream.{Inlet, Outlet}
import akka.stream.stage.{GraphStageLogic, InHandler, OutHandler, StageLogging}

object BaseMergeStage:
  /**
    * An enumeration defining constants for the input inlets of the merge
    * stage. These constants are used for instance to declare, from which of
    * the input sources an element stems or which ports need to be pulled.
    */
  enum Input:
    case Inlet1
    case Inlet2

  /** Convenience constant to indicate that inlet 1 should be pulled. */
  final val Pull1 = Set(Input.Inlet1)

  /** Convenience constant to indicate that inlet 2 should be pulled. */
  final val Pull2 = Set(Input.Inlet2)

  /** Convenience constant to indicate that both inlets should be pulled. */
  final val PullBoth = Set(Input.Inlet1, Input.Inlet2)

/**
  * A trait defining common logic for ''GraphStage'' implementations that merge
  * the content of two sources.
  *
  * The basic idea is that this trait already manages the ports of the stage,
  * e.g. it polls the desired sources as requested. A concrete implementation
  * provides the logic, which elements in which order should be passed
  * downstream. This logic is organized in terms of stateful
  * ''sync functions''. Such a function is passed the current state (managed by
  * this trait) and an element from upstream. It then returns a follow-up state
  * and elements to be pushed downstream.
  *
  * @param in1 the inlet for the first input source
  * @param in2 the inlet for the second input source
  * @param out the outlet of this stage
  * @tparam ELEMENT1 the element type of the first input source
  * @tparam ELEMENT2 the element type of the second input source
  */
trait BaseMergeStage[ELEMENT1 <: AnyRef, ELEMENT2 <: AnyRef, OUTELEMENT](in1: Inlet[ELEMENT1],
                                                                         in2: Inlet[ELEMENT2],
                                                                         out: Outlet[OUTELEMENT]) extends GraphStageLogic :
  this: StageLogging =>

  import BaseMergeStage.*
  import BaseMergeStage.Input.*

  /**
    * A base trait defining basic properties of the state managed for a merge
    * operation. These properties are accessed by this trait. Concrete
    * implementations can add further information.
    */
  trait BaseMergeState:
    /**
      * Returns the [[MergeFunc]] of the current merge state. This function is
      * invoked when new elements are available that need to be processed.
      *
      * @return the current ''MergeFunc''
      */
    def mergeFunc: MergeFunc

  /**
    * The type holding the state of the merge operation. A concrete
    * implementation can use a data class that holds all state information
    * required for the merge process.
    */
  type MergeState <: BaseMergeState

  /**
    * A class with details about elements to be emitted downstream.
    *
    * @param elements   the elements to be pushed downstream
    * @param pullInlets defines the inlets to be pulled
    * @param complete   flag whether the stream should be completed
    */
  case class EmitData(elements: scala.collection.immutable.Iterable[OUTELEMENT],
                      pullInlets: Iterable[Input],
                      complete: Boolean = false)

  /**
    * A type representing the elements that need to be handled during a merge
    * operation. This is a union of the types emitted by the input sources.
    */
  type MergeElement = ELEMENT1 | ELEMENT2

  /**
    * Type of a function that handles a new element during a merge operation.
    * This function is invoked for each element that is received from one of
    * the input sources. It is passed the current merge state, the index of the
    * source that emitted the element, and the element itself. The element may
    * be '''null''', indicating that this input source is complete.
    */
  type MergeFunc = (MergeState, Input, MergeElement) => (EmitData, MergeState)

  /**
    * A constant that can be used by a [[MergeFunc]] to indicate that no action
    * needs to be performed: no data to emit and no inlets to pull.
    */
  final val EmitNothing = EmitData(Nil, Nil)

  /**
    * Returns the current state of this stage.
    *
    * @return the current ''MergeState''
    */
  protected def state: MergeState

  /**
    * Updates the current state of this stage.
    *
    * @param newState the new ''MergeState''
    */
  protected def updateState(newState: MergeState): Unit

  /**
    * Manually stores the inlets that have been pulled; this is necessary to
    * process onUpstreamFinish notifications at the correct time
    */
  private val pulledPorts: Array[Boolean] = Array(true, true)

  /**
    * An array that stores a flag for each inlet whether it has been finished.
    */
  private val finishedPorts: Array[Boolean] = Array(false, false)

  setHandler(in1, new InHandler {
    override def onPush(): Unit = {
      updateMergeState(Inlet1, grab(in1))
    }

    override def onUpstreamFinish(): Unit = {
      handleInletFinished(Inlet1)
    }
  })

  setHandler(in2, new InHandler {
    override def onPush(): Unit = {
      updateMergeState(Inlet2, grab(in2))
    }

    override def onUpstreamFinish(): Unit = {
      handleInletFinished(Inlet2)
    }
  })

  setHandler(out, new OutHandler {
    override def onPull(): Unit = {}
  })

  override def preStart(): Unit =
    pull(in1)
    pull(in2)

  /**
    * Returns a flag whether the given input source has already been finished.
    *
    * @param input defines the input source
    * @return a flag whether this source is already finished
    */
  protected def isFinished(input: Input): Boolean = finishedPorts(input.ordinal)

  /**
    * Returns the number of input sources that have been finished.
    *
    * @return the number of finished input sources
    */
  protected def numberOfFinishedSources: Int = Input.values.count(isFinished)

  /**
    * Updates the current state on receiving an element.
    *
    * @param input   defines the input source
    * @param element the element (may be '''null''' for completion)
    */
  private def updateMergeState(input: Input, element: MergeElement): Unit =
    val (data, next) = state.mergeFunc(state, input, element)
    updateState(next)
    emitMultiple(out, data.elements)
    pulledPorts(input.ordinal) = false
    pullPorts(data)
    if data.complete then complete(out)

  /**
    * Pulls the input ports referenced by the given ''EmitData''. This
    * method also checks whether one of the ports to be pulled is already
    * finished; if so, a close notification is sent by invoking
    * ''updateState()'' recursively.
    *
    * @param data the ''EmitData'' object
    */
  private def pullPorts(data: EmitData): Unit =
    data.pullInlets foreach { input =>
      val inlet = input match {
        case Inlet1 => in1
        case Inlet2 => in2
      }
      pulledPorts(input.ordinal) = true
      if isFinished(input) then
        updateMergeState(input, null.asInstanceOf[MergeElement])
      else pull(inlet)
    }

  /**
    * Handles a notification about a finished input port. Such
    * notifications may arrive at a time when they cannot be handled yet
    * because an element from this inlet is still being processed. (The
    * processing logic expects that elements and close notifications only
    * come in when the port has been pulled.) This implementation therefore
    * propagates the notification only if this port has been pulled. If
    * this is not the case, the close notification is processed when the
    * port is pulled for the next time.
    *
    * @param input defines the input source
    */
  private def handleInletFinished(input: Input): Unit =
    finishedPorts(input.ordinal) = true
    if pulledPorts(input.ordinal) then
      updateMergeState(input, null.asInstanceOf[MergeElement])
