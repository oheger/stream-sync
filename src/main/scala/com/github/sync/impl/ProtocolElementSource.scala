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

import akka.stream.{Attributes, Outlet, SourceShape}
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.sync.SyncTypes.{FsElement, FsFolder, SyncFolderData}
import com.github.sync.protocol.SyncProtocol

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/**
  * A generic ''Source'' implementation for iterating over the elements in a
  * folder structure that is accessed via a [[SyncProtocol]].
  *
  * This class makes use of the functions in the [[SyncProtocol]] trait to read
  * the content of folders. That way it can retrieve of the elements in this
  * folder structure and pass it downstream in a defined order: The elements
  * within a folder are sorted by their names; folders are processed in breadth
  * first order (making use of a queue to keep track of pending folders).
  *
  * @param protocol the ''SyncProtocol''
  * @param ec       the execution context
  */
class ProtocolElementSource(protocol: SyncProtocol)
                           (implicit ec: ExecutionContext) extends GraphStage[SourceShape[FsElement]] {
  val out: Outlet[FsElement] = Outlet("ElementSource")

  override def shape: SourceShape[FsElement] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      /** The queue with the folders that are pending to be processed. */
      private var pendingFolders = SyncFolderQueue[Unit](SyncFolderData(FsFolder("", "/", 0), ()))

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          processNextFolder()
        }
      })

      /**
        * Processes the next folder in the iteration. Requests the content of
        * this folder from the protocol asynchronously. When the content is
        * available, the encountered elements are passed downstream. After all
        * folders have been processed, the stage is completed.
        */
      private def processNextFolder(): Unit = {
        if (pendingFolders.isEmpty) completeStage()
        else {
          val (data, queue) = pendingFolders.dequeue()
          pendingFolders = queue
          val folder = data.folder
          val callback = getAsyncCallback[Try[List[FsElement]]](handleFolderResults)
          val futFolderResult = if (folder.level == 0) protocol.readRootFolder()
          else protocol.readFolder(folder.id, UriEncodingHelper.withTrailingSeparator(folder.relativeUri),
            folder.level + 1)
          futFolderResult onComplete callback.invoke
        }
      }

      /**
        * Handles the result of a query for a folder's content. The elements
        * are sorted and passed downstream. Folders are added to the queue, so
        * that they are processed later. In case of a failure, the stream is
        * completed with this failure.
        *
        * @param triedResults a ''Try'' with the folder content
        */
      private def handleFolderResults(triedResults: Try[List[FsElement]]): Unit = {
        triedResults match {
          case Success(results) if results.nonEmpty =>
            emitMultiple(out, results.sortWith(_.relativeUri < _.relativeUri))
            val folders = results.filter(_.isInstanceOf[FsFolder])
              .map(elem => SyncFolderData(elem.asInstanceOf[FsFolder], ()))
            pendingFolders = pendingFolders ++ folders

          case Success(_) =>
            processNextFolder() // handle next folder as the current one is empty

          case Failure(exception) =>
            failStage(exception)
        }
      }
    }
}
