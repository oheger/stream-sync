/*
 * Copyright 2018-2019 The Developers Team.
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

package com.github.sync.impl

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FanInShape2, Inlet, Outlet}
import com.github.sync._
import com.github.sync.util.UriEncodingHelper

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
    * @param ops           the operations to be pushed downstream
    * @param pullInletsIdx the indices of inlets to be pulled
    * @param complete      flag whether the stream should be completed
    */
  private case class EmitData(ops: collection.immutable.Iterable[SyncOperation],
                              pullInletsIdx: Iterable[Int],
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
    * @param deferredOps     sync operations that will be pushed out at the
    *                        very end of the stream
    * @param removedPaths    root elements that have been removed in the
    *                        destination structure
    */
  private case class SyncState(currentElem: FsElement,
                               syncFunc: SyncFunc,
                               finishedSources: Int,
                               deferredOps: List[SyncOperation],
                               removedPaths: Set[FsElement]) {
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
      syncOperationForElements(elemSource, elemDest, state, stage) orElse
        syncOperationForFileFolderDiff(elemSource, elemDest, state, stage,
          stage.ignoreTimeDeltaSec)
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
                                  element: FsElement): (EmitData, SyncState) = {
    def handleElement(s: SyncState, elem: FsElement): (EmitData, SyncState) =
      (EmitData(List(SyncOperation(elem, ActionCreate, elem.level)), stage.PullSource), s)

    handleNullElementOnFinishedSource(state, element)(handleElement) getOrElse
      handleElement(state, element)
  }

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
                                element: FsElement): (EmitData, SyncState) = {
    def handleElement(s: SyncState, elem: FsElement): (EmitData, SyncState) = {
      val (op, next) = removeElement(s, elem, removedPath = None)
      (EmitData(op, stage.PullDest), next)
    }

    handleNullElementOnFinishedSource(state, element)(handleElement) getOrElse
      handleElement(state, element)
  }

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
  Option[(EmitData, SyncState)] = {
    lazy val delta = compareElements(elemSource, elemDest)
    val isRemoved = isInRemovedPath(state, elemDest)
    if (isRemoved.isDefined || delta > 0) {
      val (op, next) = removeElement(state.updateCurrentElement(elemSource),
        elemDest, isRemoved)
      Some((EmitData(op, stage.PullDest), next))
    } else if (delta < 0)
      Some((EmitData(List(SyncOperation(elemSource, ActionCreate, elemSource.level)),
        stage.PullSource), state.updateCurrentElement(elemDest)))
    else None
  }

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
  private def compareElements(elemSource: FsElement, elemDest: FsElement): Int = {
    val deltaLevel = elemSource.level - elemDest.level
    if (deltaLevel != 0) deltaLevel
    else elemSource.relativeUri.compareTo(elemDest.relativeUri)
  }

  /**
    * Handles advanced corner cases when comparing two elements that depend on
    * the element type. This function also deals with constellations that a
    * file in the source structure corresponds to a folder in the destination
    * structure and vice versa.
    *
    * @param elemSource      the element from the source inlet
    * @param elemDest        the element from the destination inlet
    * @param state           the current sync state
    * @param stage           the stage
    * @param ignoreTimeDelta the delta in file times to be ignored
    * @return an ''Option'' with data how to handle these elements
    */
  private def syncOperationForFileFolderDiff(elemSource: FsElement, elemDest: FsElement,
                                             state: SyncState, stage: SyncStage,
                                             ignoreTimeDelta: Int):
  Option[(EmitData, SyncState)] =
    (elemSource, elemDest) match {
      case (eSrc: FsFile, eDst: FsFile)
        if differentFileTimes(eSrc, eDst, ignoreTimeDelta) || eSrc.size != eDst.size =>
        Some(emitAndPullBoth(List(SyncOperation(eSrc, ActionOverride, eSrc.level)), state, stage))

      case (folderSrc: FsFolder, fileDst: FsFile) => // file converted to folder
        val ops = List(SyncOperation(fileDst, ActionRemove, fileDst.level),
          SyncOperation(folderSrc, ActionCreate, folderSrc.level))
        Some(emitAndPullBoth(ops, state, stage))

      case (fileSrc: FsFile, folderDst: FsFolder) => // folder converted to file
        val defOps = SyncOperation(folderDst, ActionRemove, fileSrc.level) ::
          SyncOperation(fileSrc, ActionCreate, fileSrc.level) :: state.deferredOps
        val next = state.copy(deferredOps = defOps,
          removedPaths = addRemovedFolder(state, folderDst))
        Some(emitAndPullBoth(Nil, next, stage))

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
                                               (emitFunc: (SyncState, FsElement) =>
                                                 (EmitData, SyncState)):
  Option[(EmitData, SyncState)] =
    if (element == null) {
      if (state.finishedSources > 0)
        Some(EmitData(state.deferredOps, Nil, complete = true), state)
      else {
        val (emit, next) = Option(state.currentElem).map(emitFunc(state, _))
          .getOrElse((EmitNothing, state))
        Some(emit, next.copy(finishedSources = 1))
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

  /**
    * Checks whether the given element belongs to a path in the destination
    * structure that has been removed before. Such elements can be removed
    * directly.
    *
    * @param state   the current sync state
    * @param element the element to be checked
    * @return a flag whether this element is in a removed path
    */
  private def isInRemovedPath(state: SyncState, element: FsElement): Option[FsElement] =
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
  private def removeElement(state: SyncState, element: FsElement, removedPath: Option[FsElement]):
  (List[SyncOperation], SyncState) = {
    val op = SyncOperation(element, ActionRemove,
      removedPath map (_.level) getOrElse element.level)
    element match {
      case folder: FsFolder =>
        val paths = if (removedPath.isDefined) state.removedPaths
        else addRemovedFolder(state, folder)
        (Nil, state.copy(removedPaths = paths, deferredOps = op :: state.deferredOps))
      case _ =>
        (List(op), state)
    }
  }

  /**
    * Adds a folder to the set of removed root paths.
    *
    * @param state  the current sync state
    * @param folder the folder to be added
    * @return the updated set with root paths
    */
  private def addRemovedFolder(state: SyncState, folder: FsFolder): Set[FsElement] =
    state.removedPaths + folder.copy(relativeUri = folder.relativeUri +
      UriEncodingHelper.UriSeparator)

  /**
    * Checks whether the timestamps of the given files are different, taking
    * the configured threshold for the time delta into account.
    *
    * @param eSrc            the source file
    * @param eDst            the destination file
    * @param ignoreTimeDelta the delta in file times to be ignored
    * @return a flag whether these files have a different timestamp
    */
  private def differentFileTimes(eSrc: FsFile, eDst: FsFile, ignoreTimeDelta: Int): Boolean =
    math.abs(extractTime(eSrc) - extractTime(eDst)) > ignoreTimeDelta

  /**
    * Extracts the time of a file that needs to be compared to detect modified
    * files. This method ignores milliseconds as some structures that can be
    * synced do not support file modification times with this granularity.
    *
    * @param file a file
    * @return the modified time to be compared during a sync operations
    */
  private def extractTime(file: FsFile): Long = file.lastModified.getEpochSecond
}

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
class SyncStage(val ignoreTimeDeltaSec: Int = 0)
  extends GraphStage[FanInShape2[FsElement, FsElement, SyncOperation]] {

  import SyncStage._

  val out: Outlet[SyncOperation] = Outlet[SyncOperation]("SyncStage.out")
  val inSource: Inlet[FsElement] = Inlet[FsElement]("SyncStage.inSource")
  val inDest: Inlet[FsElement] = Inlet[FsElement]("SyncStage.inDest")

  /** Convenience field to indicate that the source inlet should be pulled. */
  val PullSource: List[Int] = List(IdxSource)

  /** Convenience field to indicate that the dest inlet should be pulled. */
  val PullDest: List[Int] = List(IdxDest)

  /** Convenience field to indicate that both inlets should be pulled. */
  val PullBoth: List[Int] = List(IdxSource, IdxDest)

  override def shape: FanInShape2[FsElement, FsElement, SyncOperation] =
    new FanInShape2[FsElement, FsElement, SyncOperation](inSource, inDest, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      // Current state of the sync stream
      var state = SyncState(null, waitForElements, 0, Nil, Set.empty)

      // Manually stores the inlets that have been pulled; this is necessary
      // to process onUpstreamFinish notifications at the correct time
      var pulledPorts: Array[Boolean] = Array(true, true)

      setHandler(inSource, new InHandler {
        override def onPush(): Unit = {
          updateState(IdxSource, grab(inSource))
        }

        override def onUpstreamFinish(): Unit = {
          handleInletFinished(IdxSource)
        }
      })

      setHandler(inDest, new InHandler {
        override def onPush(): Unit = {
          updateState(IdxDest, grab(inDest))
        }

        override def onUpstreamFinish(): Unit = {
          handleInletFinished(IdxDest)
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
        emitMultiple(out, data.ops)
        pulledPorts(portIdx) = false
        pullPorts(data)
        if (data.complete) complete(out)
      }

      /**
        * Pulls the input ports referenced by the given ''EmitData''. This
        * method also checks whether one of the ports to be pulled is already
        * finished; if so, a close notification is sent by invoking
        * ''updateState()'' recursively.
        *
        * @param data the ''EmitData'' object
        */
      private def pullPorts(data: EmitData): Unit = {
        data.pullInletsIdx foreach { idx =>
          val in = inlet(idx)
          pulledPorts(idx) = true
          if (isClosed(in))
            updateState(idx, null)
          else pull(in)
        }
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
        * @param portIdx the index of the port affected
        */
      private def handleInletFinished(portIdx: Int): Unit = {
        if (pulledPorts(portIdx))
          updateState(portIdx, null)
      }
    }

  /**
    * Returns the inlet with the given index.
    *
    * @param portIdx the port index
    * @return the inlet with this index
    */
  private def inlet(portIdx: Int): Inlet[FsElement] =
    if (portIdx == IdxSource) inSource else inDest
}
