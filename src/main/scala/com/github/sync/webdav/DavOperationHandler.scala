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

package com.github.sync.webdav

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.http.scaladsl.model._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import com.github.sync.SyncTypes._
import com.github.sync._
import com.github.sync.impl.CleanupStage
import com.github.sync.webdav.SyncOperationRequestActor.{SyncOperationRequestData, SyncOperationResult}

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * A module implementing functionality to execute ''SyncOperation'' objects
  * against a WebDav server.
  *
  * This object provides a method for creating a ''Flow'' to process
  * [[SyncOperation]] objects and apply the corresponding changes to a WebDav
  * server. This flow can be directly integrated into a sync stream.
  */
object DavOperationHandler {
  /** The WebDav HTTP method to create a collection. */
  private val MethodMkCol = HttpMethod.custom("MKCOL")

  /** The WebDav HTTP method for setting attributes of an element. */
  private val MethodPropPatch = HttpMethod.custom("PROPPATCH")

  /** The configuration property for the size of the connection pool. */
  private val PropMaxConnections = "akka.http.host-connection-pool.max-connections"

  /**
    * Returns a ''Flow'' to apply [[SyncOperation]] objects against a WebDav
    * server. The flow executes the operations from upstream and passes all
    * that were successful to downstream.
    *
    * @param config       the configuration for the WebDav server
    * @param fileProvider the object providing access to files to upload
    * @param requestActor the actor for sending HTTP requests
    * @param system       the actor system
    * @param mat          the object to materialize streams
    * @return the flow to process operations against a WebDav server
    */
  def webDavProcessingFlow(config: DavConfig, fileProvider: SourceFileProvider, requestActor: ActorRef)
                          (implicit system: ActorSystem, mat: ActorMaterializer):
  Flow[SyncOperation, SyncOperation, NotUsed] = {
    val uriResolver = ElementUriResolver(config.rootUri)
    val modifiedTimeTemplate = ModifiedTimeRequestFactory.requestTemplate(config)
    val syncRequestActor =
      system.actorOf(SyncOperationRequestActor(requestActor, config.timeout), "syncOperationRequestActor")
    // set a long implicit timeout; timeouts are handled by the request actor
    implicit val syncOpTimeout: Timeout = Timeout(1.day)
    import system.dispatcher

    /**
      * Returns the size of the HTTP connection pool from the configuration of
      * the actor system. This is used to determine the parallelism in the
      * stream for the request execution stage.
      *
      * @return the size of the HTTP connection pool
      */
    def httpPoolSize: Int =
      system.settings.config.getInt(PropMaxConnections)

    // Creates the object with requests for a single sync operation
    def createRequestData(op: SyncOperation): Future[SyncOperationRequestData] = {
      op match {
        case SyncOperation(elem, ActionRemove, _, _, dstUri) =>
          simpleRequest(op, HttpRequest(method = HttpMethods.DELETE,
            uri = resolveRemoveUri(elem, dstUri)))
        case SyncOperation(FsFolder(_, _, _), ActionCreate, _, _, dstUri) =>
          simpleRequest(op, HttpRequest(method = MethodMkCol,
            uri = uriResolver.resolveElementUri(dstUri)))
        case SyncOperation(file@FsFile(_, _, _, _, _), action, _, srcUri, dstUri) =>
          createUploadRequest(file, srcUri, dstUri) map { req =>
            val needDelete = config.deleteBeforeOverride && action == ActionOverride
            val standardRequests = List(req, createPatchRequest(op))
            val requests = if (needDelete)
              HttpRequest(method = HttpMethods.DELETE,
                uri = uriResolver.resolveElementUri(dstUri)) :: standardRequests
            else standardRequests
            SyncOperationRequestData(op, requests)
          }
        case _ =>
          Future.failed(new IllegalStateException("Invalid SyncOperation: " + op))
      }
    }

    // Resolves the URI to remove an element. Ensures that URIs for folders
    // end on a slash.
    def resolveRemoveUri(elem: FsElement, uri: String): Uri =
      uriResolver.resolveElementUri(uri, withTrailingSlash = elem.isInstanceOf[FsFolder])

    // Creates a SyncOpRequestData future object for a simple request
    def simpleRequest(op: SyncOperation, request: HttpRequest): Future[SyncOperationRequestData] =
      Future.successful(SyncOperationRequestData(op, List(request)))

    // Creates a request to upload a file
    def createUploadRequest(file: FsFile, srcUri: String, dstUri: String): Future[HttpRequest] = {
      fileProvider.fileSource(srcUri) map { content =>
        val entity = HttpEntity(ContentTypes.`application/octet-stream`,
          fileProvider.fileSize(file.size), content)
        HttpRequest(method = HttpMethods.PUT, entity = entity,
          uri = uriResolver.resolveElementUri(dstUri))
      }
    }

    // Creates a request to update the modified date of an uploaded file
    def createPatchRequest(op: SyncOperation): HttpRequest = {
      val modifiedTime = op.element.asInstanceOf[FsFile].lastModified
      val content = ModifiedTimeRequestFactory
        .createModifiedTimeRequest(modifiedTimeTemplate, modifiedTime)
      HttpRequest(method = MethodPropPatch,
        uri = uriResolver.resolveElementUri(op.dstUri),
        entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, content))
    }

    // Sends a request to the actor for executing sync operations
    def executeRequest(request: SyncOperationRequestData): Future[SyncOperationResult] =
      (syncRequestActor ? request).mapTo[SyncOperationResult]

    Flow[SyncOperation].mapAsync(1)(createRequestData)
      .mapAsync(httpPoolSize)(executeRequest)
      .filter(_.optFailure.isEmpty)
      .map(_.op)
      .via(new CleanupStage[SyncOperation](() => syncRequestActor ! PoisonPill))
  }
}
