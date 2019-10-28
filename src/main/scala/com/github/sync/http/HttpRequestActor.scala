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

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, Status}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.ActorMaterializer
import com.github.sync.http.HttpRequestActor.{FailedResponseException, RequestException, Result, SendRequest}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object HttpRequestActor {

  /**
    * A message class processed by [[HttpRequestActor]] that describes a
    * request to be sent. The request is associated with a data object that is
    * also part of the response.
    *
    * @param request the request to be sent
    * @param data    a data object
    */
  case class SendRequest(request: HttpRequest, data: Any)

  /**
    * A message class that represents the result of an HTTP request. The
    * object contains the response received from the server and a reference to
    * the original request.
    *
    * @param request  the original request
    * @param response the response
    */
  case class Result(request: SendRequest, response: HttpResponse)

  /**
    * An exception class used to report a failed HTTP request.
    *
    * @param msg     an error message
    * @param cause   the cause of the exception
    * @param request the original request
    */
  case class RequestException(msg: String, cause: Throwable, request: SendRequest) extends Exception(msg, cause)

  /**
    * An exception class indicating a response with a non-success status code.
    * The exception contains the original response, so it can be evaluated.
    *
    * @param response the failed response
    */
  case class FailedResponseException(response: HttpResponse) extends Exception

  /** The default size of the request queue. */
  val DefaultQueueSize = 16

  /**
    * Returns a ''Props'' object for creating a new actor instance that allows
    * sending HTTP requests to the given base URI.
    *
    * @param uri       the base URI
    * @param queueSize the size of the request queue
    * @return the ''Props'' object
    */
  def apply(uri: Uri, queueSize: Int = DefaultQueueSize): Props =
    Props(classOf[HttpRequestActor], uri, queueSize)
}

/**
  * An actor class for sending HTTP requests.
  *
  * This actor class wraps a [[RequestQueue]] that allows sending requests via
  * an HTTP flow. It processes messages to send HTTP requests by propagating
  * these requests to the queue. When the result arrives it is sent back to the
  * caller.
  *
  * HTTP responses are checked whether they have a success status. If this is
  * not the case, a failed future is sent to the caller, and the response
  * entity is discarded.
  *
  * @param uri       the base URI for sending requests to
  * @param queueSize the size of the request queue
  */
class HttpRequestActor(uri: Uri, queueSize: Int) extends Actor with ActorLogging {
  /** The object to materialize streams. */
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  /** The execution context for operations with futures. */
  private implicit val ec: ExecutionContext = context.system.dispatcher

  /** The queue for sending requests. */
  private var queue: RequestQueue = _

  override def preStart(): Unit = {
    super.preStart()
    queue = createRequestQueue()
  }

  /**
    * @inheritdoc This implementation makes sure that the request queue is
    *             properly shutdown.
    */
  override def postStop(): Unit = {
    queue.shutdown()
    super.postStop()
  }

  override def receive: Receive = {
    case req: SendRequest =>
      val caller = sender()
      queue.queueRequest(req.request) flatMap checkResponseStatus(req) onComplete { triedResponse =>
        val result = triedResponse match {
          case Success(response) =>
            log.debug("{} {} - {} {}", req.request.method.value, req.request.uri,
              response.status.intValue(), response.status.defaultMessage())
            Result(req, response)
          case Failure(exception) =>
            log.error(exception, s"${req.request.method.value} ${req.request.uri} failed!")
            Status.Failure(wrapException(req, exception))
        }
        caller ! result
      }
      log.info("{} {}", req.request.method.value, req.request.uri)
  }

  /**
    * Creates the queue for sending requests. This method is called wen this
    * actor is initialized.
    *
    * @return the request queue
    */
  private[http] def createRequestQueue(): RequestQueue = {
    implicit val actorSystem: ActorSystem = context.system
    new RequestQueue(uri, queueSize)
  }

  /**
    * Checks the status code of an HTTP response and handles failure
    * responses. If the response is successful, it is returned as is.
    * Otherwise, a failed future is returned, and the entity bytes of the
    * response are discarded.
    *
    * @param req      the original request
    * @param response the response from the server
    * @return a future with the checked response
    */
  private def checkResponseStatus(req: SendRequest)(response: HttpResponse): Future[HttpResponse] =
    if (response.status.isSuccess())
      Future.successful(response)
    else response.entity.discardBytes().future()
      .map(_ => throw RequestException("Failure response", FailedResponseException(response), req))

  /**
    * Wraps an exception into a ''RequestException'' if necessary. If the
    * passed in exception is already a ''RequestException'', it is returned
    * directly.
    *
    * @param req       the original request
    * @param exception the exception
    * @return the wrapped exception
    */
  private def wrapException(req: SendRequest, exception: Throwable): RequestException =
    exception match {
      case rex: RequestException => rex
      case e => RequestException("Failed request", e, req)
    }
}
