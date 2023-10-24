/*
 * Copyright 2018-2023 The Developers Team.
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
import com.github.sync.SyncTypes
import com.github.sync.SyncTypes.*
import com.github.sync.SyncTypes.SyncAction.*
import com.github.sync.stream.BaseMergeStage.{Input, MergeEmitData}
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging}
import org.apache.pekko.stream.{Attributes, FanInShape2, Inlet, Outlet, Shape}

private object MirrorStage:
  /**
    * Constant for a destination ID that is used when this property is
    * irrelevant.
    */
  private val DstIDUnknown = "-"

  /**
    * The internal class representing the logic and the state of the mirror
    * stage.
    *
    * @param shape           the shape
    * @param in1             the inlet for the first input source
    * @param in2             the inlet for the second input source
    * @param out             the outlet of this stage
    * @param ignoreTimeDelta a time difference that is to be ignored when
    *                        comparing two files
    */
  private class MirrorStageLogic(shape: Shape,
                                 in1: Inlet[FsElement],
                                 in2: Inlet[FsElement],
                                 out: Outlet[SyncOperation],
                                 ignoreTimeDelta: IgnoreTimeDelta)
    extends GraphStageLogic(shape),
      BaseMergeStage[FsElement, FsElement, SyncOperation](in1, in2, out),
      ElementMergeStage[FsElement, FsElement, SyncOperation](in1, in2, out),
      StageLogging :
    /**
      * The state of the [[MirrorStage]].
      *
      * This class holds state information that is required when processing a
      * sync stream.
      *
      * @param currentElement     a current element to be synced
      * @param mergeFunc          the current merge function
      * @param removedFolderState the state for removed folders
      */
    case class MirrorState(override val mergeFunc: MergeFunc,
                           override val currentElement: Option[FsElement],
                           removedFolderState: RemovedFolderState) extends ElementMergeState :
      override def updateCurrentElement(element: Option[FsElement]): MirrorState = copy(currentElement = element)

      override def withMergeFunc(f: MergeFunc): MirrorState = copy(mergeFunc = f)
    end MirrorState

    override type MergeState = MirrorState

    /**
      * A ''MergeFunc'' that becomes active when the source structure is
      * complete.
      */
    private val sourceCompleteMergeFunc = inputSourceComplete(emitSourceComplete)(streamComplete)

    /**
      * A ''MergeFunc'' that becomes active when the destination struture is
      * complete.
      */
    private val destinationCompleteMergeFunc = inputSourceComplete(emitDestinationComplete)(streamComplete)

    /**
      * A ''MergeFunc'' that waits until an element from each input source has
      * been received. This is also the initial merge function.
      */
    private val waitMergeFunc = waitForElements(syncElements, sourceCompleteMergeFunc, destinationCompleteMergeFunc)

    /** The specific state of the ''MirrorStage''. */
    private var mirrorState = MirrorState(waitMergeFunc, None, RemovedFolderState.Empty)

    override protected def state: MirrorState = mirrorState

    override protected def updateState(newState: MirrorState): Unit =
      mirrorState = newState

    /**
      * A merge function that is active when both input sources deliver
      * elements. Here the actual sync logic is implemented: the current
      * elements from both inputs are compared, and it is decided which sync
      * operations need to be performed.
      *
      * @param state   the current sync state
      * @param input   defines the current input source
      * @param element the new element
      * @return data to emit and the next state
      */
    private def syncElements(state: MirrorState, input: Input, element: Option[FsElement]): MergeResult =
      handleNoneElementDuringSync(state, input, element, sourceCompleteMergeFunc,
        destinationCompleteMergeFunc) { (elemSource, elemDest) =>
        syncOperationForElements(elemSource, elemDest, state) orElse
          syncOperationForFileFolderDiff(elemSource, elemDest, state) getOrElse
          emitAndPullBoth(List(SyncOperation(elemSource, ActionNoop, elemDest.level, DstIDUnknown)), state)
      }

    /**
      * An emit function that is invoked if the destination structure is
      * complete. All elements that are now encountered have to be created in
      * this structure.
      *
      * @param s    the current state
      * @param elem the current element
      * @return data to emit and the updated state
      */
    private def emitDestinationComplete(s: MirrorState, elem: FsElement): MergeResult =
      (MergeEmitData(List(createOp(elem)), BaseMergeStage.Pull1), s)

    /**
      * An emit function that is invoked if the source structure is complete.
      * Remaining elements in the destination structure now have to be removed.
      *
      * @param s    the current state
      * @param elem the current element
      * @return data to emit and the updated state
      */
    private def emitSourceComplete(s: MirrorState, elem: FsElement): MergeResult =
      val (op, next) = removeElement(s, elem, removedPath = None)
      (MergeEmitData(op, BaseMergeStage.Pull2), next)

    /**
      * Produces the final ''EmitData'' if the stream is complete.
      *
      * @param state the current state
      * @return the ''EmitData'' completing this stream
      */
    private def streamComplete(state: MirrorState): EmitData =
      MergeEmitData(state.removedFolderState.deferredOperations, Nil, complete = true)

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
      * @return an ''Option'' with data how to handle these elements
      */
    private def syncOperationForElements(elemSource: FsElement, elemDest: FsElement, state: MirrorState):
    Option[MergeResult] =
      lazy val delta = compareElements(elemSource, elemDest)
      val removedRoot = state.removedFolderState.findRoot(elemDest)
      if removedRoot.isDefined || delta > 0 then
        val (ops, next) = removeElement(state.updateCurrentElement(Some(elemSource)), elemDest, removedRoot)
        Some((MergeEmitData(ops, BaseMergeStage.Pull2), next))
      else if delta < 0 then
        Some((MergeEmitData(List(createOp(elemSource)), BaseMergeStage.Pull1),
          state.updateCurrentElement(Some(elemDest))))
      else None

    /**
      * Handles advanced corner cases when comparing two elements that depend on
      * the element type. This function also deals with constellations that a
      * file in the source structure corresponds to a folder in the destination
      * structure and vice versa.
      *
      * @param elemSource the element from the source inlet
      * @param elemDest   the element from the destination inlet
      * @param state      the current sync state
      * @return an ''Option'' with data how to handle these elements
      */
    private def syncOperationForFileFolderDiff(elemSource: FsElement, elemDest: FsElement, state: MirrorState):
    Option[MergeResult] =
      (elemSource, elemDest) match
        case (eSrc: FsFile, eDst: FsFile)
          if ignoreTimeDelta.isDifferentFileTimes(eSrc, eDst) || eSrc.size != eDst.size =>
          log.debug("Different file attributes: {} <=> {}.", eSrc, eDst)
          Some(emitAndPullBoth(List(SyncOperation(eSrc, ActionOverride, eSrc.level, dstID = eDst.id)), state))

        case (folderSrc: FsFolder, fileDst: FsFile) => // file converted to folder
          val ops = List(removeOp(fileDst, fileDst.level), createOp(folderSrc))
          Some(emitAndPullBoth(ops, state))

        case (fileSrc: FsFile, folderDst: FsFolder) => // folder converted to file
          val defOps =
            removeOp(folderDst, fileSrc.level) :: createOp(fileSrc) :: state.removedFolderState.deferredOperations
          val nextRemoveState = state.removedFolderState.addRoot(folderDst).copy(deferredOperations = defOps)
          val next = state.copy(removedFolderState = nextRemoveState)
          Some(emitAndPullBoth(Nil, next))

        case _ => None

    /**
      * Creates ''EmitData'' and an updated state in case that both inlets have
      * to be pulled.
      *
      * @param op    a sequence with sync operations
      * @param state the current sync state
      * @return data to emit and the next state
      */
    private def emitAndPullBoth(op: List[SyncOperation], state: MirrorState): MergeResult =
      (MergeEmitData(op, BaseMergeStage.PullBoth), state.copy(currentElement = None, mergeFunc = waitMergeFunc))

    /**
      * Generates emit data for an element to be removed. The exact actions to
      * delete the element depend on the element type: files can be deleted
      * directly, the deletion of folders is deferred. The details are handled by
      * the [[RemovedFolderState]] object.
      *
      * @param state       the current mirror state
      * @param element     the element to be removed
      * @param removedPath a removed root path containing the current element
      * @return data to emit and the updated state
      */
    private def removeElement(state: MirrorState, element: FsElement, removedPath: Option[NormalizedFolder]):
    (List[SyncOperation], MirrorState) =
      val (ops, nextRemoveState) =
        state.removedFolderState.handleRemoveElement(element, SyncAction.ActionRemove, removedPath)
      val nextState = state.copy(removedFolderState = nextRemoveState)
      (ops, nextState)

    /**
      * Creates an operation that indicates that an element needs to be created.
      *
      * @param elem the element affected
      * @return the operation
      */
    private def createOp(elem: FsElement): SyncOperation =
      SyncOperation(elem, ActionCreate, elem.level, DstIDUnknown)

    /**
      * Creates an operation that indicates that an element needs to be removed.
      *
      * @param element the element affected
      * @param level   the level of the operation
      * @return the operation
      */
    private def removeOp(element: FsElement, level: Int): SyncOperation =
      SyncOperation(element, ActionRemove, level, dstID = element.id)

    /**
      * Compares the given elements and returns an integer value determining
      * which one is before the other: a value less than zero means that the
      * source element is before the destination element; a value greater than
      * zero means that the destination element is before the source element; the
      * value 0 means that elements are equivalent.
      *
      * @param elemSource the source element
      * @param elemDest   the destination element
      * @return the result of the comparison
      */
    private def compareElements(elemSource: FsElement, elemDest: FsElement): Int =
      val (srcParent, srcName) = UriEncodingHelper.splitParent(elemSource.relativeUri)
      val (dstParent, dstName) = UriEncodingHelper.splitParent(elemDest.relativeUri)
      val deltaParent = srcParent.compareTo(dstParent)
      if deltaParent != 0 then deltaParent
      else srcName.compareTo(dstName)

  end MirrorStageLogic

