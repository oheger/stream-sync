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

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.HttpRequest
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import com.github.sync.SyncTypes.SyncOperation
import com.github.sync.http.SyncOperationRequestActor.{SyncOperationRequestData, SyncOperationResult}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object SyncOperationRequestActor {

  /**
    * A data class storing the requests to be executed for a single sync
    * operation.
    *
    * For some operations multiple requests are to be executed. They are
    * represented as a stream source. This stream is executed yielding a
    * success or failure result.
    *
    * @param op       the sync operation
    * @param requests the source with requests for this operation
    */
  case class SyncOperationRequestData(op: SyncOperation, requests: Source[HttpRequest, Any])

  /**
    * A data class representing the result of the execution of a sync
    * operation.
    *
    * The ''SyncOperation'' affected is part of the data. An ''Option'' can be
    * used to determine whether the execution was successful: in case of an
    * error, the causing ''Throwable'' is contained.
    *
    * @param op         the sync operation
    * @param optFailure an ''Option'' with the exception in case of a failure
    */
  case class SyncOperationResult(op: SyncOperation, optFailure: Option[Throwable])

  /**
    * Returns a ''Props'' object for creating a new actor instance.
    *
    * @param requestActor the actor for executing HTTP requests
    * @param timeout      a timeout for request execution
    * @return the ''Props'' for creating a new actor
    */
  def apply(requestActor: ActorRef, timeout: Timeout): Props =
    Props(classOf[SyncOperationRequestActor], requestActor, timeout)
}

/**
  * An actor class for executing the requests of a single sync operation.
  *
  * This actor is used by the operation handler for an HTTP target. It is sent
  * messages that contain multiple requests that need to be executed to
  * complete a single sync operation. These requests are executed against the
  * request actor passed to the constructor. If all of them are successful, the
  * operation is considered successful. In any case a response is sent back to
  * the caller with the original sync operation and an indicator whether it
  * was successful.
  *
  * As all requests just modify the server without returning meaningful result,
  * the entities are discarded directly.
  *
  * @param requestActor the actor for executing HTTP requests
  * @param timeout      a timeout for request execution
  */
class SyncOperationRequestActor(requestActor: ActorRef, timeout: Timeout) extends Actor {
  /** The actor system in implicit scope. */
  private implicit val system: ActorSystem = context.system

  /** The execution context in implicit scope. */
  private implicit val ec: ExecutionContext = context.system.dispatcher

  /** A timeout for asynchronous operations in implicit scope. */
  private implicit val askTimeout: Timeout = timeout

  override def receive: Receive = {
    case request: SyncOperationRequestData =>
      handleOperationRequest(request, sender())
  }

  override def postStop(): Unit = {
    requestActor ! HttpExtensionActor.Release
    super.postStop()
  }

  /**
    * Handles the execution of all the requests defined in a
    * ''SyncOperationRequestData'' object and sends a corresponding success or
    * failure response back to the caller.
    *
    * @param request the request to be executed
    * @param caller  the caller
    */
  private def handleOperationRequest(request: SyncOperationRequestData, caller: ActorRef): Unit = {
    val sink = Sink.ignore
    request.requests
      .mapAsync(1)(executeNextRequest)
      .runWith(sink) onComplete { result =>
      val optFailure = result match {
        case Success(_) => None
        case Failure(exception) => Some(exception)
      }
      caller ! SyncOperationResult(request.op, optFailure)
    }
  }

  /**
    * Executes the next request of the sync operation associated with the given
    * data object.
    *
    * @param request the object representing the execution of the operation
    */
  private def executeNextRequest(request: HttpRequest): Future[HttpRequestActor.Result] = {
    val sendRequest = HttpRequestActor.SendRequest(request, null)
    processResult(HttpRequestActor.sendRequest(requestActor, sendRequest))
  }

  /**
    * Processes the result of the ask request to the HTTP request actor. The
    * future is successful only if a success response from the server has been
    * received. In this case, the entity has to be discarded.
    *
    * @param futResult the future with the result from the request actor
    * @return the processed and correctly typed future
    */
  private def processResult(futResult: Future[HttpRequestActor.Result]): Future[HttpRequestActor.Result] =
    HttpRequestActor.discardEntityBytes(futResult)
}
