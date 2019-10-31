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

package com.github.sync.http

import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.model.HttpRequest
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.github.sync.SyncTypes.SyncOperation
import com.github.sync.http.SyncOperationRequestActor.{SyncOperationExecutionData, SyncOperationRequestData}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object SyncOperationRequestActor {

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
  case class SyncOperationRequestData(op: SyncOperation, requests: List[HttpRequest])

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
    * An internal data class that is used to keep track on the execution of
    * the requests of a sync operation.
    *
    * @param request the data object with the requests to be executed
    * @param sender  the sender of the request
    */
  private case class SyncOperationExecutionData(request: SyncOperationRequestData, sender: ActorRef) {
    /**
      * Checks whether the execution is already complete. This is the case if
      * all the single requests associated with the operation have been
      * executed.
      *
      * @return a flag whether execution is complete
      */
    def isDone: Boolean = request.requests.isEmpty

    /**
      * Returns a ''SyncOperationExecutionData'' object that points to the
      * next request to be executed. This function is called after a request
      * has been processed to continue with the next one.
      *
      * @return a data object pointing to the next request
      */
    def nextRequest: SyncOperationExecutionData = {
      val nextRequests = request.requests.tail
      copy(request = request.copy(requests = nextRequests))
    }

    /**
      * Sends a message with the execution result to the original sender.
      *
      * @param optFailure an ''Option'' with an exception
      */
    def sendResult(optFailure: Option[Throwable]): Unit = {
      sender ! SyncOperationResult(request.op, optFailure)
    }
  }

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
  /** The object to materialize streams. */
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  override def receive: Receive = {
    case request: SyncOperationRequestData =>
      self ! SyncOperationExecutionData(request, sender())

    case request: SyncOperationExecutionData if request.isDone =>
      request.sendResult(None)

    case request: SyncOperationExecutionData =>
      executeNextRequest(request)
  }

  override def postStop(): Unit = {
    requestActor ! HttpExtensionActor.Release
    super.postStop()
  }

  /**
    * Executes the next request of the sync operation associated with the given
    * data object.
    *
    * @param request the object representing the execution of the operation
    */
  private def executeNextRequest(request: SyncOperationExecutionData): Unit = {
    implicit val askTimeout: Timeout = timeout
    implicit val ec: ExecutionContext = context.system.dispatcher
    val sendRequest = HttpRequestActor.SendRequest(request.request.requests.head, request)
    val futResponse = requestActor ? sendRequest
    processResult(futResponse) onComplete {
      case Success(_) =>
        self ! request.nextRequest
      case Failure(exception) =>
        request.sendResult(Some(exception))
    }
  }

  /**
    * Processes the result of the ask request to the HTTP request actor. The
    * future is successful only if a success response from the server has been
    * received. In this case, the entity has to be discarded.
    *
    * @param futAsk the future with the result from the request actor
    * @return the processed and correctly typed future
    */
  private def processResult(futAsk: Future[Any])(implicit ec: ExecutionContext): Future[HttpRequestActor.Result] =
    futAsk.mapTo[HttpRequestActor.Result]
      .flatMap(result => result.response.entity.discardBytes().future().map(_ => result))
}
