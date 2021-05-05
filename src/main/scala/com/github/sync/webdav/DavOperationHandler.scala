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

package com.github.sync.webdav

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import com.github.sync.SyncTypes._
import com.github.sync._
import com.github.sync.http.HttpOperationHandler
import com.github.sync.http.SyncOperationRequestActor.SyncOperationRequestData
import com.github.sync.webdav.DavOperationHandler.{MethodMkCol, MethodPropPatch}

import scala.concurrent.{ExecutionContext, Future}

object DavOperationHandler {
  /** The WebDav HTTP method to create a collection. */
  private val MethodMkCol = HttpMethod.custom("MKCOL")

  /** The WebDav HTTP method for setting attributes of an element. */
  private val MethodPropPatch = HttpMethod.custom("PROPPATCH")

  /**
    * Creates the ''Flow'' for applying sync operations against the WebDav
    * server defined by the passed in configuration object.
    *
    * @param config       the WebDav configuration
    * @param fileProvider the file provider
    * @param requestActor the request actor
    * @param system       the actor system
    * @return the ''Flow'' for applying sync operations
    */
  def apply(config: DavConfig, fileProvider: SourceFileProvider, requestActor: ActorRef)
           (implicit system: ActorSystem): Flow[SyncOperation, SyncOperation, NotUsed] = {
    val modifiedTimeTemplate = ModifiedTimeRequestFactory.requestTemplate(config)
    val handler = new DavOperationHandler(config, modifiedTimeTemplate)
    handler.webDavProcessingFlow(config, fileProvider, requestActor)
  }
}

/**
  * A class implementing functionality to execute ''SyncOperation'' objects
  * against a WebDav server.
  *
  * This class provides a method for creating a ''Flow'' to process
  * [[SyncOperation]] objects and apply the corresponding changes to a WebDav
  * server. This flow can be directly integrated into a sync stream.
  *
  * @param config               the config of the WebDav server
  * @param modifiedTimeTemplate a template string to path an element's time
  */
class DavOperationHandler(config: DavConfig, modifiedTimeTemplate: String) extends HttpOperationHandler[DavConfig] {
  override protected def createRemoveFolderRequest(op: SyncOperation, folder: FsFolder)
                                                  (implicit ec: ExecutionContext):
  Future[SyncOperationRequestData] = createDeleteRequest(op, isFolder = true)

  override protected def createRemoveFileRequest(op: SyncOperation, file: FsFile)(implicit ec: ExecutionContext):
  Future[SyncOperationRequestData] = createDeleteRequest(op, isFolder = false)

  override protected def createNewFolderRequest(op: SyncOperation, folder: FsFolder)
                                               (implicit ec: ExecutionContext):
  Future[SyncOperationRequestData] =
    simpleRequest(op, HttpRequest(method = MethodMkCol, uri = config.resolveRelativeUri(op.dstUri)))

  override protected def createNewFileRequest(op: SyncOperation, file: FsFile, fileSize: Long,
                                              source: Future[Source[ByteString, Any]])
                                             (implicit ec: ExecutionContext, system: ActorSystem):
  Future[SyncOperationRequestData] =
    createFileUploadRequest(op, file, fileSize, source, isUpdate = false)

  override protected def createUpdateFileRequest(op: SyncOperation, file: FsFile, fileSize: Long,
                                                 source: Future[Source[ByteString, Any]])
                                                (implicit ec: ExecutionContext, system: ActorSystem):
  Future[SyncOperationRequestData] =
    createFileUploadRequest(op, file, fileSize, source, isUpdate = true)

  /**
    * Returns the request to delete an element from the WebDav server. This
    * is basically the same for files and folders; however, for folders the
    * URI must explicitly end on a slash.
    *
    * @param op       the sync operation
    * @param isFolder flag whether a folder is affected
    * @return request information for a DELETE operation
    */
  private def createDeleteRequest(op: SyncOperation, isFolder: Boolean):
  Future[SyncOperationRequestData] =
    simpleRequest(op, HttpRequest(HttpMethods.DELETE,
      uri = config.resolveRelativeUri(op.dstUri, withTrailingSlash = isFolder)))

  /**
    * Creates the request information for a sync operation that requires a
    * file upload. Here multiple requests are needed: an optional DELETE
    * request for an update, the actual upload request, and a request to
    * update the meta data of the file.
    *
    * @param op       the sync operation
    * @param file     the file affected
    * @param fileSize the adjusted file size
    * @param source   the source with the file's content
    * @param isUpdate flag whether this is an update
    * @param ec       the execution context
    * @return request information for an upload operation
    */
  private def createFileUploadRequest(op: SyncOperation, file: FsFile,
                                      fileSize: Long, source: Future[Source[ByteString, Any]], isUpdate: Boolean)
                                     (implicit ec: ExecutionContext): Future[SyncOperationRequestData] = {
    createUploadRequest(file, fileSize, source, op.dstUri) map { req =>
      val needDelete = isUpdate && config.deleteBeforeOverride
      val standardRequests = List(req, createPatchRequest(op, modifiedTimeTemplate))
      val requests = if (needDelete)
        HttpRequest(method = HttpMethods.DELETE,
          uri = config.resolveRelativeUri(op.dstUri)) :: standardRequests
      else standardRequests
      SyncOperationRequestData(op, Source(requests))
    }
  }

  /**
    * Creates a request to upload a file.
    *
    * @param fileSize the adjusted file size
    * @param source   a ''Future'' with the content source of the file
    * @param file     the file affected
    * @param dstUri   the destination URI for the upload
    * @param ec       the execution context
    * @return a ''Future'' with the upload request
    */
  private def createUploadRequest(file: FsFile, fileSize: Long, source: Future[Source[ByteString, Any]],
                                  dstUri: String)(implicit ec: ExecutionContext): Future[HttpRequest] = {
    source map { content =>
      val entity = HttpEntity(ContentTypes.`application/octet-stream`, fileSize, content)
      HttpRequest(method = HttpMethods.PUT, entity = entity,
        uri = config.resolveRelativeUri(dstUri))
    }
  }

  /**
    * Creates a request to update the modified date of an uploaded file.
    *
    * @param op                   the current sync operation
    * @param modifiedTimeTemplate the template string to generate the request
    * @return
    */
  private def createPatchRequest(op: SyncOperation, modifiedTimeTemplate: String): HttpRequest = {
    val modifiedTime = op.element.asInstanceOf[FsFile].lastModified
    val content = ModifiedTimeRequestFactory
      .createModifiedTimeRequest(modifiedTimeTemplate, modifiedTime)
    HttpRequest(method = MethodPropPatch,
      uri = config.resolveRelativeUri(op.dstUri),
      entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, content))
  }
}
