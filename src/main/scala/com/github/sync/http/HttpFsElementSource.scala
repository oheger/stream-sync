/*
 * Copyright 2018-2020 The Developers Team.
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

package com.github.sync.http

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.HttpResponse
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Graph, SourceShape}
import akka.util.{ByteString, Timeout}
import com.github.sync.SyncTypes._
import com.github.sync.http.HttpFsElementSource.{ElemData, HttpFolder, HttpIterationState, ParsedFolderData}
import com.github.sync.util.UriEncodingHelper

import scala.concurrent.{ExecutionContext, Future}

object HttpFsElementSource {

  /**
    * Data class storing additional information about a folder that is to be
    * fetched from the Http server.
    *
    * @param ref the URI to reference the folder on the server
    */
  case class HttpFolder(ref: String) {
    /**
      * The normalized URI to reference the folder on the server. This URI
      * always ends on a slash which is required by some Dav servers.
      */
    val normalizedRef: String = UriEncodingHelper.withTrailingSeparator(ref)
  }

  /**
    * A class representing the state of an iteration over an HTTP server's
    * folder structure.
    *
    * The class stores a bunch of parameters that are needed during the
    * iteration. There are no real state updates.
    *
    * @param requestActor            the actor for sending HTTP requests
    * @param rootUriPrefix           the common prefix of all URIs in the structure to be
    *                                processed; the URIs generated for ''FsElement''
    *                                objects must be relative to this URI.
    * @param decodedRootUriPrefixLen the length of the root URI prefix
    * @param config                  the configuration for the HTTP server
    */
  case class HttpIterationState[C <: HttpConfig](requestActor: ActorRef,
                                                 rootUriPrefix: String,
                                                 decodedRootUriPrefixLen: Int,
                                                 config: C)

  /**
    * A data class holding information about an element that is processed.
    *
    * @param ref  the reference URI for this element
    * @param elem the element itself
    */
  case class ElemData(ref: String, elem: FsElement)

  /**
    * A data class for the result of a parse operation of a folder's content.
    *
    * The result of course contains a list with the elements contained in the
    * folder. If a protocol supports paging, it may be necessary to issue
    * another request to obtain the full content of the folder. Therefore, an
    * ''Option'' with such a request is supported.
    *
    * @param elements    a list with the elements of this folder
    * @param nextRequest an ''Option'' with another request to be sent
    */
  case class ParsedFolderData(elements: List[ElemData], nextRequest: Option[HttpRequestActor.SendRequest])

}

/**
  * A trait providing basic functionality for iterating over a folder structure
  * stored on an HTTP server.
  *
  * This base trait defines an iteration function and uses default
  * representations for elements. Derived classes have to define the requests
  * to the single folders and convert the responses to these default
  * representations, so they can be further processed. The idea is that this
  * trait implements the iteration logic, while derived classes integrate a
  * concrete protocol like WebDav or OneDrive.
  *
  * Note that error handling is very basic: a request that fails for whatever
  * reason (non-success server response, I/O error, unexpected data in the
  * response, etc.) causes the source to cancel the whole stream. This is
  * desired because when the server has a (temporary) problem a sync process
  * must be stopped; otherwise, it can have unexpected results.
  *
  * @tparam C the type of the configuration needed by this source
  */
trait HttpFsElementSource[C <: HttpConfig] {
  /**
    * Creates a ''Source'' based on this class using the specified
    * configuration.
    *
    * @param config         the configuration
    * @param sourceFactory  the factory for the element source
    * @param requestActor   the actor for sending HTTP requests
    * @param startFolderUri URI of a folder to start the iteration
    * @param system         the actor system
    * @return the new source
    */
  def createSource(config: C, sourceFactory: ElementSourceFactory, requestActor: ActorRef,
                   startFolderUri: String = "")(implicit system: ActorSystem):
  Source[FsElement, NotUsed] = Source.fromGraph(createSourceShape(config, sourceFactory, requestActor, startFolderUri))

