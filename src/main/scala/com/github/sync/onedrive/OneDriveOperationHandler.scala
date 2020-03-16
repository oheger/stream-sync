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

package com.github.sync.onedrive

import java.util.Locale

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Source}
import akka.util.{ByteString, Timeout}
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes.{FsFile, FsFolder, SyncOperation}
import com.github.sync.http.SyncOperationRequestActor.SyncOperationRequestData
import com.github.sync.http.{HttpOperationHandler, HttpRequestActor}
import com.github.sync.util.UriEncodingHelper

import scala.concurrent.{ExecutionContext, Future}

object OneDriveOperationHandler {
  /** Template for a request to create a new folder. */
  private val CreateFolderTemplate =
    """
      |{
      |  "name": "%s",
      |  "folder": {},
      |  "@microsoft.graph.conflictBehavior": "fail"
      |}
      |""".stripMargin

  /** Template for a request to create an upload session. */
  private val CreateUploadSessionTemplate =
    s"""
       |{
       |  "item": {
       |    "@microsoft.graph.conflictBehavior": "replace",
       |    "fileSystemInfo": {
       |      "lastModifiedDateTime": "%s"
       |    }
       |  }
       |}
       |""".stripMargin

  /** Media type of the data that is expected from the server. */
  private val MediaJson = MediaRange(MediaType.applicationWithFixedCharset("json", HttpCharsets.`UTF-8`))

  /** The Accept header to be used by all requests. */
  private val HeaderAccept = Accept(MediaJson)

  /** A list with the headers for a request for an upload session. */
  private val UploadSessionRequestHeaders = List(HeaderAccept)

  /**
    * Creates the ''Flow'' for applying sync operations against a OneDrive
    * server.
    *
    * @param config       the OneDrive configuration
    * @param fileProvider the ''SourceFileProvider''
    * @param requestActor the actor to execute HTTP requests
    * @param system       the actor system
    * @return the ''Flow'' to apply sync operations
    */
  def apply(config: OneDriveConfig, fileProvider: SourceFileProvider, requestActor: ActorRef)
           (implicit system: ActorSystem): Flow[SyncOperation, SyncOperation, NotUsed] = {
    val handler = new OneDriveOperationHandler(config, requestActor)
    handler.webDavProcessingFlow(config, fileProvider, requestActor)
  }
}

/**
  * A class that implements functionality to execute [[SyncOperation]] objects
  * against a OneDrive server.
  *
  * The class generates the requests to manipulate a OneDrive structure
  * according to a stream of ''SyncOperation'' objects.
  *
  * @param config       the configuration of the OneDrive server
  * @param requestActor the HTTP request actor
  * @param system       the actor system
  */
class OneDriveOperationHandler(config: OneDriveConfig, requestActor: ActorRef)(implicit system: ActorSystem)
  extends HttpOperationHandler[OneDriveConfig] {

  import OneDriveOperationHandler._

  override protected def createRemoveFolderRequest(op: SyncOperation, folder: FsFolder)(implicit ec: ExecutionContext):
  Future[SyncOperationRequestData] = createRemoveRequest(op)

  override protected def createRemoveFileRequest(op: SyncOperation, file: FsFile)(implicit ec: ExecutionContext):
  Future[SyncOperationRequestData] = createRemoveRequest(op)

  override protected def createNewFolderRequest(op: SyncOperation, folder: FsFolder)(implicit ec: ExecutionContext):
  Future[SyncOperationRequestData] = {
    val (_, encName) = UriEncodingHelper.splitParent(op.dstUri)
    val name = UriEncodingHelper.decode(encName)
    val body = String.format(Locale.ROOT, CreateFolderTemplate, name)
    val request = HttpRequest(method = HttpMethods.POST, uri = config.resolveFolderChildrenUri(op.dstUri),
      entity = HttpEntity(ContentTypes.`application/json`, body))
    simpleRequest(op, request)
  }

  override protected def createNewFileRequest(op: SyncOperation, file: FsFile, fileSize: Long,
                                              source: Future[Source[ByteString, Any]])
                                             (implicit ec: ExecutionContext, system: ActorSystem):
  Future[SyncOperationRequestData] = createUploadRequest(op, file, fileSize, source)

  override protected def createUpdateFileRequest(op: SyncOperation, file: FsFile, fileSize: Long,
                                                 source: Future[Source[ByteString, Any]])
                                                (implicit ec: ExecutionContext, system: ActorSystem):
  Future[SyncOperationRequestData] = createUploadRequest(op, file, fileSize, source)

  /**
    * Generates the request to delete an element. This can be used for both
    * files and folders.
    *
    * @param op the sync operation
    * @return a ''Future'' with information about the request
    */
  private def createRemoveRequest(op: SyncOperation):
  Future[SyncOperationRequestData] =
    simpleRequest(op, HttpRequest(method = HttpMethods.DELETE, uri = config.resolveRelativeUri(op.dstUri)))

  /**
    * Generates the request to upload a file. Here multiple requests can be
    * returned, if the file upload needs to be split into multiple chunks.
    *
    * @param op       the sync operation
    * @param file     the file to be uploaded
    * @param fileSize the total file size
    * @param source   the source with the content of the file
    * @param ec       the execution context
    * @param system           the actor system
    * @return a ''Future'' with information about the request
    */
  private def createUploadRequest(op: SyncOperation, file: FsFile, fileSize: Long,
                                  source: Future[Source[ByteString, Any]])
                                 (implicit ec: ExecutionContext, system: ActorSystem):
  Future[SyncOperationRequestData] =
    for {
      uploadUri <- fetchUploadUri(op, file)
      dataSource <- source
    } yield OneDriveUpload.upload(config, fileSize, dataSource, uploadUri, op)

  /**
    * Obtains the URI for a file upload operation. This function sends a
    * request to create a new upload session. From the response the upload URI
    * can be extracted.
    *
    * @param op   the current sync operation
    * @param file the file to be uploaded
    * @param ec   the execution context
    * @return a ''Future'' with the upload URI
    */
  private def fetchUploadUri(op: SyncOperation, file: FsFile)
                            (implicit ec: ExecutionContext): Future[String] = {
    val body = String.format(Locale.ROOT, CreateUploadSessionTemplate, file.lastModified)
    val request = HttpRequest(method = HttpMethods.POST, headers = UploadSessionRequestHeaders,
      uri = config.resolveItemsUri(op.dstUri + ":/createUploadSession"),
      entity = HttpEntity(ContentTypes.`application/json`, body))
    implicit val timeout: Timeout = config.timeout
    import OneDriveJsonProtocol._
    for {
      result <- HttpRequestActor.sendRequest(requestActor, HttpRequestActor.SendRequest(request, null))
      sessionResponse <- Unmarshal(result.response).to[UploadSessionResponse]
    } yield sessionResponse.uploadUrl
  }
}
