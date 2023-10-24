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

package com.github.sync.protocol

import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.sync.SyncTypes
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import scala.concurrent.{ExecutionContext, Future}

/**
  * An implementation of [[SyncProtocol]] that uses a [[FileSystem]] to access
  * and manipulate elements during a sync process.
  *
  * This class translates the functions of the sync protocol to operations of a
  * concrete ''FileSystem''. To align the data types (the different
  * representations of files and folders), it makes use of a
  * [[FileSystemProtocolConverter]].
  *
  * @param fileSystem the ''FileSystem''
  * @param httpSender the actor for sending HTTP requests
  * @param converter  the converter for data types
  * @param system     the actor system
  * @tparam ID     the type of element IDs
  * @tparam FILE   the type of files
  * @tparam FOLDER the type of folders
  */
class FileSystemSyncProtocol[ID, FILE <: Model.File[ID],
  FOLDER <: Model.Folder[ID]](val fileSystem: FileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                              val httpSender: ActorRef[HttpRequestSender.HttpCommand],
                              val converter: FileSystemProtocolConverter[ID, FILE, FOLDER])
                             (implicit system: ActorSystem[_]) extends SyncProtocol:
  override def readRootFolder(): Future[List[SyncTypes.FsElement]] =
    run(fileSystem.rootID) flatMap (id => readFolderWithID(id, "/", 0))

  override def readFolder(id: String, path: String, level: Int): Future[List[SyncTypes.FsElement]] =
    readFolderWithID(converter.elementIDFromString(id), path, level)

  override def removeFile(id: String): Future[Unit] =
    run(fileSystem.deleteFile(converter.elementIDFromString(id)))

  override def removeFolder(id: String): Future[Unit] =
    run(fileSystem.deleteFolder(converter.elementIDFromString(id)))

  override def createFolder(parentPath: String, name: String, folder: SyncTypes.FsFolder): Future[Unit] =
    val fsFolder = converter.toFsFolder(folder, name)
    run(for
      parentID <- fileSystem.resolvePath(parentPath)
      _ <- fileSystem.createFolder(parentID, fsFolder)
    yield ())

  override def createFile(parentPath: String, name: String, file: SyncTypes.FsFile,
                          source: Source[ByteString, Any]): Future[Unit] =
    val fsFile = converter.toFsFile(file, name, useID = false)
    run(for
      parentID <- fileSystem.resolvePath(parentPath)
      _ <- fileSystem.createFile(parentID, fsFile, source)
    yield ())

  override def updateFile(file: SyncTypes.FsFile, source: Source[ByteString, Any]): Future[Unit] =
    run(fileSystem.updateFileAndContent(converter.toFsFile(file, null, useID = true), source))

  override def downloadFile(id: String): Future[Source[ByteString, Any]] =
    run(fileSystem.downloadFile(converter.elementIDFromString(id)) map (_.dataBytes))

  override def close(): Unit =
    fileSystem.close()
    httpSender ! HttpRequestSender.Stop

  /**
    * Reads the content of the folder with the given ID, converts the elements
    * contained to ''FsElement'' objects, and returns a list with the results.
    *
    * @param id    the ID of the folder to read
    * @param path  the path prefix for this folder
    * @param level the level of the elements
    * @return a ''Future'' with a list of the elements in this folder
    */
  private def readFolderWithID(id: ID, path: String, level: Int): Future[List[SyncTypes.FsElement]] =
    run(fileSystem.folderContent(id)) map { content =>
      content.files.values.map(converter.toFileElement(_, path, level)).toList ++
        content.folders.values.map(converter.toFolderElement(_, path, level))
    }

  /**
    * Executes the given operation using the HTTP sender actor.
    *
    * @param op the operation
    * @tparam R the result type of the operation
    * @return the future result of the operation
    */
  private def run[R](op: FileSystem.Operation[R]): Future[R] = op.run(httpSender)

  /**
    * Obtains the execution context from the implicit actor system and exposes
    * it in implicit scope.
    *
    * @param system the actor system
    * @return the ''ExecutionContext''
    */
  private implicit def executionContext(implicit system: ActorSystem[_]): ExecutionContext = system.executionContext
