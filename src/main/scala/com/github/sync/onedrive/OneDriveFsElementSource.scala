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

package com.github.sync.onedrive

import java.time.Instant

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{HttpCharsets, HttpRequest, MediaRange, MediaType}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.github.sync.SyncTypes._
import com.github.sync.http.HttpFsElementSource.HttpIterationState
import com.github.sync.http.{HttpFsElementSource, HttpRequestActor}
import com.github.sync.util.UriEncodingHelper

import scala.concurrent.{ExecutionContext, Future}

/**
  * A module providing a stream source for listing the content of a folder
  * structure located on a OneDrive server.
  */
object OneDriveFsElementSource extends HttpFsElementSource[OneDriveConfig] {
  /** Media type of the data that is expected from the server. */
  private val MediaJson = MediaRange(MediaType.applicationWithFixedCharset("json", HttpCharsets.`UTF-8`))

  /** The Accept header to be used by all requests. */
  private val HeaderAccept = Accept(MediaJson)

  /** List with the headers sent for each request. */
  private val Headers = List(HeaderAccept)

  /** The suffix required to obtain the child elements of a folder path. */
  private val PathChildren = ":/children"

  /**
    * Creates a ''Source'' based on this class using the specified
    * configuration.
    *
    * @param config         the configuration
    * @param sourceFactory  the factory for the element source
    * @param requestActor   the actor for sending HTTP requests
    * @param startFolderUri URI of a folder to start the iteration
    * @param system         the actor system
    * @param mat            the object to materialize streams
    * @return the new source
    */
  def apply(config: OneDriveConfig, sourceFactory: ElementSourceFactory, requestActor: ActorRef,
            startFolderUri: String = "")
           (implicit system: ActorSystem, mat: ActorMaterializer):
  Source[FsElement, NotUsed] = createSource(config, sourceFactory, requestActor, startFolderUri)

  override protected def createFolderRequest(state: HttpFsElementSource.HttpIterationState[OneDriveConfig],
                                             folderData: SyncFolderData[HttpFsElementSource.HttpFolder]):
  HttpRequestActor.SendRequest = {
    val request = HttpRequest(uri = UriEncodingHelper.removeTrailingSeparator(folderData.data.ref) + PathChildren,
      headers = Headers)
    HttpRequestActor.SendRequest(request, null)
  }

  /**
    * Parses the response received for a folder request. Here a concrete
    * implementation has to process the response from the server and extracts
    * the elements (files and folders) that are referenced.
    *
    * @param state  the current state
    * @param folder the folder whose content is to be computed
    * @param result the result of the request for this folder
    * @param ec     the execution context
    * @param mat    the object to materialize streams
    * @return a ''Future'' with the elements that have been extracted
    */
  override protected def parseFolderResponse(state: HttpFsElementSource.HttpIterationState[OneDriveConfig],
                                             folder: FsFolder)(result: HttpRequestActor.Result)
                                            (implicit ec: ExecutionContext, mat: ActorMaterializer):
  Future[List[HttpFsElementSource.ElemData]] = {
    import OneDriveJsonProtocol._
    val model = Unmarshal(result.response).to[OneDriveModel]
    model map (m => extractElements(state, folder, m.value))
  }

  /**
    * Extracts the single elements contained in a folder from the OneDrive
    * JSON representation of this folder.
    *
    * @param state  the current iteration state
    * @param parent the parent folder
    * @param items  the list with OneDrive items contained in the current folder
    * @return the resulting list of extracted elements
    */
  private def extractElements(state: HttpIterationState[OneDriveConfig], parent: FsFolder,
                              items: List[OneDriveItem]): List[HttpFsElementSource.ElemData] =
    items map (convertItemToElement(state, parent, _))

  /**
    * Converts an item from the OneDrive JSON representation to an element that
    * can be processed by [[HttpFsElementSource]].
    *
    * @param state  the current iteration state
    * @param parent the parent folder
    * @param item   the item to be converted
    * @return the resulting ''ElemData'' object
    */
  private def convertItemToElement(state: HttpIterationState[OneDriveConfig], parent: FsFolder,
                                   item: OneDriveItem): HttpFsElementSource.ElemData = {
    val elemUri = parent.relativeUri + UriEncodingHelper.UriSeparator + item.name
    val ref = state.rootUriPrefix + UriEncodingHelper.encodeComponents(elemUri)
    val elem = item.file match {
      case None =>
        FsFolder(elemUri, parent.level + 1)
      case Some(_) =>
        FsFile(elemUri, parent.level + 1, Instant.parse(item.fileSystemInfo.get.lastModifiedDateTime), item.size)
    }
    HttpFsElementSource.ElemData(ref, elem)
  }
}
