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
import com.github.sync.SyncTypes.{FsElement, FsFile, SyncAction, SyncElementResult, SyncOperation, compareElementUris}
import com.github.sync.stream.BaseMergeStage.Input
import com.github.sync.stream.LocalStateStage.{ChangeType, ElementWithDelta}

object SyncStage:
  /**
    * An internal class implementing the logic of the sync stage and holding
    * its state.
    *
    * @param shape the shape
    * @param in1   the inlet for the first input source
    * @param in2   the inlet for the second input source
    * @param out   the outlet of this stage
    */
  private class SyncStageLogic(shape: Shape,
                               in1: Inlet[ElementWithDelta],
                               in2: Inlet[FsElement],
                               out: Outlet[SyncElementResult])
    extends GraphStageLogic(shape),
      BaseMergeStage[ElementWithDelta, FsElement, SyncElementResult](in1, in2, out),
      StageLogging :

    /**
      * A data class representing the state of this stage.
      *
      * @param mergeFunc            the current merge function
      * @param currentLocalElement  optional local element to be synced
      * @param currentRemoteElement optional remote element to be synced
      */
    case class SyncState(override val mergeFunc: MergeFunc,
                         currentLocalElement: Option[ElementWithDelta],
                         currentRemoteElement: Option[FsElement])
      extends BaseMergeState :
      /**
        * Obtains the current element stored in this state for the given input
        * source.
        *
        * @param input identifies the input source
        * @return an ''Option'' with the current element of this source
        */
      def currentElement(input: Input): Option[MergeElement] = input match
        case Input.Inlet1 => currentLocalElement
        case Input.Inlet2 => currentRemoteElement

      /**
        * Returns a ''MergeResult'' object with the content specified and a
        * copy of this state that has the current elements defined by the given
        * ''Input'' constants reset. After comparing two elements and computing
        * a result, it is often required to reset one or both current elements
        * to start with the next pair to be compared.
        *
        * @param elements   the items to emit downstream
        * @param pullInlets the input sources to pull
        * @return a ''MergeResult'' with this information
        */
      def mergeResultWithResetElements(elements: List[SyncElementResult],
                                       pullInlets: Iterable[Input]): MergeResult =
        def nextCurrent[T](input: Input, value: => Option[T]): Option[T] =
          if pullInlets.exists(_ == input) then None else value

        val nextCurrentLocal = nextCurrent(Input.Inlet1, currentLocalElement)
        val nextCurrentRemote = nextCurrent(Input.Inlet2, currentRemoteElement)
        (EmitData(elements, pullInlets),
          copy(currentLocalElement = nextCurrentLocal, currentRemoteElement = nextCurrentRemote))

    end SyncState

    override type MergeState = SyncState

    /** Holds the current state of this stage. */
    private var syncState = SyncState(sync, None, None)

    override protected def state: SyncState = syncState

    override protected def updateState(newState: SyncState): Unit = syncState = newState

    /**
      * The default merge function of this stage. From the incoming elements,
      * it populates the internal state until a pair of elements is available
      * from both input sources. These elements are then compared to derive
      * sync operations.
      *
      * @param state   the current state
      * @param input   identifies the input source
      * @param element the current element
      * @return data to be emitted and the updated merge state
      */
    private def sync(state: SyncState, input: Input, element: Option[MergeElement]): MergeResult =
      element match
        case None =>
          val nextState = state.copy(mergeFunc = syncRemaining)
          val remainingInput = input.opposite
          val pendingElement = state.currentElement(remainingInput)
          if pendingElement.isDefined then
            syncRemaining(nextState, remainingInput, pendingElement)
          else
            (EmitNothing, nextState)

        case Some(localElem: ElementWithDelta) =>
          checkAndSyncCurrentElements(state.copy(currentLocalElement = Some(localElem)))
        case Some(elem: FsElement) =>
          checkAndSyncCurrentElements(state.copy(currentRemoteElement = Some(elem)))

    /**
      * A merge function that is active when one of the input sources is
      * complete. In this case, the output to generate solely depends on the
      * received input element: it has to be created in the opposite structure.
      *
      * @param state   the current state
      * @param input   identifies the input source
      * @param element the current element
      * @return data to be emitted and the updated merge state
      */
    private def syncRemaining(state: SyncState, input: Input, element: Option[MergeElement]): MergeResult =
      element match
        case None => (EmitNothing.copy(complete = true), state)
        case Some(elem: FsElement) =>
          val op = SyncOperation(elem, SyncAction.ActionLocalCreate, elem.level, elem.id)
          (EmitData(emitOp(op), List(input)), state)
        case Some(elem: ElementWithDelta) =>
          val op = SyncOperation(elem.element, SyncAction.ActionCreate, elem.element.level, elem.element.id)
          (EmitData(emitOp(op), List(input)), state)

    /**
      * Generates a sync result for the current elements in the state if both
      * are available. Otherwise, no action is taken, which means waiting for
      * missing elements to arrive.
      *
      * @param state the current state
      * @return data to be emitted and the updated merge state
      */
    private def checkAndSyncCurrentElements(state: SyncState): MergeResult =
      (for
        local <- state.currentLocalElement
        remote <- state.currentRemoteElement
      yield
        syncCurrentElements(state, local, remote)) getOrElse(EmitNothing, state)

    /**
      * Compares the current local and remote elements and generates a sync
      * result.
      *
      * @param state  the current state
      * @param local  the current local element
      * @param remote the current remote element
      * @return data to be emitted and the updated merge state
      */
    private def syncCurrentElements(state: SyncState, local: ElementWithDelta, remote: FsElement): MergeResult =
      math.signum(compareElementUris(local.element, remote)) match
        case 1 =>
          syncLocalGreaterRemote(state, remote)

        case -1 =>
          syncLocalLessRemote(state, local)

        case _ => // element URIs are equal
          syncLocalEqualRemote(state, local, remote)

    /**
      * Produces a ''MergeResult'' if the URIs of the current local and remote
      * elements are equal.
      *
      * @param state  the current state
      * @param local  the current local element
      * @param remote the current remote element
      * @return data to be emitted and the updated merge state
      */
    private def syncLocalEqualRemote(state: SyncState, local: ElementWithDelta, remote: FsElement): MergeResult =
      val op = local.changeType match
        case ChangeType.Removed =>
          SyncOperation(remote, SyncAction.ActionRemove, remote.level, remote.id)
        case ChangeType.Changed =>
          SyncOperation(local.element, SyncAction.ActionOverride, remote.level, remote.id)
        case _ =>
          SyncOperation(local.element, SyncAction.ActionNoop, remote.level, remote.id)
      state.mergeResultWithResetElements(emitOp(op), BaseMergeStage.PullBoth)

    /**
      * Produces a ''MergeResult'' if the current local element's URI is less
      * than the one of the current remote element.
      *
      * @param state the current state
      * @param local the current local element
      * @return data to be emitted and the updated merge state
      */
    private def syncLocalLessRemote(state: SyncState, local: ElementWithDelta): MergeResult =
      val op = local.changeType match
        case ChangeType.Unchanged =>
          SyncOperation(local.element, SyncAction.ActionLocalRemove, local.element.level, local.element.id)
        case _ =>
          SyncOperation(local.element, SyncAction.ActionCreate, local.element.level, local.element.id)
      state.mergeResultWithResetElements(emitOp(op), BaseMergeStage.Pull1)

    /**
      * Produces a ''MergeResult'' if the current local element's URI is
      * greater than the one of the current remote element.
      *
      * @param state  the current state
      * @param remote the current remote element
      * @return data to be emitted and the updated merge state
      */
    private def syncLocalGreaterRemote(state: SyncState, remote: FsElement): MergeResult =
      val op = SyncOperation(remote, SyncAction.ActionLocalCreate, remote.level, remote.id)
      state.mergeResultWithResetElements(emitOp(op), BaseMergeStage.Pull2)

  end SyncStageLogic

  /**
    * Convenience function to produce a successful sync result for a single
    * operation.
    *
    * @param op the single operation representing the result
    * @return the resulting list of results
    */
  private def emitOp(op: SyncOperation): List[SyncElementResult] = List(Right(List(op)))

/**
  * A special stage that generates [[SyncOperation]]s for a local and a remote
  * input source.
  *
  * This stage implements bidrectional sync logic between two sources. One
  * source is considered local, since its state of the last sync operation is
  * known. Therefore, conflicts caused by changes on elements in both sources
  * can be detected.
  */
class SyncStage extends GraphStage[FanInShape2[ElementWithDelta, FsElement, SyncElementResult]] :
  val out: Outlet[SyncElementResult] = Outlet[SyncElementResult]("SyncStage.out")
  val inLocal: Inlet[ElementWithDelta] = Inlet[ElementWithDelta]("SyncStage.inLocal")
  val inRemote: Inlet[FsElement] = Inlet[FsElement]("SyncStage.inRemote")

  override def shape: FanInShape2[ElementWithDelta, FsElement, SyncElementResult] =
    new FanInShape2[ElementWithDelta, FsElement, SyncElementResult](inLocal, inRemote, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    SyncStage.SyncStageLogic(shape, inLocal, inRemote, out)
