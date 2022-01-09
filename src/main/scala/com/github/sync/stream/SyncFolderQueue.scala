/*
 * Copyright 2018-2022 The Developers Team.
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

import com.github.sync.SyncTypes.SyncFolderData

import scala.collection.SortedSet

private object SyncFolderQueue:
  /**
    * Creates a new instance of ''SyncFolderQueue'' with the given initial
    * element. This is typically the start folder for the iteration over the
    * structure serving as input of a sync process.
    *
    * @param init the initial element
    * @return the new queue instance
    */
  def apply(init: SyncFolderData): SyncFolderQueue =
    new SyncFolderQueue(SortedSet(init))

/**
  * A specialized queue class for managing pending folders during a sync
  * process.
  *
  * The sub folders of a folder have to be processed in a specific order to
  * make the sync process work correctly. Sync input sources must ensure this
  * order. To support them, this class is provided. It stores elements of
  * type ''SyncFolderData'' (which can be ordered) and implements typical queue
  * operations. It is ensured that elements are obtained from the queue in the
  * correct order.
  *
  * @param data the set with the data objects contained in this queue
  */
private class SyncFolderQueue private(data: SortedSet[SyncFolderData]):
  /**
    * Returns a new instance that contains the specified element.
    *
    * @param elem the element to be added
    * @return the updated instance with this element
    */
  def +(elem: SyncFolderData): SyncFolderQueue =
    new SyncFolderQueue(data.union(Set(elem)))

  /**
    * Returns a new instance that contains the specified elements.
    *
    * @param elems a sequence with the elements to be added
    * @return the updated instance with these elements
    */
  def ++(elems: Iterable[SyncFolderData]): SyncFolderQueue =
    new SyncFolderQueue(data ++ elems)

  /**
    * Removes the first element from this queue and returns a tuple with this
    * element and the updated instance. If this queue is empty, an exception is
    * thrown.
    *
    * @return a tuple with the first element and the updated queue
    * @throws NoSuchElementException if the queue is empty
    */
  def dequeue(): (SyncFolderData, SyncFolderQueue) =
    val firstElem = data.firstKey
    (firstElem, new SyncFolderQueue(data.diff(Set(firstElem))))

  /**
    * Returns a flag whether this queue is empty.
    *
    * @return '''true''' if this queue is empty; '''false''' otherwise
    */
  def isEmpty: Boolean = data.isEmpty

  /**
    * Returns a flag whether this queue is not empty.
    *
    * @return '''true''' if this queue is not empty; '''false''' otherwise
    */
  def nonEmpty: Boolean = data.nonEmpty
