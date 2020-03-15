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

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import com.github.sync.http.{BasicAuthConfig, HttpConfig, OAuthStorageConfig}
import com.github.sync.util.UriEncodingHelper

object DavConfig {
  /**
    * Default name of the WebDav property storing the last modified time of an
    * element.
    */
  val DefaultModifiedProperty = "getlastmodified"

  /**
    * Default value for the ''delete-before-override'' property.
    */
  val DefaultDeleteBeforeOverride = "false"

  /**
    * Creates a ''DavConfig'' from the passed in settings.
    *
    * @param rootUri              the root URI of the WebDav structure
    * @param optModifiedProperty  an option for the property containing the
    *                             last-modified timestamp
    * @param optModifiedNamespace namespace to use for the last modified
    *                             property
    * @param deleteBeforeOverride flag whether a delete operation should be
    *                             issued before a file override
    * @param timeout              a timeout for requests to the DAV server
    * @param optBasicAuthConfig   optional config with basic auth settings
    * @param optOAuthConfig       optional config with OAuth settings
    * @return the ''DavConfig'' object
    */
  def apply(rootUri: Uri, optModifiedProperty: Option[String],
            optModifiedNamespace: Option[String], deleteBeforeOverride: Boolean,
            timeout: Timeout, optBasicAuthConfig: Option[BasicAuthConfig] = None,
            optOAuthConfig: Option[OAuthStorageConfig] = None): DavConfig =
    new DavConfig(UriEncodingHelper.removeTrailingSeparator(rootUri.toString()),
      optModifiedProperty getOrElse DefaultModifiedProperty, optModifiedNamespace, deleteBeforeOverride,
      createModifiedProperties(optModifiedProperty), timeout, optBasicAuthConfig, optOAuthConfig)

  /**
    * Generates the list of modified properties. If a custom modified property
    * has been specified, the list consists of this property and the default
    * one. (Because both are checked to obtain the last-modified timestamp of a
    * file.) If there is no custom property, the list contains only the default
    * property.
    *
    * @param optPropModified an option with the custom modified property name
    * @return a list with all modified properties to check
    */
  private def createModifiedProperties(optPropModified: Option[String]): List[String] = {
    val props = List(DefaultModifiedProperty)
    optPropModified match {
      case Some(prop) if prop != DefaultModifiedProperty =>
        prop :: props
      case _ => props
    }
  }
}

/**
  * A data class collecting all configuration settings required to access a
  * WebDav resource in a sync process.
  *
  * Access to the server can be protected by different ways, e.g. using basic
  * auth or a token-based approach. This class therefore defines ''Option''
  * properties for the different mechanisms supported. Only one of these
  * options should be defined.
  *
  * @param rootUri               the root URI to be synced
  * @param lastModifiedProperty  name for the ''lastModified'' property
  * @param lastModifiedNamespace namespace to use for the last modified
  *                              property
  * @param deleteBeforeOverride  flag whether a delete operation should be
  *                              issued before a file override
  * @param modifiedProperties    a list with properties to be checked to fetch
  *                              the last-modified timestamp
  * @param timeout               a timeout for requests to the DAV server
  * @param optBasicAuthConfig    optional config with basic auth settings
  * @param optOAuthConfig        optional config with OAuth settings
  */
case class DavConfig(override val rootUri: Uri,
                     lastModifiedProperty: String,
                     lastModifiedNamespace: Option[String],
                     deleteBeforeOverride: Boolean,
                     modifiedProperties: List[String],
                     timeout: Timeout,
                     override val optBasicAuthConfig: Option[BasicAuthConfig],
                     override val optOAuthConfig: Option[OAuthStorageConfig]) extends HttpConfig