  /**
    * Creates the source shape for iterating over an HTTP folder structure.
    *
    * @param config         the configuration
    * @param sourceFactory  the factory for the element source
    * @param requestActor   the actor for sending HTTP requests
    * @param startFolderUri URI of a folder to start the iteration
    * @param system         the actor system
    * @return the new source
    */
  def createSourceShape(config: C, sourceFactory: ElementSourceFactory, requestActor: ActorRef,
                        startFolderUri: String = "")(implicit system: ActorSystem):
  Graph[SourceShape[FsElement], NotUsed] = {
    implicit val ec: ExecutionContext = system.dispatcher
    val rootUriPrefix = UriEncodingHelper.removeTrailingSeparator(config.rootUri.path.toString())
    val rootPrefixLen = UriEncodingHelper.decode(rootUriPrefix).length
    val state = HttpIterationState(requestActor, rootUriPrefix, rootPrefixLen, config)
    sourceFactory.createElementSource(state, createInitialFolder(config, startFolderUri),
      Some(completionFunc))(iterateFunc)
  }

  /**
    * Reads the entity of the given response of a folder request completely and
    * returns the resulting ''ByteString''.
    *
    * @param response the response to be read
    * @param ec       the execution context
    * @param system   the actor system
    * @return a future with the ''ByteString'' built from the response
    */
  protected def readResponse(response: HttpResponse)
                            (implicit ec: ExecutionContext, system: ActorSystem): Future[ByteString] = {
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    response.entity.dataBytes.runWith(sink)
  }

  /**
    * Creates a request for the specified folder. A concrete implementation
    * here has to generate the correct request to read the content of the
    * folder specified.
    *
    * @param state      the current state
    * @param folderData the data of the folder to be loaded
    * @return the request to query the content of this folder
    */
  protected def createFolderRequest(state: HttpIterationState[C], folderData: SyncFolderData[HttpFolder]):
  HttpRequestActor.SendRequest

  /**
    * Parses the response received for a folder request. Here a concrete
    * implementation has to process the response from the server and extracts
    * the elements (files and folders) that are referenced.
    *
    * @param state  the current state
    * @param folder the folder whose content is to be computed
    * @param result the result of the request for this folder
    * @param ec     the execution context
    * @param system the actor system
    * @return a ''Future'' with the result of the parse operation
    */
  protected def parseFolderResponse(state: HttpIterationState[C], folder: FsFolder)(result: HttpRequestActor.Result)
                                   (implicit ec: ExecutionContext, system: ActorSystem):
  Future[ParsedFolderData]

  /**
    * Creates the data object for the folder to start the iteration from.
    *
    * @param config         the configuration
    * @param startFolderUri the start folder URI
    * @return the object describing the initial folder to be processed
    */
  private def createInitialFolder(config: C, startFolderUri: String): SyncFolderData[HttpFolder] = {
    val rootUri = UriEncodingHelper.withTrailingSeparator(config.rootUri.toString()) +
      UriEncodingHelper.removeLeadingSeparator(UriEncodingHelper.encodeComponents(startFolderUri))
    SyncFolderData(FsFolder(startFolderUri, UriEncodingHelper.componentCount(startFolderUri) - 1), HttpFolder(rootUri))
  }

  /**
    * Returns the function that iterates over the folder structure of the web
    * dav server.
    *
    * @param ec     the execution context
    * @param system the actor system
    * @return the iterate function
    */
  private def iterateFunc(implicit ec: ExecutionContext, system: ActorSystem):
  IterateFunc[HttpFolder, HttpIterationState[C]] = (state, nextFolder) => {
    nextFolder() match {
      case Some(folder) =>
        (state, None, Some(futureResultFunc(state, folder)))
      case None =>
        (state, None, None)
    }
  }

