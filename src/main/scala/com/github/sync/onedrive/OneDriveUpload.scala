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

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}
import akka.http.scaladsl.model._
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.stream._
import akka.util.{ByteString, Timeout}
import com.github.sync.SyncTypes.SyncOperation
import com.github.sync.http.SyncOperationRequestActor.SyncOperationRequestData
import com.github.sync.onedrive.OneDriveUpload.UploadStreamCoordinatorActor.{NextUploadChunk, UploadChunk}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * A module implementing functionality related to file uploads to a OneDrive
  * server.
  *
  * Uploads to OneDrive a pretty complicated because they require multiple
  * steps. (There is also a direct upload mechanism, but this is supported for
  * small files only; as the files to be processed can have an arbitrary size,
  * the complex mechanism is used here.)
  *
  * In a first step a so-called ''upload session'' has to be created. This is
  * done by sending a special HTTP request to the server. The response is a
  * JSON document that - among other information - contains an upload URL. With
  * the upload URL available, the file's content can be sent to this URL in
  * one or multiple requests. There is a size restriction for a single request
  * (of ~60 MB, but the chunk size can be configured in the OneDrive config);
  * so it may be necessary to split the file into multiple requests.
  *
  * This module provides a function that creates a source of HTTP requests to
  * upload the single chunks of a file. The source is populated from a stream
  * with the content of the file to be uploaded. This is pretty tricky because
  * multiple streams have to be coordinated. The stream with the file content
  * is mapped by a custom flow stage to a stream of HTTP requests. The entity
  * of each request is defined by a source that is fed by the main stream.
  * Unfortunately, there is no default operator for this use case available;
  * therefore, a custom flow stage and a custom source have been implemented
  * that use a special actor for their coordination.
  */
object OneDriveUpload {

