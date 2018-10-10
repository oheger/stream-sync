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

package com.github.sync

import java.io.IOException

import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.scaladsl.Flow

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
    * Generates an ''Authorization'' header based on the given configuration.
    *
    * @param config the configuration of the WebDav server
    * @return a header to authenticate against this WebDav server
    */
  def authHeader(config: DavConfig): Authorization =
    Authorization(BasicHttpCredentials(config.user, config.password))

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
    * A convenience function for sending a request and processing the response
    * in case the status code indicates success. This function executes the
    * request using the given request queue. It then checks the status code of
    * the response, causing the result future to fail if necessary. Otherwise,
    * the given processing function is called to further process the response.
    *
    * @param requestQueue the request queue to execute the request
    * @param request      the request to be executed
    * @param f            the processing function
    * @param ec           the execution context
    * @tparam T the type of the result of the processing function
    * @return a ''Future'' with the processing result
    */
  def sendAndProcess[T](requestQueue: RequestQueue, request: HttpRequest)
                       (f: HttpResponse => T)(implicit ec: ExecutionContext): Future[T] =
    checkResponseStatus(requestQueue.queueRequest(request))(standardErrorMapper(
      request.uri.toString())) map f

  /**
    * Checks the status of a response and causes the future to fail if it was
    * not successful. In this case, an IOException is thrown, and the passed in
    * error mapper function is called to generate the error message.
    *
    * @param futResponse the future with the ''HttpResponse''
    * @param errMapper   the error mapping function
    * @param ec          the execution context
    * @return a ''Future'' with the same response if it is successful; a failed
    *         ''Future'' otherwise
    */
  def checkResponseStatus(futResponse: Future[HttpResponse])
                         (errMapper: => HttpResponse => String)
                         (implicit ec: ExecutionContext): Future[HttpResponse] =
    futResponse.map { resp =>
      if (resp.status.isSuccess()) resp
      else throw new IOException(errMapper(resp))
    }

  /**
    * Returns a standard error mapper function that can be used together with
    * ''checkResponseStatus()''. The function produces an error message that
    * contains the given request URI and information from the status code of
    * the response.
    *
    * @param requestUri the request URI
    * @return a standard error mapper function
    */
  def standardErrorMapper(requestUri: String): HttpResponse => String = response =>
    s"Failed request for '$requestUri': ${response.status.intValue()} " +
      s"${response.status.defaultMessage()}."

  /**
    * Determines the port to be used for an URI based on its scheme.
    *
    * @param uri the URI
    * @return the port to be used for this URI
    */
  private def extractPortFromScheme(uri: Uri): Int =
    if (SchemeHttps == uri.scheme) PortHttps else PortHttp
}
