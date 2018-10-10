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

import java.io.IOException

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.sync.{FsFile, SourceFileProvider}

import scala.concurrent.{ExecutionContext, Future}

object DavSourceFileProvider {
  /**
    * Creates a new instance of ''DavSourceFileProvider'' with the
    * configuration specified.
    *
    * @param config the configuration for the WebDav server
    * @param system the actor system
    * @param mat    the object to materialize streams
    * @return the new ''DavSourceFileProvider''
    */
  def apply(config: DavConfig)(implicit system: ActorSystem, mat: ActorMaterializer):
  DavSourceFileProvider = new DavSourceFileProvider(config, new RequestQueue(config.rootUri))

  /**
    * Checks the status of response and causes the future to fail if it was not
    * successful. In this case, an IOException is thrown, and the passed in
    * error mapper function is called to generate the error message.
    *
    * @param futResponse the future with the ''HttpResponse''
    * @param errMapper   the error mapping function
    * @param ec          the execution context
    * @return a ''Future'' with the same response if it is successful; a failed
    *         ''Future'' otherwise
    */
  private def checkResponseStatus(futResponse: Future[HttpResponse])
                                 (errMapper: => HttpResponse => String)
                                 (implicit ec: ExecutionContext): Future[HttpResponse] =
    futResponse.map { resp =>
      if (resp.status.isSuccess()) resp
      else throw new IOException(errMapper(resp))
    }
}

/**
  * An implementation of ''SourceFileProvider'' for WebDav sources.
  *
  * This class tries to resolve files by sending corresponding requests to a
  * WebDav server. A source for the file content is then obtained from the
  * entity of a successful request.
  *
  * @param config       the ''DavConfig'' for the WebDav server
  * @param requestQueue the queue for sending requests
  */
class DavSourceFileProvider private[webdav](config: DavConfig, requestQueue: RequestQueue)
                                           (implicit system: ActorSystem)
  extends SourceFileProvider {
  /** The object to resolve element URIs. */
  private val uriResolver = ElementUriResolver(config.rootUri)

  /** The authorization header to be used for all requests. */
  private val HeaderAuth = Authorization(BasicHttpCredentials(config.user, config.password))

  import system.dispatcher
  import DavSourceFileProvider._

  /**
    * @inheritdoc This implementation requests the file from the configured
    *             WebDav server. The future fails if the request was not
    *             successful.
    */
  override def fileSource(file: FsFile): Future[Source[ByteString, Any]] = {
    val request = createFileRequest(file)
    checkResponseStatus(requestQueue.queueRequest(request)) { response =>
      s"Failed request for '${request.uri}': ${response.status.intValue()} " +
        s"${response.status.defaultMessage()}."
    } map (_.entity.dataBytes)
  }

  /**
    * @inheritdoc This implementation frees the resources used for HTTP
    *             connections.
    */
  override def shutdown(): Unit = {
    requestQueue.shutdown()
  }

  /**
    * Generates the ''HttpRequest'' to obtain the specified file from the
    * configured WebDav server.
    *
    * @param file the file to be retrieved
    * @return the corresponding HTTP request
    */
  private def createFileRequest(file: FsFile): HttpRequest =
    HttpRequest(uri = uriResolver resolveElementUri file.relativeUri,
      headers = List(HeaderAuth))
}
