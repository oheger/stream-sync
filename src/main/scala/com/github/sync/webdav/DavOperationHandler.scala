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
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge}
import akka.stream.{ActorMaterializer, FlowShape}
import com.github.sync.SyncTypes._
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
    * A data class storing the requests to be executed for a single sync
    * operation.
    *
    * For some operations multiple requests are to be executed. They are held
    * in a list. The processing flow processes an instance in a cycle until all
    * requests are handled.
    *
    * @param op       the sync operation
    * @param requests the list with requests for this operation
    */
  private case class SyncOpRequestData(op: SyncOperation, requests: List[HttpRequest])

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
    Flow[(HttpRequest, SyncOpRequestData), SyncOpRequestData, Any] = {
      val requestFlow = LoggingStage.withLogging(
        createPoolClientFlow[SyncOpRequestData](config.rootUri, Http()))(logResponse)
      val mapFlow = Flow[(Try[HttpResponse], SyncOpRequestData)]
        .mapAsync[SyncOpRequestData](1)(processResponse)
      requestFlow.via(mapFlow)
    }

    // Processes a response by checking whether it was successful and always
    // consuming its entity
    def processResponse(triedResult: (Try[HttpResponse], SyncOpRequestData)):
    Future[SyncOpRequestData] =
      Future.fromTry(triedResult._1)
        .flatMap(resp => consumeEntity(resp, triedResult._2))
        .filter(_._1.status.isSuccess())
        .map(_._2)

    // Makes sure that the entity of a response is consumed
    def consumeEntity(response: HttpResponse, op: SyncOpRequestData):
    Future[(HttpResponse, SyncOpRequestData)] =
      response.entity.discardBytes().future().map(_ => (response, op))

    // Creates a tuple with the request data and the next request to execute
    def createRequestTuple(data: SyncOpRequestData, reqNo: Int):
    (HttpRequest, SyncOpRequestData) = (data.requests(reqNo), data)

    // Creates a flow that executes one specific request from a
    // SyncOpRequestData object if it is defined. Otherwise, the data object is
    // just passed through. The resulting flow is a building block for
    // processing sync operations that can require multiple requests.
    def createOptionalRequestExecutionFlow(reqNo: Int):
    Flow[SyncOpRequestData, SyncOpRequestData, Any] = {
      val hasRequest: SyncOpRequestData => Boolean = _.requests.size >= reqNo + 1
      Flow.fromGraph(GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._
        val filterHasRequest = Flow[SyncOpRequestData].filter(hasRequest)
        val filterSkip = Flow[SyncOpRequestData].filterNot(hasRequest)
        val selectRequestFlow = Flow[SyncOpRequestData].map(createRequestTuple(_, reqNo))
        val requestFlow = builder.add(LoggingStage.withLogging(selectRequestFlow)(logRequest))
        val requestFlowOp = createSyncOperationRequestFlow()
        val broadcast = builder.add(Broadcast[SyncOpRequestData](2))
        val merge = builder.add(Merge[SyncOpRequestData](2))

        broadcast ~> filterHasRequest ~> requestFlow ~> requestFlowOp ~> merge
        broadcast ~> filterSkip ~> merge
        FlowShape(broadcast.in, merge.out)
      })
    }

    // Creates the object with requests for a single sync operation
    def createRequestData(op: SyncOperation): Future[SyncOpRequestData] = {
      op match {
        case SyncOperation(elem, ActionRemove, _, _, dstUri) =>
          simpleRequest(op, HttpRequest(method = HttpMethods.DELETE, headers = headers,
            uri = resolveRemoveUri(elem, dstUri)))
        case SyncOperation(FsFolder(_, _, _), ActionCreate, _, _, dstUri) =>
          simpleRequest(op, HttpRequest(method = MethodMkCol, headers = headers,
            uri = uriResolver.resolveElementUri(dstUri)))
        case SyncOperation(file@FsFile(_, _, _, _, _), action, _, srcUri, dstUri) =>
          createUploadRequest(file, srcUri, dstUri) map { req =>
            val needDelete = config.deleteBeforeOverride && action == ActionOverride
            val standardRequests = List(req, createPatchRequest(op))
            val requests = if (needDelete)
              HttpRequest(method = HttpMethods.DELETE, uri = uriResolver.resolveElementUri(dstUri),
                headers = headers) :: standardRequests
            else standardRequests
            SyncOpRequestData(op, requests)
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
    def simpleRequest(op: SyncOperation, request: HttpRequest): Future[SyncOpRequestData] =
      Future.successful(SyncOpRequestData(op, List(request)))

    // Creates a request to upload a file
    def createUploadRequest(file: FsFile, srcUri: String, dstUri: String): Future[HttpRequest] = {
      fileProvider.fileSource(srcUri) map { content =>
        val entity = HttpEntity(ContentTypes.`application/octet-stream`,
          fileProvider.fileSize(file.size), content)
        HttpRequest(method = HttpMethods.PUT, headers = headers, entity = entity,
          uri = uriResolver.resolveElementUri(dstUri))
      }
    }

    // Creates a request to update the modified date of an uploaded file
    def createPatchRequest(op: SyncOperation): HttpRequest = {
      val modifiedTime = op.element.asInstanceOf[FsFile].lastModified
      val content = ModifiedTimeRequestFactory
        .createModifiedTimeRequest(modifiedTimeTemplate, modifiedTime)
      HttpRequest(method = MethodPropPatch, headers = headers,
        uri = uriResolver.resolveElementUri(op.dstUri),
        entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, content))
    }

    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._
      val requestDataMapper =
        builder.add(Flow[SyncOperation].mapAsync(1)(createRequestData))
      // need executor stages for the maximum number of requests per action
      val reqExec1 = createOptionalRequestExecutionFlow(0)
      val reqExec2 = createOptionalRequestExecutionFlow(1)
      val reqExec3 = createOptionalRequestExecutionFlow(2)
      val resultOpMapper = builder.add(Flow[SyncOpRequestData].map(_.op))

      requestDataMapper ~> reqExec1 ~> reqExec2 ~> reqExec3 ~> resultOpMapper
      FlowShape(requestDataMapper.in, resultOpMapper.out)
    })
  }

  /**
    * Logs a request before it is sent to the WebDav server.
    *
    * @param elem the stream element to be logged
    * @param log  the logging adapter
    */
  private def logRequest(elem: (HttpRequest, SyncOpRequestData), log: LoggingAdapter): Unit = {
    val request = elem._1
    log.info("{} {}", request.method.value, request.uri)
  }

  /**
    * Logs the response of a request. This function only logs failed responses.
    *
    * @param elem the stream element to be logged
    * @param log  the logging adapter
    */
  private def logResponse(elem: (Try[HttpResponse], SyncOpRequestData),
                          log: LoggingAdapter): Unit = {
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