  /**
    * Returns the function that retrieves the content of the current folder as
    * a ''Future'' result.
    *
    * @param state         the current iteration state
    * @param currentFolder the current folder
    * @param ec            the execution context
    * @param system        the actor system
    * @return the future with the content of the current folder
    */
  private def futureResultFunc(state: HttpIterationState[C], currentFolder: SyncFolderData[HttpFolder])
                              (implicit ec: ExecutionContext, system: ActorSystem):
  FutureResultFunc[HttpFolder, HttpIterationState[C]] = () =>
    loadFolder(state, currentFolder) map (processFolderResult(state, currentFolder.folder, _))

  /**
    * Returns the function to be called at the end of the iteration. This
    * function shuts down the request actor stored in the given state.
    *
    * @return the completion function
    */
  private def completionFunc: CompletionFunc[HttpIterationState[C]] = state =>
    state.requestActor ! HttpExtensionActor.Release

  /**
    * Sends a request for the content of the specified folder and parses
    * the response. This may yield more requests to be sent until the full
    * content of the folder has been obtained.
    *
    * @param state      the current iteration state
    * @param folderData the data of the folder to be loaded
    * @param ec         the execution context
    * @param system     the actor system
    * @return a future with the parsed content of the folder
    */
  private def loadFolder(state: HttpIterationState[C], folderData: SyncFolderData[HttpFolder])
                        (implicit ec: ExecutionContext, system: ActorSystem): Future[List[ElemData]] = {
    val request = createFolderRequest(state, folderData)
    loadAndAggregateFolderContent(state, folderData, request, Nil)
  }

  /**
    * Obtains the complete content of a folder by sending content requests and
    * aggregating the results until everything has been loaded.
    *
    * @param state       the current iteration state
    * @param folderData  the data of the folder to be loaded
    * @param nextRequest the next request to be executed
    * @param content     the current aggregated content of the folder
    * @param ec          the execution context
    * @param system      the actor system
    * @return a ''Future'' with the aggregated folder content
    */
  private def loadAndAggregateFolderContent(state: HttpIterationState[C], folderData: SyncFolderData[HttpFolder],
                                            nextRequest: HttpRequestActor.SendRequest, content: List[ElemData])
                                           (implicit ec: ExecutionContext, system: ActorSystem):
  Future[List[ElemData]] =
    executeFolderContentRequest(state, folderData, nextRequest) flatMap { parsedData =>
      val elements = parsedData.elements ::: content
      parsedData.nextRequest.fold(Future.successful(elements)) { req =>
        loadAndAggregateFolderContent(state, folderData, req, elements)
      }
    }

  /**
    * Executes a single request for the content of a folder and parses the
    * response.
    *
    * @param state      the current iteration state
    * @param folderData the data of the folder to be loaded
    * @param request    the request to be executed
    * @param ec         the execution context
    * @param system     the actor system
    * @return a ''Future'' with the result of the parse operation
    */
  private def executeFolderContentRequest(state: HttpIterationState[C], folderData: SyncFolderData[HttpFolder],
                                          request: HttpRequestActor.SendRequest)
                                         (implicit ec: ExecutionContext, system: ActorSystem):
  Future[ParsedFolderData] = {
    implicit val timeout: Timeout = state.config.timeout
    HttpRequestActor
      .sendAndProcess(state.requestActor, request)(parseFolderResponse(state, folderData.folder)).flatten
  }

  /**
    * Handles a result of parsing the content of a folder. If the folder
    * contains elements, they are emitted downstream, and the set of sub
    * folders pending to be processed is updated. Otherwise, processing
    * continues with the next folder in the set.
    *
    * @param elements a list of elements contained in the folder
    */
  private def processFolderResult(state: HttpIterationState[C], currentFolder: FsFolder, elements: List[ElemData]):
  (HttpIterationState[C], IterateResult[HttpFolder]) = {
    val (files, folders) = elements.foldLeft((List.empty[FsFile],
      List.empty[SyncFolderData[HttpFolder]])) { (lists, elem) =>
      elem.elem match {
        case f: FsFolder =>
          (lists._1, SyncFolderData(f, HttpFolder(elem.ref)) :: lists._2)
        case f: FsFile =>
          (f :: lists._1, lists._2)
      }
    }
    (state, IterateResult(currentFolder, files, folders))
  }

}
