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

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.Location
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import com.github.sync.SourceFileProvider
import com.github.sync.http.{HttpExtensionActor, HttpRequestActor}

import scala.concurrent.{ExecutionContext, Future}

/**
  * An implementation of ''SourceFileProvider'' for OneDrive sources.
  *
  * Downloading a file from OneDrive is a 2-step process: First a request has
  * to be sent to obtain the download URI. This request is answered with a
  * redirect that has the actual download URI as its ''Location'' header. By
  * following this redirect, the content of the file can be downloaded.
  *
  * Note that the HTTP actor used by this class must support connections to
  * multiple hosts because the download link typically points to a different
  * host than the OneDrive API.
  *
  * @param config    the OneDrive configuration
  * @param httpActor the actor for sending HTTP requests
  * @param ec        the execution context
  * @param system    the actor system
  * @param mat       the object to materialize streams
  */
class OneDriveSourceFileProvider(config: OneDriveConfig, httpActor: ActorRef)
                                (implicit ec: ExecutionContext, system: ActorSystem, mat: ActorMaterializer)
  extends SourceFileProvider {
  /** The timeout for HTTP requests. */
  private implicit val timeout: Timeout = config.timeout

  override def fileSource(uri: String): Future[Source[ByteString, Any]] =
    for {
      contentResult <- HttpRequestActor.discardEntityBytes(HttpRequestActor.sendRequest(httpActor,
        createContentRequest(uri)))
      downloadResponse <- sendDownloadRequest(contentResult)
    } yield downloadResponse.response.entity.dataBytes

  /**
    * @inheritdoc This implementation frees the resources used for HTTP
    *             connections.
    */
  override def shutdown(): Unit = {
    httpActor ! HttpExtensionActor.Release
    super.shutdown()
  }

  /**
    * Generates the request to query the content URI of a file to be
    * downloaded.
    *
    * @param uri the URI of the file in question
    * @return the request
    */
  private def createContentRequest(uri: String): HttpRequestActor.SendRequest = {
    val httpUri = config.resolveItemsUri(uri + ":/content")
    val request = HttpRequest(uri = httpUri)
    HttpRequestActor.SendRequest(request, null)
  }

  /**
    * Sends the actual download request for a file. The download URI is
    * extracted from the ''Location'' header of the passed in result. Note
    * that the download URI is likely to refer to a different server;
    * therefore, the request actor cannot be used
    *
    * @param contentResult the result of the content request
    * @return a ''Future'' with the response of the download request
    */
  private def sendDownloadRequest(contentResult: HttpRequestActor.Result): Future[HttpRequestActor.Result] = {
    val location = contentResult.response.header[Location]
    val request = HttpRequest(uri = location.get.uri)
    HttpRequestActor.sendRequest(httpActor, HttpRequestActor.SendRequest(request, null))
  }
}
