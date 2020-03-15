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

package com.github.sync.webdav

import akka.http.scaladsl.model.StatusCodes
import com.github.sync.WireMockSupport.{AuthFunc, BasicAuthFunc}
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalTo, request, stubFor, urlPathEqualTo}

import scala.concurrent.duration.FiniteDuration

/**
  * A helper trait that can be mixed into integration tests of the WebDav
  * functionality to get support for stubbing requests to Dav folders.
  *
  * The trait allows querying the content of a Dav folder via a test server. As
  * response for such a request a file is returned.
  */
trait DavStubbingSupport {
  /**
    * Adds a stubbing declaration for a request to a folder that is served with
    * the file specified.
    *
    * @param uri          the URI of the folder
    * @param responseFile the file to serve the request
    * @param status       the status code to return from the request
    * @param authFunc     the authorization function
    * @param optDelay     an optional delay for this request
    */
  protected def stubFolderRequest(uri: String, responseFile: String,
                                  status: Int = StatusCodes.OK.intValue,
                                  authFunc: AuthFunc = BasicAuthFunc,
                                  optDelay: Option[FiniteDuration] = None): Unit = {
    val reqUri = if (uri.endsWith("/")) uri else uri + "/"
    val delay = optDelay.map(_.toMillis.toInt).getOrElse(0)
    stubFor(authFunc(request("PROPFIND", urlPathEqualTo(reqUri))
      .withHeader("Accept", equalTo("text/xml"))
      .withHeader("Depth", equalTo("1"))
      .willReturn(aResponse()
        .withStatus(status)
        .withFixedDelay(delay)
        .withBodyFile(responseFile))))
  }
}
