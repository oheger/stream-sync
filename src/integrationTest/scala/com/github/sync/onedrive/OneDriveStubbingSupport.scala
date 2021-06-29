/*
 * Copyright 2018-2021 The Developers Team.
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

import akka.http.scaladsl.model.{StatusCodes, Uri}
import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.sync.WireMockSupport
import com.github.sync.http.SyncNoAuth
import com.github.tomakehurst.wiremock.client.WireMock._

import scala.concurrent.duration._

object OneDriveStubbingSupport {
  /** The drive ID used by tests. */
  val DriveID = "test-drive"

  /** The content type reported by OneDrive for JSON documents. */
  val ContentType =
    "application/json;odata.metadata=minimal;odata.streaming=true;IEEE754Compatible=false;charset=utf-8"

  /** The prefix for the root path. */
  val PrefixRoot = "/root:"

  /** The prefix to access the items resource with a path. */
  val PrefixItems: String = "/items" + PrefixRoot
}

/**
  * A helper trait that can be mixed into integration tests of the OneDrive
  * functionality to get support for stubbing requests to folders.
  */
trait OneDriveStubbingSupport {
  this: WireMockSupport =>

  import OneDriveStubbingSupport._
  import WireMockSupport._

  /**
    * Adds a stubbing declaration for a request to resolve the given path.
    *
    * @param config    the OneDerive config
    * @param resPath   the path that is to be resolved
    * @param elementID the ID of the element to be returned
    * @param status    the status code to be returned for the request
    * @param authFunc  the authentication function
    */
  protected def stubResolvePathRequest(config: OneDriveConfig, resPath: String, elementID: String,
                                       status: Int = StatusCodes.OK.intValue,
                                       authFunc: AuthFunc = WireMockSupport.NoAuthFunc): Unit = {
    val resolvedUri = UriEncodingHelper.removeTrailingSeparator(path(config.resolveRelativeUri(resPath))) +
      ":?select=id"
    val response = "{ \"id\": \"" + elementID + "\" }"
    stubFor(authFunc(get(urlEqualTo(resolvedUri)))
      .withHeader("Accept", equalTo("application/json"))
      .willReturn(aResponse().withStatus(status)
        .withHeader("Content-Type", ContentType)
        .withBody(response)))
  }

  /**
    * Adds a stubbing declaration for a request to a OneDrive folder that is
    * served with content defined by the given content function.
    *
    * @param id       the ID of the folder
    * @param status   the status code to be returned for the request
    * @param authFunc the authentication function
    * @param fContent the function defining the content
    * @return the URI to request the folder
    */
  protected def stubOneDriveFolderRequestContent(id: String, status: Int = StatusCodes.OK.intValue,
                                                 authFunc: AuthFunc = WireMockSupport.NoAuthFunc)
                                                (fContent: ResponseFunc): String = {
    val stubUri = itemUri(id, "/children")
    stubFor(authFunc(get(urlEqualTo(path(stubUri))))
      .withHeader("Accept", equalTo("application/json"))
      .willReturn(fContent(aResponse()
        .withStatus(status)
        .withHeader("Content-Type", ContentType))))
    stubUri
  }

  /**
    * Adds a stubbing declaration for a request to a OneDrive folder that is
    * served with the file specified.
    *
    * @param id           the ID of the folder
    * @param responseFile the file to serve the request
    * @param status       the status code to be returned for the request
    * @param authFunc     the authentication function
    * @return the URI to request the folder
    */
  protected def stubOneDriveFolderRequest(id: String, responseFile: String, status: Int = StatusCodes.OK.intValue,
                                          authFunc: AuthFunc = WireMockSupport.NoAuthFunc): String = {
    stubOneDriveFolderRequestContent(id, status, authFunc)(bodyFile(responseFile))
  }

  /**
    * Returns the path of the given URI.
    *
    * @param uri the URI
    * @return the path of this URI as string
    */
  protected def path(uri: Uri): String = uri.path.toString()

  /**
    * Generates the URI of an element based on its ID.
    *
    * @param id     the element ID
    * @param suffix an option suffix to append to the URI
    * @return the relative element URI
    */
  protected def itemUri(id: String, suffix: String = null): String =
    s"/$DriveID/items/$id${Option(suffix).getOrElse("")}"

  /**
    * Maps a relative element URI to the URI expected by the OneDrive server.
    *
    * @param config the OneDrive config
    * @param uri    the relative URI to be mapped
    * @param prefix the prefix to match a resource
    * @return the mapped URI
    */
  protected def mapElementUri(config: OneDriveConfig, uri: String, prefix: String = PrefixRoot): Uri =
    s"${config.driveRootUri}$prefix${config.syncPath}$uri"

  /**
    * Maps a relative folder URI to the URI expected by the OneDrive server.
    *
    * @param config the OneDrive config
    * @param uri    the relative URI to be mapped
    * @return the mapped URI
    */
  protected def mapFolderUri(config: OneDriveConfig, uri: String): String =
    path(mapElementUri(config, uri)) + ":/children"

  /**
    * Creates a test configuration pointing to the mock server that uses the
    * root path specified.
    *
    * @param rootPath the root path of the sync process
    * @return the configuration for the test source
    */
  protected def createOneDriveConfig(rootPath: String): OneDriveConfig =
    OneDriveConfig(DriveID, rootPath, 1, 3.seconds, SyncNoAuth, Some(serverUri("")))
}
