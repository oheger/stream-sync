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

import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.sync.SyncTypes.{FsElement, FsFolder}

private object NormalizedFolder:
  /**
    * Creates a new instance of ''NormalizedFolder'' that represents the given
    * folder.
    *
    * @param folder the original folder
    * @return the ''NormalizedFolder'' for this folder
    */
  def apply(folder: FsFolder): NormalizedFolder =
    NormalizedFolder(folder, UriEncodingHelper withTrailingSeparator folder.relativeUri)

/**
  * A class representing a folder with its normalized URI.
  *
  * For sync processes, it is sometimes required to check whether an element is
  * a child (directly or indirectly) of a specific folder. To do this in a
  * reliable way, it must be guaranteed that the URI used to check this is
  * normalized (i.e. ends on a separator). This class makes sure that this
  * criterion is fulfilled and provides some functions to check the relations
  * of elements to the represented folder.
  *
  * @param folder        the represented folder
  * @param normalizedUri the normalized URI of this folder
  */
private case class NormalizedFolder private(folder: FsFolder,
                                            normalizedUri: String):
  /**
    * Returns a flag whether the given element is in the tree of elements
    * spawned by this folder. This means that it is a direct or indirect child
    * of this folder.
    *
    * @param element the element in question
    * @return a flag whether this element is in the tree spawned by this folder
    */
  def isInTree(element: FsElement): Boolean = element.relativeUri.startsWith(normalizedUri)

  /**
    * Returns a flag whether the given element comes after any direct child
    * element (in the order used to iterate elements) of this folder. This
    * function can be used to find out whether all child elements of this
    * folder (in the next level) have been fully iterated over. (Note that
    * this function does not work over multiple levels, as it does not take the
    * trees spawned by child folders into account.)
    *
    * @param element the element in question
    * @return a flag whether this element is after the iteration of the direct
    *         children of this folder
    */
  def isChildIterationComplete(element: FsElement): Boolean =
    (element.level == folder.level + 1 && element.relativeUri > folder.relativeUri && !isInTree(element)) ||
      element.level > folder.level + 1

