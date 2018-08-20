/*
 * Copyright 2018 The Developers Team.
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

package com.github.sync

/**
  * A trait representing an action to be applied on an element during a sync
  * operation.
  */
sealed trait SyncAction

/**
  * A special ''SyncAction'' stating that an element should be newly created in
  * the destination structure.
  */
case object ActionCreate extends SyncAction

/**
  * A special ''SyncAction'' stating that an element from the source structure
  * should replace the corresponding one in the destination structure.
  */
case object ActionOverride extends SyncAction

/**
  * A special ''SyncAction'' stating that an element should be removed from the
  * destination structure.
  */
case object ActionRemove extends SyncAction

/**
  * A class that stores all information for a single sync operation.
  *
  * Subject of the operation is an element (a folder or a file), for which an
  * action is to be executed. The operation also has a level which corresponds
  * to the level of the element in the source structure that triggered it.
  * (Note that this does not necessarily correspond to the level of the element
  * associated with the operation.) The level can be used in filter expressions
  * to customize sync behavior.
  *
  * @param element the element that is subject to this operation
  * @param action  the action to be executed on this element
  */
case class SyncOperation(element: FsElement, action: SyncAction, level: Int)
