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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * A helper class to manage a flow for sending requests to the WebDav server.
  *
  * This is based on the usage example of the Akka HTTP host-level client API
  * from the official Akka documentation.
  *
  * @param uri       the URI requests are to be sent to
  * @param queueSize the size of the request queue
  * @param system    the actor system
  * @param mat       the object to materialize streams
  */
class RequestQueue(uri: Uri, queueSize: Int = 2)(implicit system: ActorSystem, mat: ActorMaterializer) {
  /** The flow for generating HTTP requests. */
  val poolClientFlow: Flow[(HttpRequest, Promise[HttpResponse]),
    (Try[HttpResponse], Promise[HttpResponse]), Http.HostConnectionPool] =
    createPoolClientFlow[Promise[HttpResponse]](uri, Http())

  import system.dispatcher

  /** The queue acting as source for the stream of requests and a kill switch. */
  val queue: SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])] =
    Source.queue[(HttpRequest, Promise[HttpResponse])](queueSize, OverflowStrategy.dropNew)
      .via(poolClientFlow)
      .toMat(Sink.foreach({
        case (Success(resp), p) => p.success(resp)
        case (Failure(e), p) => p.failure(e)
      }))(Keep.left)
      .run()

  /**
    * Puts a request into the queue and returns a ''Future'' with the response
    * returned from the server.
    *
    * @param request the request
    * @return a ''Future'' with the response
    */
  def queueRequest(request: HttpRequest): Future[HttpResponse] = {
    val responsePromise = Promise[HttpResponse]()
    queue.offer(request -> responsePromise).flatMap {
      case QueueOfferResult.Enqueued =>
        responsePromise.future
      case QueueOfferResult.Dropped =>
        Future.failed(new RuntimeException("Queue overflowed."))
      case QueueOfferResult.Failure(ex) =>
        Future.failed(ex)
      case QueueOfferResult.QueueClosed =>
        Future.failed(new RuntimeException("Queue was closed."))
    }
  }

  /**
    * Shuts down this queue by terminating the request stream. Afterwards no
    * requests can be sent any more.
    */
  def shutdown(): Unit = {
    queue.complete()
  }
}
