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

import java.util.Locale

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import com.github.sync.{SourceFileProvider, SyncTypes}
import com.github.sync.SyncTypes.{FsFile, FsFolder, SyncOperation}
import com.github.sync.http.SyncOperationRequestActor.SyncOperationRequestData
import com.github.sync.http.{ElementUriResolver, HttpOperationHandler, SyncOperationRequestActor}
import com.github.sync.util.UriEncodingHelper

import scala.concurrent.{ExecutionContext, Future}

object OneDriveOperationHandler {
  /** Template for a request to create a new folder. */
  private val CreateFolderTemplate =
    s"""
       |{
       |  "name": "%s",
       |  "folder": {},
       |  "@microsoft.graph.conflictBehavior": "fail"
       |}
       |""".stripMargin

  /**
    * Creates the ''Flow'' for applying sync operations against a OneDrive
    * server.
    *
    * @param config       the OneDrive configuration
    * @param fileProvider the ''SourceFileProvider''
    * @param requestActor the actor to execute HTTP requests
    * @param system       the actor system
    * @param mat          the object to materialize streams
    * @return the ''Flow'' to apply sync operations
    */
  def apply(config: OneDriveConfig, fileProvider: SourceFileProvider, requestActor: ActorRef)
           (implicit system: ActorSystem, mat: ActorMaterializer): Flow[SyncOperation, SyncOperation, NotUsed] = {
    val handler = new OneDriveOperationHandler(config)
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
  * @param config the configuration of the OneDrive server
  */
class OneDriveOperationHandler(config: OneDriveConfig) extends HttpOperationHandler[OneDriveConfig] {

  import OneDriveOperationHandler._

  override protected def createRemoveFolderRequest(uriResolver: ElementUriResolver, op: SyncOperation,
                                                   folder: FsFolder)(implicit ec: ExecutionContext):
  Future[SyncOperationRequestData] = createRemoveRequest(uriResolver, op)

  override protected def createRemoveFileRequest(uriResolver: ElementUriResolver, op: SyncOperation, file: FsFile)
                                                (implicit ec: ExecutionContext):
  Future[SyncOperationRequestData] = createRemoveRequest(uriResolver, op)

  /**
    * Creates an object describing the request to create a new folder.
    *
    * @param uriResolver the URI resolver
    * @param op          the sync operation
    * @param folder      the folder affected
    * @param ec          the execution context
    * @return a ''Future'' with request information
    */
  override protected def createNewFolderRequest(uriResolver: ElementUriResolver, op: SyncOperation,
                                                folder: FsFolder)(implicit ec: ExecutionContext):
  Future[SyncOperationRequestData] = {
    val (parent, encName) = UriEncodingHelper.splitParent(op.dstUri)
    val name = UriEncodingHelper.decode(encName)
    val body = String.format(Locale.ROOT, CreateFolderTemplate, name)
    val request = HttpRequest(method = HttpMethods.POST, uri = uriResolver.resolveElementUri(parent) + ":/children",
      entity = HttpEntity(body))
    simpleRequest(op, request)
  }

  /**
    * Creates an object describing the request to create a new file.
    *
    * @param uriResolver the URI resolver
    * @param op          the sync operation
    * @param fileSize    the adjusted file size
    * @param file        the file affected
    * @param source      a ''Source'' with the content of the file
    * @param ec          the execution context
    * @param mat         the object to materialize streams
    * @return a ''Future'' with request information
    */
  override protected def createNewFileRequest(uriResolver: ElementUriResolver, op: SyncTypes.SyncOperation, file: SyncTypes.FsFile, fileSize: Long, source: Future[Source[ByteString, Any]])(implicit ec: ExecutionContext, mat: ActorMaterializer): Future[SyncOperationRequestActor.SyncOperationRequestData] = ???

  /**
    * Creates an object describing the request to replace a file on the server.
    *
    * @param uriResolver the URI resolver
    * @param op          the sync operation
    * @param fileSize    the adjusted file size
    * @param file        the file affected
    * @param source      a ''Source'' with the content of the file
    * @param ec          the execution context
    * @param mat         the object to materialize streams
    * @return a ''Future'' with request information
    */
  override protected def createUpdateFileRequest(uriResolver: ElementUriResolver, op: SyncTypes.SyncOperation, file: SyncTypes.FsFile, fileSize: Long, source: Future[Source[ByteString, Any]])(implicit ec: ExecutionContext, mat: ActorMaterializer): Future[SyncOperationRequestActor.SyncOperationRequestData] = ???

  /**
    * Generates the request to delete an element. This can be used for both
    * files and folders.
    *
    * @param uriResolver the URI resolver
    * @param op          the sync operation
    * @return a ''Future'' with information about the request
    */
  private def createRemoveRequest(uriResolver: ElementUriResolver, op: SyncOperation):
  Future[SyncOperationRequestData] =
    simpleRequest(op, HttpRequest(method = HttpMethods.DELETE, uri = uriResolver.resolveElementUri(op.dstUri)))
}
