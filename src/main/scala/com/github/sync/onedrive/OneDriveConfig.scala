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

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import com.github.sync.http.{BasicAuthConfig, HttpConfig, OAuthStorageConfig}
import com.github.sync.util.UriEncodingHelper

object OneDriveConfig {
  /** The default root URI for the OneDrive API. */
  val OneDriveServerUri = "https://graph.microsoft.com/v1.0/me/drives"

  /**
    * Default value for the upload chunk size (in MB). OneDrive accepts only
    * uploads of a limited size (60 MB currently). Larger files need to be
    * split into multiple chunks. This constant defines the default chunk size
    * used when the user has not explicitly defined one.
    */
  val DefaultUploadChunkSizeMB: Int = 10

  /** Factor for converting MB to bytes. */
  private val FactorMB = 1024 * 1024

  /**
    * Creates a ''OneDriveConfig'' object with the settings provided. This
    * function sets some default values for properties that typically have
    * constant values. For instance, the URI of the OneDrive server is such a
    * constant. Another one is that OneDrive uses OAuth authentication, so the
    * configuration for basic auth is not needed. It is, however, possible to
    * override these values, e.g. to access a different server using the
    * OneDrive protocol or for testing purposes.
    *
    * @param driveID            the ID defining the drive to be accessed
    * @param syncPath           the relative path in the drive to be synced
    * @param uploadChunkSizeMB  the chunk size for uploads of large files (MB)
    * @param timeout            a timeout for server requests
    * @param optOAuthConfig     optional OAuth config settings
    * @param optServerUri       optional (alternative) server URI
    * @param optBasicAuthConfig optional basic auth settings
    * @return the newly created ''OneDriveConfig'' object
    */
  def apply(driveID: String, syncPath: String, uploadChunkSizeMB: Int, timeout: Timeout,
            optOAuthConfig: Option[OAuthStorageConfig], optServerUri: Option[String] = None,
            optBasicAuthConfig: Option[BasicAuthConfig] = None): OneDriveConfig = {
    val driveRoot = UriEncodingHelper.withTrailingSeparator(optServerUri.getOrElse(OneDriveServerUri)) + driveID
    val rootUri = driveRoot + "/root:/" + UriEncodingHelper.removeLeadingSeparator(syncPath)
    new OneDriveConfig(rootUri, timeout, optBasicAuthConfig, optOAuthConfig, driveRoot, syncPath,
      uploadChunkSizeMB * FactorMB)
  }
}

/**
  * A data class storing the configuration settings to access a OneDrive
  * server.
  *
  * This configuration class is used to integrate Microsoft OneDrive with sync
  * processes. Note that the root URI of the OneDrive server is normally
  * constant; however, to better support testing and make the class more
  * flexible, it can be freely configured.
  *
  * @param rootUri            the root URI to be synced
  * @param timeout            a timeout for requests to the server
  * @param optBasicAuthConfig optional config with basic auth settings
  * @param optOAuthConfig     optional config with OAuth config settings
  * @param driveRootUri       the root URI of the drive to be synced
  * @param syncPath           the relative path of the folder to be synced
  * @param uploadChunkSize    the chunk size for file upload operations (Bytes)
  */
case class OneDriveConfig(override val rootUri: Uri,
                          override val timeout: Timeout,
                          override val optBasicAuthConfig: Option[BasicAuthConfig],
                          override val optOAuthConfig: Option[OAuthStorageConfig],
                          driveRootUri: String,
                          syncPath: String,
                          uploadChunkSize: Int) extends HttpConfig
