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
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging}
import akka.stream.{Attributes, FanInShape2, Inlet, Outlet, Shape}
import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.sync.SyncTypes
import com.github.sync.SyncTypes.*
import com.github.sync.SyncTypes.SyncAction.*
import com.github.sync.stream.BaseMergeStage.Input

object MirrorStage:
  /**
    * Constant for a destination ID that is used when this property is
    * irrelevant.
    */
  private val DstIDUnknown = "-"

  /**
    * The internal class representing the logic and the state of the mirror
    * stage.
    *
    * @param shape              the shape
    * @param in1                the inlet for the first input source
    * @param in2                the inlet for the second input source
    * @param out                the outlet of this stage
    * @param ignoreTimeDeltaSec a time difference in seconds that is to be
    *                           ignored when comparing two files
    */
  private class MirrorStageLogic(shape: Shape,
                                 in1: Inlet[FsElement],
                                 in2: Inlet[FsElement],
                                 out: Outlet[SyncOperation],
                                 ignoreTimeDeltaSec: Int)
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
      * @param currentElement a current element to be synced
      * @param mergeFunc      the current merge function
      * @param deferredOps    sync operations that will be pushed out at the
      *                       very end of the stream
      * @param removedPaths   root elements that have been removed in the
      *                       destination structure
      */
    case class MirrorState(override val mergeFunc: MergeFunc,
                           override val currentElement: FsElement,
                           deferredOps: List[SyncOperation],
                           removedPaths: Set[FsElement]) extends ElementMergeState :
      override def updateCurrentElement(element: FsElement): MirrorState = copy(currentElement = element)

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
    private var mirrorState = MirrorState(waitMergeFunc, null, Nil, Set.empty)

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
    private def syncElements(state: MirrorState, input: Input, element: FsElement): (EmitData, MirrorState) =
      handleNullElementDuringSync(state, input, element, sourceCompleteMergeFunc,
        destinationCompleteMergeFunc) getOrElse {
        val (elemSource, elemDest) = extractMergePair(state, input, element)
        syncOperationForElements(elemSource, elemDest, state) orElse
          syncOperationForFileFolderDiff(elemSource, elemDest, state) getOrElse
          emitAndPullBoth(List(SyncOperation(elemSource, ActionNoop, element.level, DstIDUnknown)), state)
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
    private def emitDestinationComplete(s: MirrorState, elem: FsElement): (EmitData, MirrorState) =
      (EmitData(List(createOp(elem)), BaseMergeStage.Pull1), s)

    /**
      * An emit function that is invoked if the source structure is complete.
      * Remaining elements in the destination structure now have to be removed.
      *
      * @param s    the current state
      * @param elem the current element
      * @return data to emit and the updated state
      */
    private def emitSourceComplete(s: MirrorState, elem: FsElement): (EmitData, MirrorState) =
      val (op, next) = removeElement(s, elem, removedPath = None)
      (EmitData(op, BaseMergeStage.Pull2), next)

    /**
      * Produces the final ''EmitData'' if the stream is complete.
      *
      * @param state the current state
      * @return the ''EmitData'' completing this stream
      */
    private def streamComplete(state: MirrorState): EmitData =
      EmitData(state.deferredOps, Nil, complete = true)

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
    Option[(EmitData, MirrorState)] =
      lazy val delta = SyncTypes.compareElementUris(elemSource, elemDest)
      val isRemoved = isInRemovedPath(state, elemDest)
      if isRemoved.isDefined || delta > 0 then
        val (op, next) = removeElement(state.updateCurrentElement(elemSource), elemDest, isRemoved)
        Some((EmitData(op, BaseMergeStage.Pull2), next))
      else if delta < 0 then
        Some((EmitData(List(createOp(elemSource)), BaseMergeStage.Pull1), state.updateCurrentElement(elemDest)))
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
    Option[(EmitData, MirrorState)] =
      (elemSource, elemDest) match
        case (eSrc: FsFile, eDst: FsFile)
          if differentFileTimes(eSrc, eDst) || eSrc.size != eDst.size =>
          log.debug("Different file attributes: {} <=> {}.", eSrc, eDst)
          Some(emitAndPullBoth(List(SyncOperation(eSrc, ActionOverride, eSrc.level, dstID = eDst.id)), state))

        case (folderSrc: FsFolder, fileDst: FsFile) => // file converted to folder
          val ops = List(removeOp(fileDst, fileDst.level), createOp(folderSrc))
          Some(emitAndPullBoth(ops, state))

        case (fileSrc: FsFile, folderDst: FsFolder) => // folder converted to file
          val defOps = removeOp(folderDst, fileSrc.level) :: createOp(fileSrc) :: state.deferredOps
          val next = state.copy(deferredOps = defOps,
            removedPaths = addRemovedFolder(state, folderDst))
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
    private def emitAndPullBoth(op: List[SyncOperation], state: MirrorState): (EmitData, MirrorState) =
      (EmitData(op, BaseMergeStage.PullBoth), state.copy(currentElement = null, mergeFunc = waitMergeFunc))

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
    private def handleNullElementOnFinishedSource(state: MirrorState, element: FsElement)
                                                 (emitFunc: (MirrorState, FsElement) => (EmitData, MirrorState)):
    Option[(EmitData, MirrorState)] =
      if element == null then
        Option(state.currentElement).map { currentElem =>
          val (emit, next) = emitFunc(state, currentElem)
          Some((emit, next.copy(currentElement = null)))
        } getOrElse Some {
          if numberOfFinishedSources > 1 then
            (EmitData(state.deferredOps, Nil, complete = true), state)
          else (EmitNothing, state)
        }
      else None

    /**
      * Checks whether the given element belongs to a path in the destination
      * structure that has been removed before. Such elements can be removed
      * directly.
      *
      * @param state   the current sync state
      * @param element the element to be checked
      * @return a flag whether this element is in a removed path
      */
    private def isInRemovedPath(state: MirrorState, element: FsElement): Option[FsElement] =
      state.removedPaths find { e =>
        element.relativeUri.startsWith(e.relativeUri)
      }

    /**
      * Generates emit data for an element to be removed. The exact actions to
      * delete the element depend on the element type: files can be deleted
      * directly, the deletion of folders is deferred. So in case of a folder,
      * the properties of the state are updated accordingly.
      *
      * @param state       the current sync state
      * @param element     the element to be removed
      * @param removedPath a removed root path containing the current element
      * @return data to emit and the updated state
      */
    private def removeElement(state: MirrorState, element: FsElement, removedPath: Option[FsElement]):
    (List[SyncOperation], MirrorState) =
      val op = removeOp(element, removedPath map (_.level) getOrElse element.level)
      element match
        case folder: FsFolder =>
          val paths = if removedPath.isDefined then state.removedPaths
          else addRemovedFolder(state, folder)
          (Nil, state.copy(removedPaths = paths, deferredOps = op :: state.deferredOps))
        case _ =>
          (List(op), state)

    /**
      * Adds a folder to the set of removed root paths.
      *
      * @param state  the current sync state
      * @param folder the folder to be added
      * @return the updated set with root paths
      */
    private def addRemovedFolder(state: MirrorState, folder: FsFolder): Set[FsElement] =
      state.removedPaths + folder.copy(relativeUri = folder.relativeUri +
        UriEncodingHelper.UriSeparator)

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
      * Checks whether the timestamps of the given files are different, taking
      * the configured threshold for the time delta into account.
      *
      * @param eSrc            the source file
      * @param eDst            the destination file
      * @param ignoreTimeDelta the delta in file times to be ignored
      * @return a flag whether these files have a different timestamp
      */
    private def differentFileTimes(eSrc: FsFile, eDst: FsFile): Boolean =
      math.abs(extractTime(eSrc) - extractTime(eDst)) > ignoreTimeDeltaSec

    /**
      * Extracts the time of a file that needs to be compared to detect modified
      * files. This method ignores milliseconds as some structures that can be
      * synced do not support file modification times with this granularity.
      *
      * @param file a file
      * @return the modified time to be compared during a sync operations
      */
    private def extractTime(file: FsFile): Long = file.lastModified.getEpochSecond
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
  * @param ignoreTimeDeltaSec a time difference in seconds that is to be
  *                           ignored when comparing two files
  */
class MirrorStage(val ignoreTimeDeltaSec: Int = 0)
  extends GraphStage[FanInShape2[FsElement, FsElement, SyncOperation]] :

  import MirrorStage._

  val out: Outlet[SyncOperation] = Outlet[SyncOperation]("MirrorStage.out")
  val inSource: Inlet[FsElement] = Inlet[FsElement]("MirrorStage.inSource")
  val inDest: Inlet[FsElement] = Inlet[FsElement]("MirrorStage.inDest")

  override def shape: FanInShape2[FsElement, FsElement, SyncOperation] =
    new FanInShape2[FsElement, FsElement, SyncOperation](inSource, inDest, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new MirrorStageLogic(shape, inSource, inDest, out, ignoreTimeDeltaSec)
