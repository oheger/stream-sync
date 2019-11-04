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

import akka.http.scaladsl.model.StatusCodes
import com.github.sync.WireMockSupport
import com.github.sync.WireMockSupport.AuthFunc
import com.github.tomakehurst.wiremock.client.WireMock._

import scala.concurrent.duration._

object OneDriveStubbingSupport {
  /** The drive ID used by tests. */
  val DriveID = "test-drive"

  /** The content type reported by OneDrive for JSON documents. */
  val ContentType =
    "application/json;odata.metadata=minimal;odata.streaming=true;IEEE754Compatible=false;charset=utf-8"
}

/**
  * A helper trait that can be mixed into integration tests of the OneDrive
  * functionality to get support for stubbing requests to folders.
  */
trait OneDriveStubbingSupport {
  this: WireMockSupport =>

  import OneDriveStubbingSupport._

  /**
    * Adds a stubbing declaration for a request to a OneDrive folder that is
    * served with the file specified.
    *
    * @param config       the OneDrive config
    * @param uri          the URI of the folder
    * @param responseFile the file to serve the request
    * @param status       the status code to be returned for the request
    * @param authFunc     the authentication function
    */
  protected def stubOneDriveFolderRequest(config: OneDriveConfig, uri: String, responseFile: String,
                                          status: Int = StatusCodes.OK.intValue,
                                          authFunc: AuthFunc = WireMockSupport.NoAuthFunc): Unit = {
    stubFor(authFunc(get(urlPathEqualTo(mapFolderUri(config, uri))))
      .withHeader("Accept", equalTo("application/json"))
      .willReturn(aResponse()
        .withStatus(status)
        .withHeader("Content-Type", ContentType)
        .withBodyFile(responseFile)))
  }

  protected def mapElementUri(config: OneDriveConfig, uri: String): String =
    s"/$DriveID/root:$uri:"

  /**
    * Maps a relative folder URI to the URI expected by the OneDrive server.
    *
    * @param config the current OneDrive config
    * @param uri    the relative URI to be mapped
    * @return the mapped URI
    */
  protected def mapFolderUri(config: OneDriveConfig, uri: String): String =
    mapElementUri(config, uri) + "/children"

  /**
    * Creates a test configuration pointing to the mock server that uses the
    * root path specified.
    *
    * @param rootPath the root path of the sync process
    * @return the configuration for the test source
    */
  protected def createOneDriveConfig(rootPath: String): OneDriveConfig =
    OneDriveConfig(DriveID, rootPath, 1, 3.seconds, None, Some(serverUri("")))
}
