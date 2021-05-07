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

package com.github.sync.local

import java.io.IOException
import java.nio.file.{DirectoryStream, Files, Path}
import akka.NotUsed
import akka.stream.scaladsl.Source
import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.sync.SyncTypes.{ElementSourceFactory, FsElement, FsFile, FsFolder, IterateFunc, IterateResult, NextFolderFunc, SyncFolderData}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

/**
  * A module providing a stream source for traversing a directory structure.
  *
  * This source generates [[FsElement]] objects for all files and folders
  * below a given root folder. Note that the root directory itself is not part
  * of the output of this source. Detected elements are are passed downstream
  * one by one in an undefined order; additional means must be applied to
  * create the order required by the sync stage.
  *
  * This source makes use of the ''DirectoryStream'' API from Java nio. It
  * thus uses blocking operations.
  */
object LocalFsElementSource {
  /**
    * Returns a new source for iterating over the files in the specified root
    * folder.
    *
    * @param config         the configuration of the new source
    * @param streamFactory  an optional stream factory
    * @param startDirectory URI of a directory to start the iteration with
    * @param sourceFactory  a factory for creating an element source
    * @param ec             the execution context
    * @return the new source
    */
  def apply(config: LocalFsConfig, streamFactory: StreamFactory = createDirectoryStream, startDirectory: String = "")
           (sourceFactory: ElementSourceFactory)
           (implicit ec: ExecutionContext): Source[FsElement, NotUsed] = {
    val initState = IterationState(None, null)
    val initFolder = createInitFolder(config, startDirectory)
    Source.fromGraph(sourceFactory.createElementSource(initState, initFolder,
      Some(iterationComplete _))(iterateFunc(config, streamFactory)))
  }

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
    * Case class representing the state of the iteration over the local FS.
    *
    * @param optCurrentStream option for the currently active stream
    * @param currentFolder    the current folder whose elements are iterated
    */
  private case class IterationState(optCurrentStream: Option[DirectoryStreamRef],
                                    currentFolder: FsFolder)

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
    * Creates the data object for the folder to start the iteration with.
    *
    * @param config         the configuration
    * @param startDirectory the URI of the start directory
    * @return the folder data object for the initial folder
    */
  private def createInitFolder(config: LocalFsConfig, startDirectory: String): SyncFolderData[Path] = {
    //TODO: Set correct element ID.
    val rootFolder = FsFolder(null, startDirectory, UriEncodingHelper.componentCount(startDirectory) - 1)
    val rootPath = if (startDirectory.nonEmpty)
      config.rootPath.resolve(UriEncodingHelper removeLeadingSeparator startDirectory)
    else config.rootPath
    SyncFolderData(rootFolder, rootPath)
  }

  /**
    * Returns the function for iterating over all elements in the source folder
    * structure.
    *
    * @param config        the configuration for this source
    * @param streamFactory the function for creating directory streams
    * @param ec            the execution context
    * @return the iteration function
    */
  private def iterateFunc(config: LocalFsConfig, streamFactory: StreamFactory = createDirectoryStream)
                         (implicit ec: ExecutionContext): IterateFunc[Path, IterationState] =
    (state, nextFolder) =>
      iterateBFS(config, streamFactory, state, nextFolder) map { res =>
        (res._1, Some(res._2), None)
      } getOrElse ((state, None, None))

  /**
    * The iteration function for BFS traversal. This function processes
    * directories on the current level first before sub directories are
    * iterated over.
    *
    * @param config        the configuration for this source
    * @param streamFactory the function for creating directory streams
    * @param state         the current state of the iteration
    * @param nextFolder    the function to fetch the next folder
    * @return an object with the updated iteration state and the result
    *         to emit or ''None'' if iteration is complete
    */
  @tailrec private def iterateBFS(config: LocalFsConfig, streamFactory: StreamFactory,
                                  state: IterationState, nextFolder: NextFolderFunc[Path]):
  Option[(IterationState, IterateResult[Path])] = {
    state.optCurrentStream match {
      case Some(ref) =>
        if (ref.iterator.hasNext) {
          val path = ref.iterator.next()
          val isDir = Files isDirectory path
          val elem = createElement(config, path, state.currentFolder, isDir = isDir)
          if (isDir)
            Some((state, IterateResult(state.currentFolder, Nil,
              List(SyncFolderData(elem.asInstanceOf[FsFolder], path)))))
          else
            Some((state, IterateResult(state.currentFolder, List(elem.asInstanceOf[FsFile]),
              List.empty[SyncFolderData[Path]])))
        } else {
          ref.close()
          iterateBFS(config, streamFactory, state.copy(optCurrentStream = None), nextFolder)
        }

      case None =>
        nextFolder() match {
          case Some(data) =>
            iterateBFS(config, streamFactory,
              state.copy(optCurrentStream = Some(createStreamRef(data.data, streamFactory)),
                currentFolder = data.folder), nextFolder)
          case None => None
        }
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
    //TODO: Set correct element ID.
    if (isDir) FsFolder(null, uri, parent.level + 1)
    else FsFile(null, uri, parent.level + 1,
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
    parent.originalUri + UriEncodingHelper.UriSeparator + path.getFileName.toString

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
    * The completion function called when stream processing is finished. This
    * implementation makes sure that an open directory stream is closed.
    *
    * @param state the current iteration state
    */
  private def iterationComplete(state: IterationState): Unit = {
    state.optCurrentStream foreach (_.close())
  }
}
