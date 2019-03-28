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

import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler, StageLogging}
import akka.stream.{Attributes, Outlet, SourceShape}
import com.github.sync.SyncTypes.{CompletionFunc, FsElement, FutureResultFunc, IterateFunc, IterateResult, NextFolderFunc, SyncFolderData, TransformResultFunc}
import com.github.sync.util.SyncFolderQueue

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

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
  * @param initState        the initial state of the iteration
  * @param initFolder       the initial folder to be processed
  * @param optCompleteFunc  option for the function to be called at completion
  * @param optTransformFunc option for the function to transform results
  * @param iterateFunc      the iteration function
  * @param ec               the execution context
  * @tparam F the type of the data used for folders
  * @tparam S the type of the iteration state
  */
class ElementSource[F <: SyncFolderData, S](val initState: S, initFolder: F,
                                            optCompleteFunc: Option[CompletionFunc[S]] = None,
                                            optTransformFunc: Option[TransformResultFunc[F]] = None)
                                           (iterateFunc: IterateFunc[F, S])
                                           (implicit ec: ExecutionContext)
  extends GraphStage[SourceShape[FsElement]] {
  val out: Outlet[FsElement] = Outlet("ElementSource")

  override def shape: SourceShape[FsElement] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {

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
          log.info("Processing folder {}.", f.folder.relativeUri)
          Some(f)
        }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          continueIteration()
        }

        override def onDownstreamFinish(): Unit = {
          onComplete()
          super.onDownstreamFinish()
        }
      })

      /**
        * Invokes the iteration function to generate new results. The results
        * (if available) are passed downstream.
        */
      private def continueIteration(): Unit = {
        Try {
          iterateFunc(currentState, nextFolderFunc)
        } match {
          case Success((nextState, optResult, optFutureFunc)) =>
            if (optResult.isEmpty && optFutureFunc.isEmpty) {
              onComplete()
              completeStage()
            } else {
              handleDirectResult(nextState, optResult)
              handleFutureResult(nextState, optFutureFunc)
            }
          case Failure(exception) =>
            handleFailure(exception)
        }
      }

      /**
        * Handles a direct result received from the iterate function. If
        * defined, the next state and the result are passed to the generic
        * result handling function.
        *
        * @param nextState the next state
        * @param optResult an option with the result
        */
      private def handleDirectResult(nextState: S, optResult: Option[IterateResult[F]]): Unit = {
        optResult foreach (result => handleResult(optTransformFunc)(Success((nextState, result))))
      }

      /**
        * Handles a future result function obtain from the iterate function. If
        * defined, an async callback is obtained which is invoked when the
        * future result from the function completes.
        *
        * @param nextState     the next state
        * @param optFutureFunc the option with the future result function
        */
      private def handleFutureResult(nextState: S, optFutureFunc: Option[FutureResultFunc[F, S]]): Unit = {
        optFutureFunc foreach { resultFunc =>
          currentState = nextState
          val callback = getAsyncCallback[Try[(S, IterateResult[F])]](handleResult(optTransformFunc))
          resultFunc() onComplete callback.invoke
        }
      }

      /**
        * Handles a result received from the iterate function (either directly
        * or via the future function). Received elements are processed and
        * passed downstream. In case of an error, this stage fails.
        *
        * @param optTransFunc an option with the transformation function
        * @param triedResult  a ''Try'' with the result from the iterate func
        */
      private def handleResult(optTransFunc: Option[TransformResultFunc[F]])
                              (triedResult: Try[(S, IterateResult[F])]): Unit = {
        triedResult match {
          case Success((state, result)) if result.nonEmpty =>
            log.debug("Folder {} has {} files and {} sub folders.", result.currentFolder.relativeUri,
              result.files.size, result.folders.size)
            optTransFunc match {
              case Some(f) =>
                applyTransformation(state, result)(f)
              case None =>
                currentState = state
                pendingFolders = pendingFolders ++ result.folders
                val folderElems = result.folders map (_.folder)
                val orderedElements = (result.files ++ folderElems).sortWith(_.relativeUri < _.relativeUri)
                emitMultiple(out, orderedElements)
            }

          case Success((state, result)) =>
            log.debug("Folder {} is empty.", result.currentFolder.relativeUri)
            currentState = state
            continueIteration()

          case Failure(exception) =>
            log.error(exception, "Exception during iteration.")
            handleFailure(exception)
        }
      }

      /**
        * Invokes the given transformation function on the result and processes
        * the result.
        *
        * @param state  the current state
        * @param result the result to be transformed
        * @param f      the transformation function
        */
      private def applyTransformation(state: S, result: IterateResult[F])(f: TransformResultFunc[F]): Unit = {
        val callback = getAsyncCallback[Try[(S, IterateResult[F])]](handleResult(None))
        f(result).map((state, _)) onComplete callback.invoke
      }

      /**
        * Handles a failed result. This causes the stage to fail with the
        * given exception.
        *
        * @param exception the exception causing the failure
        */
      private def handleFailure(exception: Throwable): Unit = {
        onComplete()
        failStage(exception)
      }

      /**
        * Executes final steps when the source is complete.
        */
      private def onComplete(): Unit = {
        optCompleteFunc foreach (_.apply(currentState))
      }
    }

}
