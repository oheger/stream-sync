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

package com.github.sync.onedrive

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import com.github.sync.http.{AuthConfig, HttpConfig}
import com.github.sync.util.UriEncodingHelper

object OneDriveConfig {
  /** The default root URI for the OneDrive API. */
  val OneDriveServerUri = "https://graph.microsoft.com/v1.0/me/drives"

  /** The URI prefix representing the root path of a drive structure. */
  val PrefixRoot = "/root:"

  /** The URI prefix for accessing the items resource with a path. */
  val ItemsPrefix: String = "/items" + PrefixRoot

  /** The URI suffix to access the children of a folder. */
  val PathChildren = ":/children"

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
    * @param driveID           the ID defining the drive to be accessed
    * @param syncPath          the relative path in the drive to be synced
    * @param uploadChunkSizeMB the chunk size for uploads of large files (MB)
    * @param timeout           a timeout for server requests
    * @param authConfig        the config of the auth mechanism
    * @param optServerUri      optional (alternative) server URI
    * @return the newly created ''OneDriveConfig'' object
    */
  def apply(driveID: String, syncPath: String, uploadChunkSizeMB: Int, timeout: Timeout,
            authConfig: AuthConfig, optServerUri: Option[String] = None): OneDriveConfig = {
    val driveRoot = UriEncodingHelper.withTrailingSeparator(optServerUri.getOrElse(OneDriveServerUri)) + driveID
    val normalizedSyncPath = UriEncodingHelper withLeadingSeparator syncPath
    val rootUri = driveRoot + PrefixRoot + normalizedSyncPath
    new OneDriveConfig(rootUri, timeout, authConfig, driveRoot, normalizedSyncPath,
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
  * @param rootUri         the root URI to be synced
  * @param timeout         a timeout for requests to the server
  * @param authConfig      the config for the auth mechanism
  * @param driveRootUri    the root URI of the drive to be synced
  * @param syncPath        the relative path of the folder to be synced
  * @param uploadChunkSize the chunk size for file upload operations (Bytes)
  */
case class OneDriveConfig(override val rootUri: Uri,
                          override val timeout: Timeout,
                          override val authConfig: AuthConfig,
                          driveRootUri: Uri,
                          syncPath: String,
                          uploadChunkSize: Int) extends HttpConfig {
  /**
    * @inheritdoc This implementation always returns absolute URIs. This is
    *             required by the multi-host request actor.
    */
  override def resolveRelativeUri(uri: String, prefix: String, withTrailingSlash: Boolean): Uri = {
    val relUri = super.resolveRelativeUri(uri, prefix, withTrailingSlash)
    relUri.resolvedAgainst(rootUri)
  }

  /**
    * Resolves a relative URI as a sub path of the ''Items'' resource.
    *
    * @param uri the relative URI
    * @return the resolved URI
    */
  def resolveItemsUri(uri: String): Uri =
    resolveRelativeUri(uri, prefix = driveRootUri.path.toString() + OneDriveConfig.ItemsPrefix + syncPath)

  /**
    * Resolves a relative URI of a folder and appends the suffix to access the
    * children of this folder.
    *
    * @param uri the relative folder URI
    * @return the resolved URI pointing to this folder's children
    */
  def resolveFolderChildrenUri(uri: String): Uri =
    UriEncodingHelper.removeTrailingSeparator(resolveRelativeUri(uri).toString()) + OneDriveConfig.PathChildren
}