/**
  * A special stage that generates sync operations for two input sources.
  *
  * This stage expects that the input sources generate streams of ordered
  * ''FsElement'' objects. It then compares the elements from its inputs and
  * produces corresponding ''SyncOperation'' objects as output.
  *
  * By processing the generated ''SyncOperation'' objects, the destination
  * structure can be synced with the source structure. Note that the operations
  * produced by this stage are in an order that can be processed directly. For
  * instance, if a ''SyncOperation'' is emitted to delete a folder, it is
  * guaranteed that all operations that remove the content of this folder have
  * been pushed before.
  *
  * @param ignoreTimeDelta a time difference that is to be ignored when
  *                        comparing two files
  */
private class MirrorStage(val ignoreTimeDelta: IgnoreTimeDelta = IgnoreTimeDelta.Zero)
  extends GraphStage[FanInShape2[FsElement, FsElement, SyncOperation]] :

  import MirrorStage.*

  val out: Outlet[SyncOperation] = Outlet[SyncOperation]("MirrorStage.out")
  val inSource: Inlet[FsElement] = Inlet[FsElement]("MirrorStage.inSource")
  val inDest: Inlet[FsElement] = Inlet[FsElement]("MirrorStage.inDest")

  override def shape: FanInShape2[FsElement, FsElement, SyncOperation] =
    new FanInShape2[FsElement, FsElement, SyncOperation](inSource, inDest, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new MirrorStageLogic(shape, inSource, inDest, out, ignoreTimeDelta)
