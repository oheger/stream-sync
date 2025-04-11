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

import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder}

import java.time.Instant

extension (elem: FsElement)

  /**
    * Determines the last modified time from an element, no matter whether it is
    * a file or a folder. When comparing elements during a sync process it is
    * often required to compare the times of last modification for these
    * elements. Rather than checking for the concrete element types, this
    * function allows direct access to the last modification time. If the element
    * is a file, its ''lastModified'' property is returned; otherwise, result is
    * the provided folder time.
    */
  def modifiedTime(folderTime: => Instant): Instant = elem match
    case file: FsFile => file.lastModified
    case _: FsFolder => folderTime

extension (folder: FsFolder)

  /**
    * Returns a ''NormalizedFolder'' initialized with this folder.
    */
  def toNormalizedFolder: NormalizedFolder = NormalizedFolder(folder)
