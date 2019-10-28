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

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.HttpRequest
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.github.sync.SourceFileProvider
import com.github.sync.http.{HttpExtensionActor, HttpRequestActor}

import scala.concurrent.Future

object DavSourceFileProvider {
  /**
    * Creates a new instance of ''DavSourceFileProvider'' with the
    * configuration specified.
    *
    * @param config    the configuration for the WebDav server
    * @param httpActor the actor for sending HTTP requests
    * @param system    the actor system
    * @return the new ''DavSourceFileProvider''
    */
  def apply(config: DavConfig, httpActor: ActorRef)(implicit system: ActorSystem):
  DavSourceFileProvider = new DavSourceFileProvider(config, httpActor)
}

/**
  * An implementation of ''SourceFileProvider'' for WebDav sources.
  *
  * This class tries to resolve files by sending corresponding requests to a
  * WebDav server. A source for the file content is then obtained from the
  * entity of a successful request.
  *
  * @param config    the ''DavConfig'' for the WebDav server
  * @param httpActor the actor for sending HTTP requests
  * @param system    the actor system
  */
class DavSourceFileProvider(config: DavConfig, httpActor: ActorRef)
                           (implicit system: ActorSystem)
  extends SourceFileProvider {
  /** The object to resolve element URIs. */
  private val uriResolver = ElementUriResolver(config.rootUri)

  /** The timeout for HTTP requests. */
  private implicit val timeout: Timeout = config.timeout

  import system.dispatcher

  /**
    * @inheritdoc This implementation requests the file from the configured
    *             WebDav server. The future fails if the request was not
    *             successful.
    */
  override def fileSource(uri: String): Future[Source[ByteString, Any]] =
    sendAndProcess(httpActor, createFileRequest(uri))(_.response.entity.dataBytes)

  /**
    * @inheritdoc This implementation frees the resources used for HTTP
    *             connections.
    */
  override def shutdown(): Unit = {
    httpActor ! HttpExtensionActor.Release
  }

  /**
    * Generates the ''HttpRequest'' to obtain the specified file from the
    * configured WebDav server.
    *
    * @param uri the URI of the file to be retrieved
    * @return the corresponding HTTP request
    */
  private def createFileRequest(uri: String): HttpRequestActor.SendRequest = {
    val httpRequest = HttpRequest(uri = uriResolver resolveElementUri uri)
    HttpRequestActor.SendRequest(httpRequest, null)
  }
}
