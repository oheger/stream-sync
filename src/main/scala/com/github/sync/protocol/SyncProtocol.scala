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

package com.github.sync.protocol

import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import scala.concurrent.Future

/**
  * A trait defining the set of operations required by a sync process.
  *
  * Via the operations defined here files and folders are updated during a sync
  * process. In order for a specific protocol (such as local file system,
  * OneDrive, etc.) an implementation of this trait must exist. As a
  * generalization, IDs are represented as strings; protocols using other ID
  * values must perform a conversion.
  *
  * All operations are asynchronous and can fail; hence they return a
  * ''Future''. For the operations that update the destination structure, it is
  * only relevant whether they are successful or fail; because of that the
  * futures are of type ''Unit''. To make sure that resources can be safely
  * released when an instance is no longer needed, the trait extends
  * ''AutoCloseable''.
  */
trait SyncProtocol extends AutoCloseable:
  /**
    * Returns the [[Source]] for iterating over all elements in the structure to
    * be synced. This becomes the source of the sync stream.
    *
    * @return the [[Source]] yielding the elements to be synced
    */
  def elementSource: Future[Source[FsElement, NotUsed]]

  /**
    * Removes the file with the given ID.
    *
    * @param id the ID of the file affected
    * @return a ''Future'' with the result of the operation
    */
  def removeFile(id: String): Future[Unit]

  /**
    * Removes the folder with the given ID.
    *
    * @param id the ID of the folder affected
    * @return a ''Future'' with the result of the operation
    */
  def removeFolder(id: String): Future[Unit]

  /**
    * Creates a new folder. The properties of the new folder are specified in
    * the passed in folder object.
    *
    * @param parentPath the path to the parent folder
    * @param name       the name of the new folder (already extracted from its
    *                   path)
    * @param folder     the data of the new folder
    * @return a ''Future'' with the result of the operation
    */
  def createFolder(parentPath: String, name: String, folder: FsFolder): Future[Unit]

  /**
    * Creates a new file. The properties of the new file are specified in the
    * passed in file object, its content is defined by the given source. The
    * size of the file should be present in the file object.
    *
    * @param parentPath the path to the parent folder
    * @param name       the name of the new file (already extracted from its
    *                   path)
    * @param file       the data of the new file
    * @param source     a source with the file content
    * @return a ''Future'' with the result of the operation
    */
  def createFile(parentPath: String, name: String, file: FsFile, source: Source[ByteString, Any]): Future[Unit]

  /**
    * Updates a specific file, especially by uploading new content.
    *
    * @param file   the data of the file to be updated
    * @param source a source with the new content of the file
    * @return a ''Future'' with the result of the operation
    */
  def updateFile(file: FsFile, source: Source[ByteString, Any]): Future[Unit]

  /**
    * Returns a source to download the content of a file. This function is
    * called if from the source structure data needs to be copied to the
    * destination structure.
    *
    * @param id the ID of the file to download
    * @return a ''Future'' with a source to obtain the file's content
    */
  def downloadFile(id: String): Future[Source[ByteString, Any]]
