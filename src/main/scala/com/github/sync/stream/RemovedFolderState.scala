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
import com.github.sync.SyncTypes.{FsElement, FsFolder, SyncAction, SyncOperation}

private object RemovedFolderState:
  /** Constant representing an initial empty state. */
  final val Empty = RemovedFolderState(Set.empty, List.empty)

  /**
    * Convenience function to create a ''SyncOperation'' for the given element,
    * level, and action (typically a remove or a local remove action).
    *
    * @param element  the element subject of the operation
    * @param optLevel the optional level of the operation (defaults to the
    *                 element's level)
    * @param action   the action of the operation
    * @return the newly created ''SyncOperation''
    */
  def createRemoveOperation(element: FsElement, optLevel: Option[Int] = None,
                            action: SyncAction = SyncAction.ActionRemove): SyncOperation =
    SyncOperation(element, action, optLevel getOrElse element.level, element.id)

/**
  * A helper class to manage folders that have been removed during a sync
  * process.
  *
  * Dealing with removed folders can be tricky for multiple reasons:
  *  - A folder cannot be safely removed directly, but only after all its
  *    subelements have been processed. Therefore, the remove operations are
  *    delayed until the end of the sync process.
  *  - When processing the content of a removed folder, the information that
  *    the elements encountered also need to be removed, must be available;
  *    this needs to be tracked over the whole subtree.
  *
  * This supports in implementing these requirements. It stores the roots of
  * directory trees that need to be removed and offers functionality to check
  * whether an element is part of such a tree.
  *
  * @param roots              the current set of roots that have been removed
  * @param deferredOperations the operations to be executed at the end of the
  *                           sync process
  */
private case class RemovedFolderState(roots: Set[NormalizedFolder],
                                      deferredOperations: List[SyncOperation]):

  import RemovedFolderState.*

  /**
    * Tries to find a root folder that spans a tree, which contains the given
    * element. If such a folder exists, its normalized form is returned in a
    * ''Some''; otherwise, result is ''None''.
    *
    * @param element the element to check
    * @return an ''Option'' with the normalized removed root folder of this
    *         element
    */
  def findRoot(element: FsElement): Option[NormalizedFolder] =
    roots find { root => root.isInTree(element) }

  /**
    * Creates a ''SyncOperation'' for the given element and returns an updated
    * state object. This function must be called for each element that is
    * removed during the sync process. It contains the logic to defer remove
    * operations for folders and to keep track of new removed root folders.
    * The ''optRoot'' parameter can point to a removed root folder, which is a
    * (direct or indirect) parent of this element; this is normally the result
    * of a ''findRoot()'' call. Result is a list with the ''SyncOperation''s to
    * execute immediately (which can contain at most 1 element, but for stages
    * doing sync operations a list is typically more convenient than an option)
    * and the possibly modified state.
    *
    * @param element the removed element
    * @param action  the action for the sync operation (typically a remove or
    *                a local remove action)
    * @param optRoot the optional removed parent folder of the element
    * @return the operations to execute directly and the updated state
    */
  def handleRemoveElement(element: FsElement, action: SyncAction, optRoot: Option[NormalizedFolder]):
  (List[SyncOperation], RemovedFolderState) =
    val op = createRemoveOperation(element, optRoot map (_.folder.level), action)
    element match
      case _: FsFolder if optRoot.isDefined =>
        (Nil, copy(deferredOperations = op :: deferredOperations))
      case folder: FsFolder =>
        val newRoot = folder.toNormalizedFolder
        (Nil, copy(roots = roots + newRoot, deferredOperations = op :: deferredOperations))
      case _ =>
        (List(op), this)

  /**
    * Adds another folder to the set of removed root folders. This function can
    * be used for folders that are removed by other operations not handled by
    * ''handleRemoveElement()''; e.g. when a folder is replaced by a file.
    *
    * @param folder the folder to add
    * @return the updated state containing this folder
    */
  def addRoot(folder: FsFolder): RemovedFolderState = addRoot(folder.toNormalizedFolder)

  /**
    * Adds another ''NormalizedFolder'' to the set of removed root folders.
    * This function can be used for folders that are removed by other
    * operations not handled by ''handleRemoveElement()''; e.g. when a folder
    * is replaced by a file.
    *
    * @param normalizedFolder the normalized folder to add
    * @return the updated state containing this folder
    */
  def addRoot(normalizedFolder: NormalizedFolder): RemovedFolderState = copy(roots = roots + normalizedFolder)
  
  