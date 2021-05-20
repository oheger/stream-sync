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

package com.github.sync.impl

import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.sync.SyncTypes._
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
                              (implicit ec: ExecutionContext) {
  def execute(op: SyncOperation): Future[Unit] =
    op match {
      case SyncOperation(file@FsFile(_, _, _, _, _, _), ActionRemove, _, _, _) =>
        protocol.removeFile(file.id)

      case SyncOperation(folder@FsFolder(_, _, _, _), ActionRemove, _, _, _) =>
        protocol.removeFolder(folder.id)

      case SyncOperation(folder@FsFolder(_, _, _, _), ActionCreate, _, _, _) =>
        val (parent, name) = UriEncodingHelper.splitParent(folder.relativeUri)
        protocol.createFolder(parent, name, folder)

      case SyncOperation(file@FsFile(_, _, _, _, _, _), ActionCreate, _, srcID, _) =>
        val (parent, name) = UriEncodingHelper.splitParent(file.relativeUri)
        downloadProtocol.downloadFile(srcID) flatMap { source =>
          protocol.createFile(parent, name, file, source)
        }

      case SyncOperation(file@FsFile(_, _, _, _, _, _), ActionOverride, _, srcID, _) =>
        downloadProtocol.downloadFile(srcID) flatMap { source =>
          protocol.updateFile(file, source)
        }

      case _ =>
        Future.failed(new IllegalStateException("Invalid SyncOperation: " + op))
    }
}
