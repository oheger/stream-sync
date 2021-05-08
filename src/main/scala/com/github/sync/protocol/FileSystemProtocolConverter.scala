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

package com.github.sync.protocol

import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder}

/**
  * A trait defining a number of conversion operations that are needed to
  * implement the [[SyncProtocol]] trait on top of a ''FileSystem''.
  *
  * The access to specific data structures is done via the ''FileSystem'' API
  * of the CloudFiles project. A sync process uses its own representation of
  * files and folders containing the relevant properties to derive the
  * necessary sync operations. These representations must be converted (in both
  * directions) to match the data used by the file system. Typically, there
  * needs to be a specific converter implementation for the concrete
  * ''FileSystem'' class implementing a protocol. The representations used by
  * the ''FileSystem'' are reflected by the type parameters of this trait.
  *
  * @tparam ID     the type used for element IDs
  * @tparam FILE   the type used for files
  * @tparam FOLDER the type used for folders
  */
trait FileSystemProtocolConverter[ID, FILE, FOLDER] {
  /**
    * Converts the given string to an ID used by the associated file system.
    *
    * @param strID the string representation of the ID
    * @return the corresponding file system ID
    */
  def elementIDFromString(strID: String): ID

  /**
    * Extracts the ID of a specific element from the given object. This
    * implementation calls ''elementIDFromString()'' on the ID value of the
    * passed in element.
    *
    * @param elem the element
    * @return the ID of this element
    */
  def elementID(elem: FsElement): ID = elementIDFromString(elem.id)

  /**
    * Converts the given sync file element to a file in terms of the associated
    * ''FileSystem''.
    *
    * @param fileElement the file element
    * @param name        the file name (extracted from the path)
    * @return the file for this file system
    */
  def toFsFile(fileElement: FsFile, name: String): FILE

  /**
    * Converts the given sync folder element to a folder in terms of the
    * associated ''FileSystem''.
    *
    * @param folderElement the folder element
    * @param name the folder name (extracted from the path)
    * @return the folder for this file system
    */
  def toFsFolder(folderElement: FsFolder, name: String): FOLDER

  /**
    * Converts the given ''FileSystem'' file to the representation used by a
    * sync process.
    *
    * @param file  the file
    * @param path  the path to the parent folder
    * @param level the level of the file
    * @return the sync file element
    */
  def toFileElement(file: FILE, path: String, level: Int): FsFile

  /**
    * Converts the given ''FileSystem'' folder to the representation used by a
    * sync process.
    *
    * @param folder the folder
    * @param path   the path to the parent folder
    * @param level  the level of the file
    * @return the sync folder element
    */
  def toFolderElement(folder: FOLDER, path: String, level: Int): FsFolder
}
