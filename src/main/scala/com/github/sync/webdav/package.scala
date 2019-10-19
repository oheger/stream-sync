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

package com.github.sync

import akka.actor.ActorRef
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.{Http, HttpExt}
import akka.pattern.ask
import akka.stream.scaladsl.Flow
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * Object with some common functionality for the ''webdav'' package.
  */
package object webdav {
  /** The HTTPS scheme. */
  private val SchemeHttps = "https"

  /** Default port for HTTPS requests. */
  private val PortHttps = 443

  /** Default port for plain HTTP requests. */
  private val PortHttp = 80

  /**
    * Extracts the port from the specified URI. If a port is explicitly
    * provided, it is used. Otherwise the default port for the scheme is used.
    *
    * @param uri the URI
    * @return the port of this URI
    */
  def extractPort(uri: Uri): Int = {
    val port = uri.authority.port
    if (port != 0) port
    else extractPortFromScheme(uri)
  }

  /**
    * Creates a flow to execute HTTP requests to the host identified by the
    * given URI. From the URI host and port are extracted. Whether the flow is
    * for sending HTTP or HTTPS requests is determined from the URI's scheme.
    *
    * @param uri the URI
    * @tparam T the type of objects passed to the flow
    * @return the flow for sending HTTP requests to this URI
    */
  def createPoolClientFlow[T](uri: Uri, ext: HttpExt): Flow[(HttpRequest, T),
    (Try[HttpResponse], T), Http.HostConnectionPool] = {
    val host = uri.authority.host.toString()
    val port = extractPort(uri)
    if (SchemeHttps == uri.scheme)
      ext.cachedHostConnectionPoolHttps(host, port)
    else ext.cachedHostConnectionPool(host, port)
  }

  /**
    * Sends a request to an HTTP actor and allows processing of the result.
    * This function passes the given request to the actor and expects the
    * future with the result. The future is then mapped with the specified
    * mapping function to come to the final result. Note that error handling is
    * already done by the actor, including an evaluation of the HTTP response
    * status.
    *
    * @param httpActor the actor to execute the request
    * @param request   the request to be executed
    * @param f         the processing function
    * @param ec        the execution context
    * @param timeout   a timeout
    * @tparam T the type of the result of the processing function
    * @return a ''Future'' with the processing result
    */
  def sendAndProcess[T](httpActor: ActorRef, request: HttpRequestActor.SendRequest)
                       (f: HttpRequestActor.Result => T)
                       (implicit ec: ExecutionContext, timeout: Timeout): Future[T] =
    (httpActor ? request)
      .mapTo[HttpRequestActor.Result]
      .map(f)

  /**
    * Determines the port to be used for an URI based on its scheme.
    *
    * @param uri the URI
    * @return the port to be used for this URI
    */
  private def extractPortFromScheme(uri: Uri): Int =
    if (SchemeHttps == uri.scheme) PortHttps else PortHttp
}
