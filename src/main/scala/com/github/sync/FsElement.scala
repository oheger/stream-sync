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

package com.github.sync

import java.time.Instant

/**
  * A trait representing an element that can occur in the file system.
  *
  * This trait defines some basic properties of such elements that are relevant
  * for the sync algorithm. There are concrete implementations for the items
  * that are handled by the sync engine; those can define additional attributes
  * that may be of interest.
  */
sealed trait FsElement {
  /**
    * Returns the URI of this file system element relative to the root URI of
    * the source that is synced.
    *
    * @return the relative URI of this element
    */
  def relativeUri: String

  /**
    * Returns the level of this file system element in the structure that is
    * subject of the current sync operation. The level is the distance of this
    * element to the root folder. Elements contained in the root directory have
    * level 1, the elements in a sub folder of the root directory have level 2
    * and so on.
    *
    * @return the level of this element
    */
  def level: Int
}

/**
  * A class representing a file in a file system to be synced.
  *
  * This class defines some additional attributes relevant for files.
  *
  * @param relativeUri  the relative URI of this file
  * @param level        the level of this file
  * @param lastModified the time of the last modification
  * @param size         the file size (in bytes)
  */
case class FsFile(override val relativeUri: String,
                  override val level: Int,
                  lastModified: Instant,
                  size: Long) extends FsElement

/**
  * A class representing a folder in a file system to be synced.
  *
  * @param relativeUri the relative URI of this folder
  * @param level       the level of this folder
  */
case class FsFolder(override val relativeUri: String,
                    override val level: Int) extends FsElement
