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

package com.github.sync.impl

import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{Attributes, Outlet, SourceShape}
import com.github.sync.SyncTypes.{FsElement, IterateFunc, NextFolderFunc, ReadResult, SyncFolderData}
import com.github.sync.util.SyncFolderQueue

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

object ElementSource {
}

/**
  * A generic source implementation for iterating over a folder structure.
  *
  * This class implements iteration logic for traversing a structure with
  * folders and files in a defined order. For accessing these elements, an
  * iteration function is used; therefore, this source class can work with
  * various structures, such as a local file system or a file system on a
  * WebDav server.
  *
  * During iteration, the iteration function is invoked again and again. It can
  * have a state (defining the current position in the iteration) that is
  * managed by this class. Each invocation of the iteration function yields new
  * elements. These are passed downstream. Sub folders are stored in a queue,
  * so that they can be processed later.
  *
  * The iterate function is expected to return either single results or the
  * content of a full directory. If there are multiple results, they are sorted
  * by their URI, which is needed for the sync process. So the iterate function
  * does not have to care about the order of the elements it generates.
  *
  * @param initState   the initial state of the iteration
  * @param initFolder  the initial folder to be processed
  * @param iterateFunc the iteration function
  * @param ec          the execution context
  * @tparam F the type of the data used for folders
  * @tparam S the type of the iteration state
  */
class ElementSource[F <: SyncFolderData, S](initState: S, initFolder: F)(iterateFunc: IterateFunc[F, S])
                                           (implicit ec: ExecutionContext)
  extends GraphStage[SourceShape[FsElement]] {
  val out: Outlet[FsElement] = Outlet("ElementSource")

  override def shape: SourceShape[FsElement] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      /** The state of the iteration, updated by the iterate function. */
      private var currentState = initState

      /** The queue with the folders that are pending to be processed. */
      private var pendingFolders = SyncFolderQueue[F](initFolder)

      /**
        * The function to be passed to the iteration function in order to
        * obtain another pending folder. This function accesses the ordered
        * queue with pending folders.
        */
      private val nextFolderFunc: NextFolderFunc[F] = () =>
        if (pendingFolders.isEmpty) None
        else {
          val (f, q) = pendingFolders.dequeue()
          pendingFolders = q
          Some(f)
        }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          val callback = getAsyncCallback[Try[Option[ReadResult[F, S]]]](handleResult)
          iterateFunc(currentState, nextFolderFunc) onComplete callback.invoke
        }
      })

      /**
        * Handles a future result received from the iterate function. Received
        * results are processed and passed downstream. If there are no results,
        * this stage can be completed. In case of an error, this stage fails.
        *
        * @param triedResult a ''Try'' with the result from the iterate func
        */
      private def handleResult(triedResult: Try[Option[ReadResult[F, S]]]): Unit = {
        triedResult match {
          case Success(Some(result)) =>
            pendingFolders = pendingFolders ++ result.folders
            val folderElems = result.folders map (_.folder)
            val orderedElements = (result.files ++ folderElems).sortWith(_.relativeUri < _.relativeUri)
            emitMultiple(out, orderedElements)
            currentState = result.nextState

          case Success(None) =>
            completeStage()

          case Failure(exception) =>
            failStage(exception)
        }
      }
    }

}
