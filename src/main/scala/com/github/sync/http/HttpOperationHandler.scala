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

package com.github.sync.http

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.http.scaladsl.model.HttpRequest
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Source}
import akka.util.{ByteString, Timeout}
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes._
import com.github.sync.http.SyncOperationRequestActor.{SyncOperationRequestData, SyncOperationResult}
import com.github.sync.impl.CleanupStage

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * A trait implementing functionality to execute ''SyncOperation'' objects
  * against an HTTP server.
  *
  * This trait provides a method for creating a ''Flow'' to process
  * [[SyncOperation]] objects and apply the corresponding changes to an HTTP
  * server. This flow can be directly integrated into a sync stream.
  *
  * The trait implements the basic logic for the creation of the flow and the
  * handling of the different types of sync operations. Concrete
  *
  * @tparam C the type of the configuration used by this trait
  */
trait HttpOperationHandler[C <: HttpConfig] {
  /** The configuration property for the size of the connection pool. */
  private val PropMaxConnections = "akka.http.host-connection-pool.max-connections"

  /**
    * Returns a ''Flow'' to apply [[SyncOperation]] objects against an HTTP
    * server. The flow executes the operations from upstream and passes all
    * that were successful to downstream.
    *
    * @param config       the configuration for the HTTP server
    * @param fileProvider the object providing access to files to upload
    * @param requestActor the actor for sending HTTP requests
    * @param system       the actor system
    * @return the flow to process operations against an HTTP server
    */
  def webDavProcessingFlow(config: C, fileProvider: SourceFileProvider, requestActor: ActorRef)
                          (implicit system: ActorSystem): Flow[SyncOperation, SyncOperation, NotUsed] = {
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
        case SyncOperation(file@FsFile(_, _, _, _, _), ActionRemove, _, _, _) =>
          createRemoveFileRequest(op, file)
        case SyncOperation(folder@FsFolder(_, _, _), ActionRemove, _, _, _) =>
          createRemoveFolderRequest(op, folder)
        case SyncOperation(folder@FsFolder(_, _, _), ActionCreate, _, _, _) =>
          createNewFolderRequest(op, folder)
        case SyncOperation(file@FsFile(_, _, _, _, _), ActionCreate, _, srcUri, _) =>
          createNewFileRequest(op, file, fileProvider.fileSize(file.size),
            fileProvider.fileSource(srcUri))
        case SyncOperation(file@FsFile(_, _, _, _, _), ActionOverride, _, srcUri, _) =>
          createUpdateFileRequest(op, file, fileProvider.fileSize(file.size),
            fileProvider.fileSource(srcUri))
        case _ =>
          Future.failed(new IllegalStateException("Invalid SyncOperation: " + op))
      }
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

  /**
    * Creates an object with request information based on a single request.
    * This is a convenience method that creates a ''Future'' with request
    * information if only a single static request is required.
    *
    * @param op      the sync operation
    * @param request the HTTP request to be executed for this operation
    * @return a ''Future'' with request information
    */
  protected def simpleRequest(op: SyncOperation, request: HttpRequest): Future[SyncOperationRequestData] =
    Future.successful(SyncOperationRequestData(op, Source.single(request)))

  /**
    * Creates an object describing the request to remove a folder.
    *
    * @param op     the sync operation
    * @param folder the folder affected
    * @param ec     the execution context
    * @return a ''Future'' with request information
    */
  protected def createRemoveFolderRequest(op: SyncOperation, folder: FsFolder)
                                         (implicit ec: ExecutionContext): Future[SyncOperationRequestData]

  /**
    * Creates an object describing the request to remove a file.
    *
    * @param op   the sync operation
    * @param file the file affected
    * @param ec   the execution context
    * @return a ''Future'' with request information
    */
  protected def createRemoveFileRequest(op: SyncOperation, file: FsFile)
                                       (implicit ec: ExecutionContext): Future[SyncOperationRequestData]

  /**
    * Creates an object describing the request to create a new folder.
    *
    * @param op     the sync operation
    * @param folder the folder affected
    * @param ec     the execution context
    * @return a ''Future'' with request information
    */
  protected def createNewFolderRequest(op: SyncOperation, folder: FsFolder)
                                      (implicit ec: ExecutionContext): Future[SyncOperationRequestData]

  /**
    * Creates an object describing the request to create a new file.
    *
    * @param op       the sync operation
    * @param fileSize the adjusted file size
    * @param file     the file affected
    * @param source   a ''Source'' with the content of the file
    * @param ec       the execution context
    * @param system   the actor system
    * @return a ''Future'' with request information
    */
  protected def createNewFileRequest(op: SyncOperation, file: FsFile,
                                     fileSize: Long, source: Future[Source[ByteString, Any]])
                                    (implicit ec: ExecutionContext, system: ActorSystem):
  Future[SyncOperationRequestData]

  /**
    * Creates an object describing the request to replace a file on the server.
    *
    * @param op       the sync operation
    * @param fileSize the adjusted file size
    * @param file     the file affected
    * @param source   a ''Source'' with the content of the file
    * @param ec       the execution context
    * @param system   the actor system
    * @return a ''Future'' with request information
    */
  protected def createUpdateFileRequest(op: SyncOperation, file: FsFile,
                                        fileSize: Long, source: Future[Source[ByteString, Any]])
                                       (implicit ec: ExecutionContext, system: ActorSystem):
  Future[SyncOperationRequestData]
}