  /**
    * A source implementation that provides the data for a single request to
    * upload a chunk of a file.
    *
    * The stream with the file's content is split into multiple requests with
    * a configurable chunk size. For each request, an instance of this class
    * is created. The instance uses the stream coordinator provided to obtain
    * blocks of data.
    *
    * @param config            the OneDrive configuration
    * @param streamCoordinator the stream coordinator actor
    * @param ec                the execution context
    */
  class UploadRequestSource(config: OneDriveConfig, streamCoordinator: ActorRef)
                           (implicit ec: ExecutionContext)
    extends GraphStage[SourceShape[ByteString]] {
    val out: Outlet[ByteString] = Outlet("UploadRequestSource")

    /** Timeout for communication with the coordinator actor. */
    private implicit val timeout: Timeout = config.timeout

    override def shape: SourceShape[ByteString] = SourceShape(out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            val callback = getAsyncCallback[Try[UploadChunk]](chunkAvailable)
            requestUploadChunk() onComplete callback.invoke
          }
        })

        /**
          * Asks the stream coordinator actor for the next block of data.
          *
          * @return a future with the response from the actor
          */
        private def requestUploadChunk(): Future[UploadChunk] =
          (streamCoordinator ? NextUploadChunk).mapTo[UploadChunk]

        /**
          * Handles a response from the stream coordinator actor. If new data
          * is available, it is passed downstream. If the current chunk is
          * complete (indicated by an empty block of data), the source is
          * completed. Failures from upstream are also handled.
          *
          * @param triedChunk a ''Try'' with the next block of data
          */
        private def chunkAvailable(triedChunk: Try[UploadChunk]): Unit = {
          triedChunk match {
            case Success(chunk) =>
              if (chunk.data.nonEmpty) {
                push(out, chunk.data)
              } else {
                completeStage()
              }

            case Failure(exception) =>
              failStage(exception)
          }
        }
      }
  }

  /**
    * A custom flow stage implementation to split the stream with the content
    * of a file into multiple upload requests.
    *
    * The class receives blocks of data from upstream and passes them to the
    * stream coordinator actor. From there they can be queried by the source
    * that produces the content of upload requests.
    *
    * Each pull signal from downstream generates another HTTP request to upload
    * a chunk of the file affected. The requests have a special header to
    * indicate which part of the file is uploaded. The stream coordinator actor
    * keeps track when a chunk is complete, so that the next upload request can
    * be started.
    *
    * @param config    the OneDrive configuration
    * @param uploadUri the URI where to upload the data
    * @param fileSize  the size of the file to be uploaded
    * @param ec        the execution context
    * @param system    the actor system
    */
  class UploadBytesToRequestFlow(config: OneDriveConfig, uploadUri: Uri, fileSize: Long)
                                (implicit ec: ExecutionContext, system: ActorSystem)
    extends GraphStage[FlowShape[ByteString, HttpRequest]] {
    val in: Inlet[ByteString] = Inlet("UploadBytesToRequestFlow.in")
    val out: Outlet[HttpRequest] = Outlet("UploadBytesToRequestFlow.out")

    override def shape: FlowShape[ByteString, HttpRequest] = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with StageLogging {
        /**
          * The actor to coordinate between this flow stage and the sources
          * for the upload requests.
          */
        private var streamCoordinator: ActorRef = _

        /**
          * Keeps track of the number of bytes that have already been uploaded.
          * This is used to find out when stream processing is complete.
          */
        private var bytesUploaded = 0L

        /** Records that an upload finished signal has been received. */
        private var finished = false

        override def preStart(): Unit = {
          super.preStart()
          val callback = getAsyncCallback[Unit] { _ =>
            pollFromCoordinator()
          }
          streamCoordinator = createCoordinatorActor(callback)
        }

        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            streamCoordinator ! UploadChunk(grab(in))
          }

          override def onUpstreamFinish(): Unit = {
            log.debug("onUpstreamFinish()")
            finished = true
          }

          override def onUpstreamFailure(ex: Throwable): Unit = {
            super.onUpstreamFailure(ex)
            stopStreamCoordinator()
          }
        })

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            if (bytesUploaded >= fileSize) {
              complete(out)
              stopStreamCoordinator()
            } else {
              push(out, createNextRequest())
            }
          }
        })

        /**
          * Creates the request to upload a chunk of bytes for the current file.
          *
          * @return the upload request
          */
        private def createNextRequest(): HttpRequest = {
          val chunkEnd = math.min(bytesUploaded + config.uploadChunkSize, fileSize) - 1
          log.info("Uploading chunk {}-{}/{} to {}.", bytesUploaded, chunkEnd, fileSize, uploadUri)
          val dataSource = new UploadRequestSource(config, streamCoordinator)
          val request = HttpRequest(method = HttpMethods.PUT, uri = uploadUri,
            headers = List(ContentRangeHeader.fromChunk(bytesUploaded, chunkEnd, fileSize)),
            entity = HttpEntity(ContentTypes.`application/octet-stream`, chunkEnd - bytesUploaded + 1,
              Source.fromGraph(dataSource)))
          bytesUploaded += config.uploadChunkSize
          request
        }

        /**
          * Handler function for the callback invoked by the coordinator actor.
          * This function is called when the actor requests new data. If
          * upstream is already finished, this graph stage can now be
          * completed; otherwise, data is pulled from the in channel.
          */
        private def pollFromCoordinator(): Unit = {
          if (finished) {
            log.debug("Upload to {} completed; sent {} bytes.", uploadUri, bytesUploaded)
            complete(out)
          } else pull(in)
        }

        /**
          * Stops the stream coordinator actor by sending it an empty chunk
          * message. This is the signal for the actor to stop itself after all
          * pending messages have been delivered.
          */
        private def stopStreamCoordinator(): Unit = {
          streamCoordinator ! UploadStreamCoordinatorActor.EmptyChunk
        }
      }

    /**
      * Creates the actor that does the stream coordination.
      *
      * @param callback the callback for the actor
      * @return the stream coordinator actor
      */
    private[onedrive] def createCoordinatorActor(callback: AsyncCallback[Unit]): ActorRef =
      system.actorOf(UploadStreamCoordinatorActor(config.uploadChunkSize, fileSize, callback))
  }

  object UploadStreamCoordinatorActor {

    /**
      * A message processed by [[UploadStreamCoordinatorActor]] that requests
      * the next block of data. The message is sent by [[UploadRequestSource]].
      */
    case object NextUploadChunk

    /**
      * A message that contains a block of data.
      *
      * The message is both received and sent by
      * [[UploadStreamCoordinatorActor]]. [[UploadBytesToRequestFlow]] sends
      * this message to pass data from the uploaded file to this actor. It is
      * then also sent as response of a [[NextUploadChunk]] message to
      * [[UploadRequestSource]]. Note that messages with an empty block of data
      * have a special meaning indicating the end of a chunk or the whole
      * stream.
      *
      * @param data the block of data
      */
    case class UploadChunk(data: ByteString)

    /**
      * Constant for an empty chunk of data. This is used to indicate the end of
      * the stream for a single upload request.
      */
    val EmptyChunk = UploadChunk(ByteString.empty)

    /**
      * Returns a ''Props'' object for creating a new instance of this actor
      * class.
      *
      * @param chunkSize the upload chunk size
      * @param fileSize  the size of the file to be uploaded
      * @param callback  a callback to request more data from
      *                  [[UploadBytesToRequestFlow]]
      * @return ''Props'' for creating a new instance
      */
    def apply(chunkSize: Int, fileSize: Long, callback: AsyncCallback[Unit]): Props =
      Props(classOf[UploadStreamCoordinatorActor], chunkSize, fileSize, callback)
  }

  /**
    * An actor implementation that coordinates the interactions between the
    * custom flow stage ([[UploadBytesToRequestFlow]]) and the custom source
    * ([[UploadRequestSource]]) implementations.
    *
    * This actor class passes data from the stream with the content of the
    * file to be uploaded to the sources representing the entities of upload
    * requests. It also makes sure that chunks of the correct size are
    * uploaded.
    *
    * The basic idea behind this actor is that data flow is controlled from
    * downstream to upstream. A source for the entity of an upload request
    * sends a [[NextUploadChunk]] message to this actor to query the next block
    * of data. This request is forwarded to the custom flow stage via an
    * asynchronous callback. As a reaction to this callback, the flow pulls its
    * upstream source and receives a block of data, which it passes to this
    * actor. The actor can then pass this block to the entity source.
    *
    * @param chunkSize    the size of an upload chunk (determines the request
    *                     size)
    * @param fileSize     the total file size
    * @param pollCallback the callback to poll the flow stage
    */
  class UploadStreamCoordinatorActor(chunkSize: Int, fileSize: Long,
                                     pollCallback: AsyncCallback[Unit]) extends Actor {
    /** A list with data that can be queried from downstream. */
    private var pendingData = List.empty[UploadChunk]

    /** Stores the client of the latest request for data. */
    private var client: ActorRef = _

    /** The total number of bytes for the file to be uploaded. */
    private var bytesUploaded = 0L

    /**
      * Stores the number of bytes that have been processed in the current chunk.
      */
    private var bytesInCurrentChunk = 0

    /** A flag to indicate that all bytes to be uploaded have been received. */
    private var finished = false

    import UploadStreamCoordinatorActor._

    override def receive: Receive = {
      case NextUploadChunk =>
        pendingData match {
          case h :: t =>
            sender() ! h
            pendingData = t
            if (finished) stopIfDone()
          case _ =>
            pollCallback.invoke(())
            client = sender()
        }

      case UploadChunk(data) if data.isEmpty =>
        // empty chunk is the indicator of a finished upload
        finished = true
        stopIfDone()

      case c@UploadChunk(data) =>
        bytesUploaded += data.length
        if (bytesInCurrentChunk + data.length > chunkSize) {
          val (last, next) = data.splitAt(chunkSize - bytesInCurrentChunk)
          client ! UploadChunk(last)
          pendingData = List(EmptyChunk, UploadChunk(next))
          bytesInCurrentChunk = next.length
        } else {
          client ! c
          bytesInCurrentChunk += data.length
          if (bytesUploaded >= fileSize || bytesInCurrentChunk == chunkSize) {
            pendingData = List(EmptyChunk)
            bytesInCurrentChunk = 0
          }
        }
    }

    /**
      * Stops this actor if all pending messages have been processed.
      */
    private def stopIfDone(): Unit = {
      if (pendingData.isEmpty) self ! PoisonPill
    }
  }

  /**
    * The class representing the ''Content-Range'' custom header.
    *
    * This header is required for upload requests to a OneDrive server. Large
    * files can be uploaded in multiple chunks, and the header describes the
    * current chunk.
    *
    * @param value the value of this header
    */
  class ContentRangeHeader(override val value: String) extends ModeledCustomHeader[ContentRangeHeader] {
    override def companion: ModeledCustomHeaderCompanion[ContentRangeHeader] = ContentRangeHeader

    override def renderInRequests(): Boolean = true

    override def renderInResponses(): Boolean = true
  }

  object ContentRangeHeader extends ModeledCustomHeaderCompanion[ContentRangeHeader] {
    override def name: String = "Content-Range"

    override def parse(value: String): Try[ContentRangeHeader] =
      Try(new ContentRangeHeader(value))

    /**
      * Returns a ''Content-Range'' header from the parameters of the current
      * upload chunk.
      *
      * @param from  the start byte of the current chunk
      * @param to    the end byte of the current chunk
      * @param total the total file size
      * @return the header with these parameters
      */
    def fromChunk(from: Long, to: Long, total: Long): ContentRangeHeader =
      apply(s"bytes $from-$to/$total")
  }

  def upload(config: OneDriveConfig, fileSize: Long, fileSource: Source[ByteString, Any], uploadUri: Uri,
             op: SyncOperation)(implicit ec: ExecutionContext, system: ActorSystem):
  SyncOperationRequestData = SyncOperationRequestData(op,
    fileSource.via(new UploadBytesToRequestFlow(config, uploadUri, fileSize)))
}
