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
import com.github.sync.SyncTypes.*
import com.github.sync.SyncTypes.SyncAction.*
import com.github.sync.protocol.SyncProtocol

import scala.concurrent.{ExecutionContext, Future}

/**
  * A generic implementation for executing sync operations against a
  * [[SyncProtocol]] implementation.
  *
  * This class provides a generic ''execute()'' function that accepts a
  * [[SyncOperation]] instance. It maps this operation to a manipulating
  * function of the associated ''SyncProtocol''.
  *
  * @param protocol         the protocol to execute the operations against
  * @param downloadProtocol the protocol for downloading source files
  * @param ec               the execution context
  */
class ProtocolOperationHandler(protocol: SyncProtocol, downloadProtocol: SyncProtocol)
                              (implicit ec: ExecutionContext):
  def execute(op: SyncOperation): Future[Unit] =
    op match
      case SyncOperation(_: FsFile, ActionRemove, _, dstID) =>
        protocol.removeFile(dstID)

      case SyncOperation(_: FsFolder, ActionRemove, _, dstID) =>
        protocol.removeFolder(dstID)

      case SyncOperation(folder: FsFolder, ActionCreate, _, _) =>
        val (parent, name) = extractParentAndName(folder)
        protocol.createFolder(parent, name, folder)

      case SyncOperation(file: FsFile, ActionCreate, _, _) =>
        val (parent, name) = extractParentAndName(file)
        downloadProtocol.downloadFile(file.id) flatMap { source =>
          protocol.createFile(parent, name, file, source)
        }

      case SyncOperation(file: FsFile, ActionOverride, _, dstID) =>
        downloadProtocol.downloadFile(file.id) flatMap { source =>
          protocol.updateFile(file.copy(id = dstID), source)
        }

      case SyncOperation(_, ActionNoop, _, _) =>
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
