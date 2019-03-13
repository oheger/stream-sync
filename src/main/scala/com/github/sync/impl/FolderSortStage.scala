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

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import com.github.sync.SyncTypes.FsElement

object FolderSortStage {

  /**
    * Internally used class for keeping track on the state of the stage.
    *
    * This is used to figure out when a new folder starts and when sorted
    * elements need to be pushed downstream.
    *
    * @param currentFolder the URI of the folder currently processed
    * @param elements      the elements encountered for this folder
    */
  private case class ProcessingState(currentFolder: String,
                                     elements: List[FsElement])

  /** Constant for the URI of the root folder. */
  private val RootFolder = ""

  /**
    * Sorts a list of elements.
    *
    * @param elements the list to be sorted
    * @return the sorted list
    */
  private def sortElements(elements: List[FsElement]): List[FsElement] =
    elements.sortWith(_.relativeUri < _.relativeUri)

  /**
    * Obtains the parent directory of the given element. If the element belongs
    * to the root folder, an empty string is returned.
    *
    * @param elem the element
    * @return the parent directory
    */
  private def parentFolder(elem: FsElement): String = {
    val index = elem.relativeUri.lastIndexOf('/')
    if (index > 0) elem.relativeUri.substring(0, index)
    else RootFolder
  }

  /**
    * The state transition function. This function is invoked for each element
    * encountered during stream processing. It updates the current processing
    * state and also returns a sequence of elements to be pushed downstream.
    *
    * @param state the current state
    * @param elem  the next element
    * @return a tuple with the next state and a sequence of elements to push
    */
  private def updateState(state: ProcessingState, elem: FsElement):
  (ProcessingState, List[FsElement]) = {
    val folder = parentFolder(elem)
    if (folder == state.currentFolder)
      (state.copy(elements = elem :: state.elements), Nil)
    else (ProcessingState(folder, List(elem)), sortElements(state.elements))
  }
}

/**
  * A stage providing a default sorting function for the elements contained in
  * a folder.
  *
  * The sync algorithm requires that the elements contained in a folder are
  * processed in a defined alphabetical order. This graph stage applies this
  * order to the current flow. So, sources do not have to implement sorting on
  * their own.
  *
  * For this to work, this stage requires that all elements contained in a
  * folder are output in a series. This can be achieved for instance if the
  * source does breadth first traversal.
  */
class FolderSortStage extends GraphStage[FlowShape[FsElement, FsElement]] {
  val in: Inlet[FsElement] = Inlet[FsElement]("FolderSortStage.in")
  val out: Outlet[FsElement] = Outlet[FsElement]("FolderSortStage.out")

  override val shape: FlowShape[FsElement, FsElement] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      import FolderSortStage._

      var state = ProcessingState(RootFolder, Nil)

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val elem = grab(in)
          val (next, elemsToPush) = updateState(state, elem)
          state = next
          if (elemsToPush.nonEmpty)
            emitMultiple(out, elemsToPush)
          else pull(in)
        }

        override def onUpstreamFinish(): Unit = {
          emitMultiple(out, sortElements(state.elements))
          complete(out)
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
    }
}
