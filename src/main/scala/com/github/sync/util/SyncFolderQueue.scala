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

package com.github.sync.util

import com.github.sync.SyncTypes.FsFolder

import scala.collection.SortedSet

/**
  * A trait describing the elements that can be stored in a
  * [[SyncFolderQueue]].
  *
  * An client can store use case-specific data, but to make the queue working,
  * a minimum set of properties must be provided. Based on these properties, an
  * ''Ordering'' implementation is provided.
  */
trait SyncFolderData {
  /**
    * Returns the represented ''FsFolder'' object.
    *
    * @return the folder
    */
  def folder: FsFolder

  /**
    * Returns the URI of the represented folder.
    *
    * @return the folder URI
    */
  def uri: String = folder.relativeUri

  /**
    * Returns the level of the represented folder.
    *
    * @return the level of the folder
    */
  def level: Int = folder.level
}

object SyncFolderQueue {
  /**
    * Creates a new instance of ''SyncFolderQueue'' with the given initial
    * element. This is typically the start folder for the iteration over the
    * structure serving as input of a sync process.
    *
    * @param init the initial element
    * @param o    an ''Ordering'' instance for the element type
    * @tparam T the type of elements managed by the queue
    * @return the new queue instance
    */
  def apply[T <: SyncFolderData](init: T)(implicit o: Ordering[T]): SyncFolderQueue[T] = {
    new SyncFolderQueue[T](SortedSet(init))
  }

  /**
    * Provides an implicit ordering for the given type derived from
    * [[SyncFolderData]]. This is required to use the queue for such types.
    *
    * @tparam T the type
    * @return the ordering for this type
    */
  implicit def derivedOrdering[T <: SyncFolderData]: Ordering[T] = (x: T, y: T) => {
    val deltaLevel = x.level - y.level
    if (deltaLevel != 0) deltaLevel
    else x.uri.compareTo(y.uri)
  }
}

/**
  * A specialized queue class for managing pending folders during a sync
  * process.
  *
  * The sub folders of a folder have to be processed in a specific order to
  * make the sync process work correctly. Sync input sources must ensure this
  * order. To support them, this class is provided. It stores elements of a
  * given type (which can be ordered) and implements typical queue operations.
  * It is ensured that elements are obtained from the queue in the correct
  * order.
  *
  * @tparam T the type of elements stored in the queue
  */
class SyncFolderQueue[T <: SyncFolderData] private(data: SortedSet[T]) {
  /**
    * Returns a new instance that contains the specified element.
    *
    * @param elem the element to be added
    * @return the updated instance with this element
    */
  def +(elem: T): SyncFolderQueue[T] =
    new SyncFolderQueue(data + elem)

  /**
    * Returns a new instance that contains the specified elements.
    *
    * @param elems a sequence with the elements to be added
    * @return the updated instance with these elements
    */
  def ++(elems: Iterable[T]): SyncFolderQueue[T] =
    new SyncFolderQueue(data ++ elems)

  /**
    * Removes the first element from this queue and returns a tuple with this
    * element and the updated instance. If this queue is empty, an exception is
    * thrown.
    *
    * @return a tuple with the first element and the updated queue
    * @throws NoSuchElementException if the queue is empty
    */
  def dequeue(): (T, SyncFolderQueue[T]) = {
    val firstElem = data.firstKey
    (firstElem, new SyncFolderQueue(data - firstElem))
  }

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
}
