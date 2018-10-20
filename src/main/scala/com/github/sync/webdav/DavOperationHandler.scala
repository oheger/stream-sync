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

package com.github.sync.webdav

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge}
import akka.stream.{ActorMaterializer, FlowShape}
import com.github.sync._
import com.github.sync.util.LoggingStage

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

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

  /**
    * Returns a ''Flow'' to apply [[SyncOperation]] objects against a WebDav
    * server. The flow executes the operations from upstream and passes all
    * that were successful to downstream.
    *
    * @param config       the configuration for the WebDav server
    * @param fileProvider the object providing access to files to upload
    * @param system       the actor system
    * @param mat          the object to materialize streams
    * @return the flow to process operations against a WebDav server
    */
  def webDavProcessingFlow(config: DavConfig, fileProvider: SourceFileProvider)
                          (implicit system: ActorSystem, mat: ActorMaterializer):
  Flow[SyncOperation, SyncOperation, NotUsed] = {
    val uriResolver = ElementUriResolver(config.rootUri)
    val headers = List(authHeader(config))
    val modifiedTimeTemplate = ModifiedTimeRequestFactory.requestTemplate(config)
    import system.dispatcher

    // Creates a flow that consumes the entities of HTTP responses and filters
    // out failed responses; only sync operations processed successfully are
    // passed through
    def createSyncOperationRequestFlow():
    Flow[(HttpRequest, SyncOperation), SyncOperation, Any] = {
      val requestFlow = LoggingStage.withLogging(
        createPoolClientFlow[SyncOperation](config.rootUri, Http()))(logResponse)
      val mapFlow = Flow[(Try[HttpResponse], SyncOperation)]
        .mapAsync[SyncOperation](1)(processResponse)
      requestFlow.via(mapFlow)
    }

    // Processes a response by checking whether it was successful and always
    // consuming its entity
    def processResponse(triedResult: (Try[HttpResponse], SyncOperation)):
    Future[SyncOperation] =
      Future.fromTry(triedResult._1)
        .flatMap(resp => consumeEntity(resp, triedResult._2))
        .filter(_._1.status.isSuccess())
        .map(_._2)

    // Makes sure that the entity of a response is consumed
    def consumeEntity(response: HttpResponse, op: SyncOperation):
    Future[(HttpResponse, SyncOperation)] =
      response.entity.discardBytes().future().map(_ => (response, op))

    // Transforms a sync operation into an HTTP request
    def createRequest(op: SyncOperation): Future[HttpRequest] = {
      val uri = uriResolver.resolveElementUri(op.element.relativeUri)
      op match {
        case SyncOperation(_, ActionRemove, _) =>
          Future.successful(HttpRequest(method = HttpMethods.DELETE, uri = uri, headers = headers))
        case SyncOperation(FsFolder(_, _), ActionCreate, _) =>
          Future.successful(HttpRequest(method = MethodMkCol, uri = uri, headers = headers))
        case SyncOperation(file@FsFile(_, _, _, _), _, _) =>
          createUploadRequest(file)
        case _ =>
          Future.failed(new IllegalStateException("Invalid SyncOperation: " + op))
      }
    }

    // Creates a request to upload a file
    def createUploadRequest(file: FsFile): Future[HttpRequest] = {
      fileProvider.fileSource(file) map { content =>
        val entity = HttpEntity(ContentTypes.`application/octet-stream`, file.size,
          content)
        HttpRequest(method = HttpMethods.PUT, headers = headers, entity = entity,
          uri = uriResolver.resolveElementUri(file.relativeUri))
      }
    }

    // Creates a request to update the modified date of an uploaded file
    def createPatchRequest(op: SyncOperation): HttpRequest = {
      val modifiedTime = op.element.asInstanceOf[FsFile].lastModified
      val content = ModifiedTimeRequestFactory
        .createModifiedTimeRequest(modifiedTimeTemplate, modifiedTime)
      HttpRequest(method = MethodPropPatch, headers = headers,
        uri = uriResolver.resolveElementUri(op.element.relativeUri),
        entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, content))
    }

    // Creates a Future with the data that goes into the request flow
    def createRequestTuple(op: SyncOperation): Future[(HttpRequest, SyncOperation)] =
      createRequest(op) map ((_, op))

    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._
      val requestMapper = Flow[SyncOperation].mapAsync(1)(createRequestTuple)
      val requestFlow = builder.add(LoggingStage.withLogging(requestMapper)(logRequest))
      val requestPatchMapper = Flow[SyncOperation].map { op => (createPatchRequest(op), op) }
      val requestPatchFlow = builder.add(LoggingStage.withLogging(requestPatchMapper)(logRequest))
      val requestFlowOp = createSyncOperationRequestFlow()
      val requestFlowPatch = createSyncOperationRequestFlow()
      val broadcast = builder.add(Broadcast[SyncOperation](2))
      val merge = builder.add(Merge[SyncOperation](2))
      val filterUpload = Flow[SyncOperation].filter(isUpload)
      val filterNoUpload = Flow[SyncOperation].filterNot(isUpload)

      requestFlow ~> requestFlowOp ~> broadcast
      broadcast ~> filterUpload ~> requestPatchFlow ~> requestFlowPatch ~> merge
      broadcast ~> filterNoUpload ~> merge
      FlowShape(requestFlow.in, merge.out)
    })
  }

  /**
    * Checks whether the specified operation requires a file upload.
    *
    * @param op the operation to be checked
    * @return '''true''' if this operation requires a file upload; '''false'''
    *         otherwise
    */
  private def isUpload(op: SyncOperation): Boolean =
    op match {
      case SyncOperation(_, ActionOverride, _) => true
      case SyncOperation(FsFile(_, _, _, _), ActionCreate, _) => true
      case _ => false
    }

  /**
    * Logs a request before it is sent to the WebDav server.
    *
    * @param elem the stream element to be logged
    * @param log  the logging adapter
    */
  private def logRequest(elem: (HttpRequest, SyncOperation), log: LoggingAdapter): Unit = {
    val request = elem._1
    log.info("{} {}", request.method.value, request.uri)
  }

  /**
    * Logs the response of a request. This function only logs failed responses.
    *
    * @param elem the stream element to be logged
    * @param log  the logging adapter
    */
  private def logResponse(elem: (Try[HttpResponse], SyncOperation), log: LoggingAdapter): Unit = {
    elem._1 match {
      case Success(response) =>
        if (!response.status.isSuccess()) {
          log.error("{} {}", response.status.intValue(), response.status.reason())
        }
      case Failure(exception) =>
        log.error(exception, "Failed request!")
    }
  }
}
