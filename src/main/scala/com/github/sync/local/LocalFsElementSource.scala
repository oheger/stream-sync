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

package com.github.sync.local

import java.io.IOException
import java.nio.file.{DirectoryStream, Files, Path}

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{Attributes, Outlet, SourceShape}
import com.github.sync.local.LocalFsElementSource.StreamFactory
import com.github.sync.util.{SyncFolderData, SyncFolderQueue, UriEncodingHelper}
import com.github.sync.{FsElement, FsFile, FsFolder}

import scala.annotation.tailrec
import scala.language.implicitConversions

object LocalFsElementSource {
  /**
    * Returns a new source for iterating over the files in the specified root
    * folder.
    *
    * @param config        the configuration of the new source
    * @param streamFactory an optional stream factory
    * @return the new source
    */
  def apply(config: LocalFsConfig, streamFactory: StreamFactory = createDirectoryStream):
  Source[FsElement, NotUsed] =
    Source.fromGraph(new LocalFsElementSource(config, streamFactory))

  /**
    * An internally used data class for storing data about a directory
    * stream. The class stores the original reference to the stream and the
    * iterator for the current iteration.
    *
    * @param stream   the stream
    * @param iterator the iterator
    */
  private case class DirectoryStreamRef(stream: DirectoryStream[Path],
                                        iterator: java.util.Iterator[Path]) {
    /**
      * Closes the underlying stream ignoring all exceptions.
      */
    def close(): Unit = {
      try {
        stream.close()
      } catch {
        case _: IOException => // ignore
      }
    }
  }

  /**
    * A simple data class that stores relevant information about a folder that
    * is pending to be processed.
    *
    * @param folderPath the path to the folder
    * @param folder     the associated folder element
    */
  private case class FolderData(folderPath: Path, override val folder: FsFolder) extends SyncFolderData

  /**
    * Case class representing the state of a BFS iteration.
    *
    * @param optCurrentStream option for the currently active stream
    * @param dirsToProcess    a list with directories to be processed
    * @param currentFolder    the current folder whose elements are iterated
    */
  private case class BFSState(optCurrentStream: Option[DirectoryStreamRef],
                              dirsToProcess: SyncFolderQueue[FolderData],
                              currentFolder: FsElement)

  /**
    * Definition of a function serving as stream factory. Such a function can
    * be provided when creating a source. This can be used to influence the
    * creation of directory streams.
    */
  type StreamFactory = Path => DirectoryStream[Path]

  /**
    * A default function for creating a ''DirectoryStream''. This function
    * just delegates to the ''Files'' class.
    *
    * @param path the path in question
    * @return a ''DirectoryStream'' for this path
    */
  def createDirectoryStream(path: Path): DirectoryStream[Path] =
    Files.newDirectoryStream(path)

  /**
    * The iteration function for BFS traversal. This function processes
    * directories on the current level first before sub directories are
    * iterated over.
    *
    * @param config        the configuration for this source
    * @param streamFactory the function for creating directory streams
    * @param state         the current state of the iteration
    * @return a tuple with the new current directory stream, the new list of
    *         pending directories, and the next element to emit
    */
  @tailrec private def iterateBFS(config: LocalFsConfig, streamFactory: StreamFactory,
                                  state: BFSState): (BFSState, Option[FsElement]) = {
    state.optCurrentStream match {
      case Some(ref) =>
        if (ref.iterator.hasNext) {
          val path = ref.iterator.next()
          val isDir = Files isDirectory path
          val elem = createElement(config, path, state.currentFolder, isDir = isDir)
          if (isDir) (state.copy(dirsToProcess = state.dirsToProcess +
            FolderData(path, elem.asInstanceOf[FsFolder])), Some(elem))
          else (state, Some(elem))
        } else {
          ref.close()
          iterateBFS(config, streamFactory, state.copy(optCurrentStream = None))
        }

      case None =>
        if (state.dirsToProcess.nonEmpty) {
          val (data, queue) = state.dirsToProcess.dequeue()
          iterateBFS(config, streamFactory, BFSState(optCurrentStream = Some(createStreamRef(data
            .folderPath, streamFactory)),
            currentFolder = data.folder, dirsToProcess = queue))
        } else (BFSState(None, state.dirsToProcess, null), None)
    }
  }

  /**
    * Creates an ''FsElement'' for the given path.
    *
    * @param config the configuration for this source
    * @param path   the path
    * @param parent the parent folder
    * @param isDir  flag whether this is a directory
    * @return the ''FsElement'' representing the path
    */
  private def createElement(config: LocalFsConfig, path: Path, parent: FsElement, isDir: Boolean):
  FsElement = {
    val uri = generateElementUri(parent, path)
    if (isDir) FsFolder(uri, parent.level + 1)
    else FsFile(uri, parent.level + 1,
      FileTimeUtils.getLastModifiedTimeInTimeZone(path, config.optTimeZone),
      Files.size(path))
  }

  /**
    * Generates the Uri for an element in the current iteration.
    *
    * @param parent the parent folder
    * @param path   the path of the element
    * @return the URI for this element
    */
  private def generateElementUri(parent: FsElement, path: Path): String =
    parent.relativeUri + UriEncodingHelper.UriSeparator + path.getFileName.toString

  /**
    * Uses the specified ''StreamFactory'' to create a ''DirectoryStreamRef''
    * for the specified path.
    *
    * @param p       the path
    * @param factory the ''StreamFactory''
    * @return the new ''DirectoryStreamRef''
    */
  private def createStreamRef(p: Path, factory: StreamFactory): DirectoryStreamRef = {
    val stream = factory(p)
    DirectoryStreamRef(stream, stream.iterator())
  }
}

/**
  * A stream source for traversing a directory structure.
  *
  * This source generates [[FsElement]] objects for all files and folders
  * below a given root folder. Note that the root directory itself is not part
  * of the output of this source.
  *
  * This source makes use of the ''DirectoryStream'' API from Java nio. It
  * thus uses blocking operations.
  *
  * @param config        the configuration for this source
  * @param streamFactory the factory for creating streams
  */
class LocalFsElementSource(val config: LocalFsConfig,
                           streamFactory: StreamFactory)
  extends GraphStage[SourceShape[FsElement]] {

  import LocalFsElementSource._

  val out: Outlet[FsElement] = Outlet("DirectoryStreamSource")

  override def shape: SourceShape[FsElement] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      import SyncFolderQueue._

      /** The current state of the iteration. */
      private var currentState = BFSState(None,
        SyncFolderQueue(FolderData(config.rootPath, FsFolder("", -1))), null)

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          val (nextState, optElem) = iterateBFS(config, streamFactory, currentState)
          optElem match {
            case Some(e) =>
              push(out, e)
              currentState = nextState
            case None =>
              complete(out)
          }
        }

        override def onDownstreamFinish(): Unit = {
          iterationComplete(currentState)
          super.onDownstreamFinish()
        }
      })
    }

  /**
    * Callback function to indicate that stream processing is now finished. A
    * concrete implementation should free all resources that are still in use.
    *
    * @param state the current iteration state
    */
  private def iterationComplete(state: BFSState): Unit = {
    state.optCurrentStream foreach (_.close())
  }
}
