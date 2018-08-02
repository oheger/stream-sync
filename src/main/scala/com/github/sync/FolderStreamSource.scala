/*
 * Copyright 2018 The Developers Team.
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

package com.github.sync

import java.io.IOException
import java.nio.file.{DirectoryStream, Files, Path}

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{Attributes, Outlet, SourceShape}
import com.github.sync.FolderStreamSource.StreamFactory

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.language.implicitConversions

object FolderStreamSource {
  /** The separator for URI path components. */
  private val UriSeparator = "/"

  /**
    * Returns a new source for iterating over the files in the specified root
    * folder.
    *
    * @param root          the root folder
    * @param streamFactory an optional stream factory
    * @return the new source
    */
  def apply(root: Path, streamFactory: StreamFactory = createDirectoryStream):
  Source[FsElement, NotUsed] =
    Source.fromGraph(new FolderStreamSource(root, streamFactory))

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
    * @param folder the path to the folder
    * @param level  the level
    */
  private case class FolderData(folder: Path, level: Int)

  /**
    * Case class representing the state of a BFS iteration.
    *
    * @param optCurrentStream option for the currently active stream
    * @param dirsToProcess    a list with directories to be processed
    * @param level            the current level in the iteration
    */
  private case class BFSState(optCurrentStream: Option[DirectoryStreamRef],
                              dirsToProcess: Queue[FolderData],
                              level: Int)

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
    * @param rootUri       the Uri of the root path of this source
    * @param streamFactory the function for creating directory streams
    * @param state         the current state of the iteration
    * @return a tuple with the new current directory stream, the new list of
    *         pending directories, and the next element to emit
    */
  @tailrec private def iterateBFS(rootUri: String, streamFactory: StreamFactory, state: BFSState):
  (BFSState, Option[FsElement]) = {
    state.optCurrentStream match {
      case Some(ref) =>
        if (ref.iterator.hasNext) {
          val path = ref.iterator.next()
          val isDir = Files isDirectory path
          val elem = createElement(rootUri, path, state.level, isDir)
          if (isDir) (state.copy(dirsToProcess =
            state.dirsToProcess.enqueue(FolderData(path, state.level + 1))), Some(elem))
          else (state, Some(elem))
        } else {
          ref.close()
          iterateBFS(rootUri, streamFactory, state.copy(optCurrentStream = None))
        }

      case None =>
        state.dirsToProcess.dequeueOption match {
          case Some((p, q)) =>
            iterateBFS(rootUri, streamFactory,
              BFSState(optCurrentStream = Some(createStreamRef(p.folder, streamFactory)),
                level = p.level, dirsToProcess = q))
          case _ =>
            (BFSState(None, Queue(), 0), None)
        }
    }
  }

  /**
    * Creates an ''FsElement'' for the given path.
    *
    * @param rootUri the URI of the root directory of this source
    * @param path    the path
    * @param level   the level
    * @param isDir   flag whether this is a directory
    * @return the ''FsElement'' representing the path
    */
  private def createElement(rootUri: String, path: Path, level: Int, isDir: Boolean): FsElement = {
    val uri = generateElementUri(rootUri, path)
    if (isDir) FsFolder(uri, level)
    else FsFile(uri, level, Files.getLastModifiedTime(path).toInstant,
      Files.size(path))
  }

  /**
    * Generates the Uri for an element which is relative to the root URI.
    *
    * @param rootUri the root URI
    * @param path    the path of the element
    * @return the URI for this element
    */
  private def generateElementUri(rootUri: String, path: Path): String = {
    @tailrec def removeTrailingSlash(s: String): String =
      if (s.endsWith(UriSeparator)) removeTrailingSlash(s dropRight 1)
      else s

    removeTrailingSlash(path.toUri.toString.substring(rootUri.length - 1))
  }

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

  /**
    * Extracts the URI from the given root path.
    *
    * @param root the root path to be iterated
    * @return the root URI
    */
  private def extractRootUri(root: Path): String = {
    val uri = root.toUri.toString
    if (uri.endsWith(UriSeparator)) uri else uri + UriSeparator
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
  * @param root          the root directory to be scanned
  * @param streamFactory the factory for creating streams
  */
class FolderStreamSource(val root: Path,
                         streamFactory: StreamFactory)
  extends GraphStage[SourceShape[FsElement]] {

  import FolderStreamSource._

  val out: Outlet[FsElement] = Outlet("DirectoryStreamSource")

  /** Stores the URI of the root path to be iterated. */
  val rootUri: String = extractRootUri(root)

  override def shape: SourceShape[FsElement] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      /** The current state of the iteration. */
      private var currentState = BFSState(None, Queue(FolderData(root, 0)), 0)

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          val (nextState, optElem) = iterateBFS(rootUri, streamFactory, currentState)
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
