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

import com.github.cloudfiles.core.http.{HttpRequestSender, UriEncodingHelper}
import com.github.cloudfiles.core.utils.Walk
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.sync.SyncTypes
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import scala.concurrent.{ExecutionContext, Future}

object FileSystemSyncProtocol:
  /**
    * A function that can serve as ''ParentDataFunc'' to generate the path of
    * an element when iterating over a structure. The function simply obtains
    * the folder name, so that a path can be constructed by concatenating the
    * list of parent data.
    *
    * @param folder the current folder
    * @tparam ID the type of the element ID
    * @return the extracted data for this folder
    */
  private def extractName[ID](folder: Model.Folder[ID]): Option[String] = Some(folder.name)

  /**
    * Generates the path of an element based on the list of names of its parent
    * folders.
    *
    * @param parentNames the list of parent folders
    * @return the generated path for this element
    */
  private def generatePath(parentNames: List[String]): String =
    parentNames.reverse.mkString(start = "/", sep = "/", end = "")

  /**
    * A function that determines the order in which elements are iterated over
    * in a folder. To have a deterministic order, elements are sorted by their
    * names.
    *
    * @param elements the original list of elements
    * @tparam ID the type of the element ID
    * @return the sorted list of elements
    */
  private def sortElements[ID](elements: List[Model.Element[ID]]): List[Model.Element[ID]] =
    elements.sortWith(_.name < _.name)
end FileSystemSyncProtocol

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
                             (implicit system: ActorSystem[?]) extends SyncProtocol:

  import FileSystemSyncProtocol.*

  override def elementSource: Future[Source[SyncTypes.FsElement, NotUsed]] =
    fileSystem.rootID.run(httpSender).map { rootID =>
      val walkConfig = Walk.WalkConfig(
        fileSystem = fileSystem,
        httpActor = httpSender,
        rootID = rootID,
        transform = sortElements
      )
      Walk.bfsSourceWithParentData(walkConfig)(extractName)
        .map { elemWithParentData =>
          val path = UriEncodingHelper.withTrailingSeparator(generatePath(elemWithParentData.parentData))
          val level = elemWithParentData.parentData.size
          elemWithParentData.element match
            case f: Model.File[ID] => converter.toFileElement(f.asInstanceOf[FILE], path, level)
            case f: Model.Folder[ID] => converter.toFolderElement(f.asInstanceOf[FOLDER], path, level)
        }
    }

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
  private implicit def executionContext(implicit system: ActorSystem[?]): ExecutionContext = system.executionContext
