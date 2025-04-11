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

import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder, SyncAction, SyncConflictException, SyncElementResult, SyncOperation, compareElements}
import com.github.sync.stream.BaseMergeStage.{Input, MergeEmitData}
import com.github.sync.stream.LocalStateStage.{ChangeType, ElementWithDelta}
import com.github.sync.stream.RemovedFolderConflictHandler.HandlerResult
import com.github.sync.stream.SyncStage.SyncStageResult
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, StageLogging}
import org.apache.pekko.stream.{Attributes, FanInShape2, Inlet, Outlet, Shape}

import java.time.Instant

private object SyncStage:
  /**
    * A data class to represent a result produced by [[SyncStage]].
    *
    * In addition to the actual [[SyncElementResult]], further information is
    * required to update the local state. The class therefore contains an
    * optional [[ElementWithDelta]] with the original local element, for which 
    * this result was generated. So, if an update operation fails, this element 
    * can be written to the new local state. If no original local element is
    * available (if it was newly created), the property is undefined.
    *
    * @param elementResult   the actual result
    * @param optLocalElement an option with the original local element
    */
  case class SyncStageResult(elementResult: SyncElementResult,
                             optLocalElement: Option[ElementWithDelta])

  /**
    * An internal class implementing the logic of the sync stage and holding
    * its state.
    *
    * @param shape           the shape
    * @param in1             the inlet for the first input source
    * @param in2             the inlet for the second input source
    * @param out             the outlet of this stage
    * @param ignoreTimeDelta a time difference that is to be ignored when
    *                        comparing two files
    */
  private class SyncStageLogic(shape: Shape,
                               in1: Inlet[ElementWithDelta],
                               in2: Inlet[FsElement],
                               out: Outlet[SyncStageResult],
                               ignoreTimeDelta: IgnoreTimeDelta)
    extends GraphStageLogic(shape),
      BaseMergeStage[ElementWithDelta, FsElement, SyncStageResult](in1, in2, out),
      StageLogging:

    /**
      * A data class representing the state of this stage.
      *
      * @param mergeFunc             the current merge function
      * @param currentLocalElement   optional local element to be synced
      * @param currentRemoteElement  optional remote element to be synced
      * @param remoteConflictHandler the handler for conflicts in remote folder
      *                              removal operations
      * @param localConflictHandler  the handler for conflicts in local folder
      *                              removal operations
      */
    case class SyncState(override val mergeFunc: MergeFunc,
                         currentLocalElement: Option[ElementWithDelta],
                         currentRemoteElement: Option[FsElement],
                         remoteConflictHandler: RemovedFolderConflictHandler[SyncState],
                         localConflictHandler: RemovedFolderConflictHandler[SyncState])
      extends BaseMergeState:
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
                                       pullInlets: Iterable[Input]): HandlerResult[SyncState] =
        def nextCurrent[T](input: Input, value: => Option[T]): Option[T] =
          if pullInlets.exists(_ == input) then None else value

        val nextCurrentLocal = nextCurrent(Input.Inlet1, currentLocalElement)
        val nextCurrentRemote = nextCurrent(Input.Inlet2, currentRemoteElement)
        (MergeEmitData(elements, pullInlets),
          copy(currentLocalElement = nextCurrentLocal, currentRemoteElement = nextCurrentRemote))

    end SyncState

    override type MergeState = SyncState

    /** Holds the current state of this stage. */
    private var syncState = SyncState(sync, None, None,
      new RemovedFolderConflictHandler[SyncState](RemovedFolderState.Empty, Map.empty,
        SyncAction.ActionLocalRemove, updateRemoteConflictState, RemovedFolderConflictHandler.LocalConflictFunc,
        noopForDeferredElem = true),
      new RemovedFolderConflictHandler[SyncState](RemovedFolderState.Empty, Map.empty,
        SyncAction.ActionRemove, updateLocalConflictState, RemovedFolderConflictHandler.RemoteConflictFunc))

    override protected def state: SyncState = syncState

    override protected def updateState(newState: SyncState): Unit = syncState = newState

    /**
      * The update function for the remote [[RemovedFolderConflictHandler]].
      *
      * @param state   the current sync state
      * @param handler the handler to update
      * @return the updated sync state
      */
    private def updateRemoteConflictState(state: SyncState,
                                          handler: RemovedFolderConflictHandler[SyncState]): SyncState =
      state.copy(remoteConflictHandler = handler)

    /**
      * The update function for the local [[RemovedFolderConflictHandler]].
      *
      * @param state   the current sync state
      * @param handler the handler to update
      * @return the updated sync state
      */
    private def updateLocalConflictState(state: SyncState,
                                         handler: RemovedFolderConflictHandler[SyncState]): SyncState =
      state.copy(localConflictHandler = handler)

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
        case None =>
          val elements =
            state.remoteConflictHandler.remainingResults() ::: state.localConflictHandler.remainingResults()
          val results = elements.map(elemResult => SyncStageResult(elemResult, optLocalElement = None))
          (MergeEmitData(results, Nil, complete = true), state)
        case Some(elem: FsElement) =>
          syncLocalGreaterRemote(state, elem)
        case Some(elem: ElementWithDelta) =>
          syncLocalLessRemote(state, elem)

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
      math.signum(compareElements(local.element, remote)) match
        case 1 =>
          syncLocalGreaterRemote(state, remote)

        case -1 =>
          syncLocalLessRemote(state, local)

        case _ => // elements are equal
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
      def overrideOp() = SyncOperation(local.element, SyncAction.ActionOverride, remote.level, remote.id)

      def removeOp() = RemovedFolderState.createRemoveOperation(remote)

      def localOverrideOp() = SyncOperation(remote, SyncAction.ActionLocalOverride, remote.level, local.element.id)

      mapResult(state.localConflictHandler.handleElement(state, remote, resultFunc(BaseMergeStage.PullBoth),
        conflictsForLocalRemovedFolder(remote, local)) {
        state.remoteConflictHandler.handleElement(state, remote, resultFunc(BaseMergeStage.PullBoth), Nil) {
          val results = syncFileFolderReplacements(local, remote) getOrElse {
            val localModified = local.element.modifiedTime(Instant.MIN)
            val remoteModified = remote.modifiedTime(Instant.MIN)
            val lastLocalTime = if local.lastLocalTime == null then Instant.MIN else local.lastLocalTime

            local.changeType match
              case ChangeType.Removed if ignoreTimeDelta.isDifferentTimes(remoteModified, lastLocalTime) =>
                emitConflict(SyncOperation(remote, SyncAction.ActionLocalCreate, remote.level, remote.id), removeOp())
              case ChangeType.Removed =>
                emitOp(removeOp())
              case _ if !ignoreTimeDelta.isDifferentTimes(localModified, remoteModified) =>
                emitOp(noop(local, remote))
              case ChangeType.Changed if ignoreTimeDelta.isDifferentTimes(remoteModified, lastLocalTime) =>
                emitConflict(localOverrideOp(), overrideOp())
              case ChangeType.Changed =>
                emitOp(overrideOp())
              case ChangeType.Unchanged if ignoreTimeDelta.isDifferentTimes(localModified, remoteModified) =>
                emitOp(localOverrideOp())
              case ChangeType.Created =>
                emitConflict(localOverrideOp(), overrideOp())
              case _ =>
                // This basically means ChangeType.Unchanged, since ChangeType.TypeChanged is dealt with in
                // syncFileFolderReplacements().
                emitOp(noop(local, remote))
          }

          state.mergeResultWithResetElements(results, BaseMergeStage.PullBoth)
        }
      }, Some(local))

    /**
      * Handles corner cases when syncing two equivalent elements that are
      * related to files converted to folders or vice versa. If such a case is
      * detected, the function returns a defined ''Option''; otherwise, result
      * is ''None''.
      *
      * @param local  the current local element
      * @param remote the current remove element
      * @return an option with operations to handle this case
      */
    private def syncFileFolderReplacements(local: ElementWithDelta, remote: FsElement):
    Option[List[SyncElementResult]] =
      (local.element, remote) match
        case (localFolder: FsFolder, remoteFile: FsFile) if local.changeType == ChangeType.TypeChanged &&
          remoteFile.lastModified == local.lastLocalTime =>
          val opRemoveFile = RemovedFolderState.createRemoveOperation(remoteFile)
          val opCreateFolder = SyncOperation(localFolder, SyncAction.ActionCreate, localFolder.level, localFolder.id)
          Some(List(Right(List(opRemoveFile, opCreateFolder))))

        case (localFolder: FsFolder, remoteFile: FsFile) if local.changeType == ChangeType.TypeChanged =>
          val localOperations = List(RemovedFolderState.createRemoveOperation(localFolder,
            action = SyncAction.ActionLocalRemove),
            SyncOperation(remoteFile, SyncAction.ActionLocalCreate, remoteFile.level, remoteFile.id))
          val remoteOperations = List(RemovedFolderState.createRemoveOperation(remoteFile),
            SyncOperation(localFolder, SyncAction.ActionCreate, localFolder.level, localFolder.id))
          Some(List(Left(SyncConflictException(localOperations = localOperations,
            remoteOperations = remoteOperations))))

        case (localFolder: FsFolder, remoteFile: FsFile) =>
          val opRemoveFolder = RemovedFolderState.createRemoveOperation(localFolder,
            action = SyncAction.ActionLocalRemove)
          val opCreateFile = SyncOperation(remoteFile, SyncAction.ActionLocalCreate, remoteFile.level, remoteFile.id)
          Some(List(Right(List(opRemoveFolder, opCreateFile))))

        case (localFile: FsFile, _: FsFolder) if local.changeType == ChangeType.TypeChanged =>
          val opRemoveFolder = RemovedFolderState.createRemoveOperation(remote)
          val opCreateFile = SyncOperation(localFile, SyncAction.ActionCreate, localFile.level, localFile.id)
          Some(List(Right(List(opRemoveFolder, opCreateFile))))

        case (localFile: FsFile, remoteFolder: FsFolder) if local.changeType == ChangeType.Changed ||
          local.changeType == ChangeType.Created =>
          val localOperations = List(RemovedFolderState.createRemoveOperation(localFile,
            action = SyncAction.ActionLocalRemove),
            SyncOperation(remoteFolder, SyncAction.ActionLocalCreate, remoteFolder.level, remoteFolder.id))
          val remoteOperations = List(RemovedFolderState.createRemoveOperation(remoteFolder),
            SyncOperation(localFile, SyncAction.ActionCreate, localFile.level, localFile.id))
          Some(List(Left(SyncConflictException(localOperations = localOperations,
            remoteOperations = remoteOperations))))

        case (localFile: FsFile, remoteFolder: FsFolder) if local.changeType == ChangeType.Unchanged =>
          val opRemoveFile = RemovedFolderState.createRemoveOperation(localFile, action = SyncAction.ActionLocalRemove)
          val opCreateFolder = SyncOperation(remoteFolder, SyncAction.ActionLocalCreate, remoteFolder.level,
            remoteFolder.id)
          Some(List(Right(List(opRemoveFile, opCreateFolder))))

        case _ => None

    /**
      * Produces a ''MergeResult'' if the current local element's URI is less
      * than the one of the current remote element.
      *
      * @param state the current state
      * @param local the current local element
      * @return data to be emitted and the updated merge state
      */
    private def syncLocalLessRemote(state: SyncState, local: ElementWithDelta): MergeResult =
      def createOp(action: SyncAction): SyncOperation =
        SyncOperation(local.element, action, local.element.level, local.element.id)

      mapResult(state.remoteConflictHandler.handleElement(state, local.element, resultFunc(BaseMergeStage.Pull1),
        conflictsForRemoteRemovedFolder(local)) {
        val ops = local.changeType match
          case ChangeType.Unchanged =>
            emitOp(createOp(SyncAction.ActionLocalRemove))
          case ChangeType.Removed =>
            Nil
          case ChangeType.Changed =>
            emitConflict(createOp(SyncAction.ActionLocalRemove), createOp(SyncAction.ActionCreate))
          case ChangeType.Created =>
            emitOp(createOp(SyncAction.ActionCreate))
          case ChangeType.TypeChanged =>
            emitConflict(RemovedFolderState.createRemoveOperation(local.element, action = SyncAction.ActionLocalRemove),
              SyncOperation(local.element, SyncAction.ActionCreate, local.element.level, local.element.id))

        state.mergeResultWithResetElements(ops, BaseMergeStage.Pull1)
      }, Some(local))

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
      mapResult(state.localConflictHandler.handleElement(state, remote, resultFunc(BaseMergeStage.Pull2), List(op)) {
        state.mergeResultWithResetElements(emitOp(op), BaseMergeStage.Pull2)
      }, None)

    /**
      * Checks the given element for a conflict with an ongoing operation to
      * remove a remote folder. If there was a change on the local element, a
      * conflict is reported.
      *
      * @param element the current local element
      * @return a list with conflicting sync operations
      */
    private def conflictsForRemoteRemovedFolder(element: ElementWithDelta): List[SyncOperation] =
      if element.changeType == ChangeType.Changed || element.changeType == ChangeType.Created then
        List(SyncOperation(element.element, SyncAction.ActionCreate, element.element.level, element.element.id))
      else Nil

    /**
      * Checks the given remote and local elements for a conflict with an
      * ongoing operation to remove a local folder. If a remote file has a
      * different modified time than recorded locally, a conflict is reported.
      *
      * @param remote the remote element
      * @param local  the local element
      * @return a list with conflicting operations
      */
    private def conflictsForLocalRemovedFolder(remote: FsElement, local: ElementWithDelta): List[SyncOperation] =
      if remote.modifiedTime(null) != local.lastLocalTime then
        List(SyncOperation(remote, SyncAction.ActionLocalCreate, remote.level, local.element.id))
      else Nil

    /**
      * Returns the function to create results in behalf of the conflict
      * handlers for remove folder operations. The inlets to be pulled can be
      * configured. In addition, the current elements of the state must be
      * updated accordingly.
      *
      * @param pullInlets the inlets to pull
      * @param state      the current sync state
      * @param elements   the elements to emit
      * @return the result of the current operations
      */
    private def resultFunc(pullInlets: Set[BaseMergeStage.Input])
                          (state: SyncState, elements: List[SyncElementResult]): HandlerResult[MergeState] =
      state.mergeResultWithResetElements(elements, pullInlets)

    /**
      * Maps a result based on [[SyncElementResult]] to a result based on
      * [[SyncStageResult]].
      *
      * @param result       the original result
      * @param optLocalElem the optional local element
      * @return the resulting [[SyncStageResult]]
      */
    private def mapResult(result: HandlerResult[SyncState], optLocalElem: Option[ElementWithDelta]): MergeResult =
      val resultElems = result._1.elements.map(res => SyncStageResult(res, optLocalElem))
      (result._1.copy(elements = resultElems), result._2)

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
    * Convenience function to produce a conflict sync result for two
    * conflicting operations.
    *
    * @param localOp  the local operation
    * @param remoteOp the remote operation
    * @return the resulting list of results
    */
  private def emitConflict(localOp: SyncOperation, remoteOp: SyncOperation): List[SyncElementResult] =
    val conflictException = SyncConflictException(List(localOp), List(remoteOp))
    List(Left(conflictException))

  /**
    * Generates a ''SyncOperation'' with a Noop action for the given elements.
    *
    * @param local  the current local element
    * @param remote the current remote element
    * @return the Noop ''SyncOperation'' for these elements
    */
  private def noop(local: ElementWithDelta, remote: FsElement): SyncOperation =
    SyncOperation(local.element, SyncAction.ActionNoop, remote.level, remote.id)

/**
  * A special stage that generates [[SyncOperation]]s for a local and a remote
  * input source.
  *
  * This stage implements bidirectional sync logic between two sources. One
  * source is considered local, since its state of the last sync operation is
  * known. Therefore, conflicts caused by changes on elements in both sources
  * can be detected.
  *
  * @param ignoreTimeDelta a time difference that is to be ignored when
  *                        comparing two files
  */
private class SyncStage(ignoreTimeDelta: IgnoreTimeDelta = IgnoreTimeDelta.Zero)
  extends GraphStage[FanInShape2[ElementWithDelta, FsElement, SyncStageResult]]:
  val out: Outlet[SyncStageResult] = Outlet[SyncStageResult]("SyncStage.out")
  val inLocal: Inlet[ElementWithDelta] = Inlet[ElementWithDelta]("SyncStage.inLocal")
  val inRemote: Inlet[FsElement] = Inlet[FsElement]("SyncStage.inRemote")

  override def shape: FanInShape2[ElementWithDelta, FsElement, SyncStageResult] =
    new FanInShape2[ElementWithDelta, FsElement, SyncStageResult](inLocal, inRemote, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    SyncStage.SyncStageLogic(shape, inLocal, inRemote, out, ignoreTimeDelta)
