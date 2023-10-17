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

package com.github.sync.stream

import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.sync.SyncTypes.*
import com.github.sync.SyncTypes.SyncAction.*
import com.github.sync.protocol.SyncProtocol

import scala.concurrent.{ExecutionContext, Future}

/**
  * A generic implementation for executing sync operations against
  * [[SyncProtocol]] instances.
  *
  * This class is initialized with two [[SyncProtocol]] objects, one for the
  * remote and one for the local folder structure to be synced. It provides a
  * generic ''execute()'' function that accepts a [[SyncOperation]] instance
  * and maps this operation to a manipulating function of the affected
  * ''SyncProtocol'' based on the action type.
  *
  * This handler works with both sync and mirror streams. In case of a mirror
  * stream, the remote protocol corresponds to the destination structure, and
  * the local protocol is only used to obtain files that need to be uploaded to
  * the destination.
  *
  * @param remoteProtocol the protocol to execute remote operations against
  * @param localProtocol  the protocol to execute local operations against
  * @param ec             the execution context
  */
class ProtocolOperationHandler(remoteProtocol: SyncProtocol, localProtocol: SyncProtocol)
                              (implicit ec: ExecutionContext):
  def execute(op: SyncOperation): Future[Unit] =
    op match
      case SyncOperation(element, ActionRemove, _, dstID, _) =>
        removeElement(remoteProtocol, element, dstID)

      case SyncOperation(element, ActionLocalRemove, _, dstID, _) =>
        removeElement(localProtocol, element, dstID)

      case SyncOperation(folder: FsFolder, ActionCreate, _, _, _) =>
        createFolder(remoteProtocol, folder)

      case SyncOperation(folder: FsFolder, ActionLocalCreate, _, _, _) =>
        createFolder(localProtocol, folder)

      case SyncOperation(file: FsFile, ActionCreate, _, _, _) =>
        createFile(remoteProtocol, localProtocol, file)

      case SyncOperation(file: FsFile, ActionLocalCreate, _, _, _) =>
        createFile(localProtocol, remoteProtocol, file)

      case SyncOperation(file: FsFile, ActionOverride, _, dstID, _) =>
        overrideFile(remoteProtocol, localProtocol, file, dstID)

      case SyncOperation(file: FsFile, ActionLocalOverride, _, dstID, _) =>
        overrideFile(localProtocol, remoteProtocol, file, dstID)

      case SyncOperation(_, ActionNoop, _, _, _) =>
        Future.successful(())

      case _ =>
        Future.failed(new IllegalStateException("Invalid SyncOperation: " + op))

  /**
    * Helper function to split the relative URI of an element into the parent
    * path and the element name. The name is decoded.
    *
    * @param element the element
    * @return a tuple with the parent URI and the decoded element name
    */
  private def extractParentAndName(element: FsElement): (String, String) =
    val (parent, encName) = UriEncodingHelper.splitParent(element.relativeUri)
    (parent, UriEncodingHelper.decode(encName))

  /**
    * Removes the given element via the given protocol. The element can either
    * be a file or a folder.
    *
    * @param protocol the protocol to execute the remove operation
    * @param element  the affected element
    * @param id       the ID of the element
    * @return a ''Future'' with the result of the operation
    */
  private def removeElement(protocol: SyncProtocol, element: FsElement, id: String): Future[Unit] =
    element match
      case _: FsFile => protocol.removeFile(id)
      case _: FsFolder => protocol.removeFolder(id)

  /**
    * Creates a folder via the given protocol.
    *
    * @param protocol the protocol to create the folder
    * @param folder   the data object describing the new folder
    * @return a ''Future'' with the result of the operation
    */
  private def createFolder(protocol: SyncProtocol, folder: FsFolder): Future[Unit] =
    val (parent, name) = extractParentAndName(folder)
    protocol.createFolder(parent, name, folder)

  /**
    * Creates a file via one protocol whose content is obtained via another
    * protocol.
    *
    * @param targetProtocol   the protocol to create the file
    * @param downloadProtocol the protocol to load the file's content
    * @param file             the data object for the new file
    * @return a ''Future'' with the result of the operation
    */
  private def createFile(targetProtocol: SyncProtocol, downloadProtocol: SyncProtocol, file: FsFile): Future[Unit] =
    val (parent, name) = extractParentAndName(file)
    downloadProtocol.downloadFile(file.id) flatMap { source =>
      targetProtocol.createFile(parent, name, file, source)
    }

  /**
    * Executes an operation to override a file via one protocol, obtaining the
    * new content via another protocol.
    *
    * @param targetProtocol   the protocol to update the file
    * @param downloadProtocol the protocol to load the file's new content
    * @param file             the data object for the affected file
    * @param dstID            the ID of the file to override
    * @return a ''Future'' with the result of the operation
    */
  private def overrideFile(targetProtocol: SyncProtocol, downloadProtocol: SyncProtocol, file: FsFile, dstID: String):
  Future[Unit] =
    downloadProtocol.downloadFile(file.id) flatMap { source =>
      targetProtocol.updateFile(file.copy(id = dstID), source)
    }
