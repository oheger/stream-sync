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

import java.nio.file.Path

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import com.github.sync.crypt.Secret

object DavConfig {
  /**
    * Property for the user name. This is used to authenticate against the
    * WebDav server.
    */
  val PropUser = "user"

  /**
    * Property for the password of the user. This is used to authenticate
    * against the WebDav server.
    */
  val PropPassword = "password"

  /**
    * Property for the name of the WebDav property defining the last modified
    * time of an element. This is optional; if unspecified, the default WebDav
    * property for the last modified time is used.
    */
  val PropModifiedProperty = "modified-property"

  /**
    * Property for the name of the WebDav property that defines a namespace for
    * the property with the last modified time. If this property is defined, in
    * patch requests to the WebDav server to update the modified time of a file
    * this namespace will be used. Note that this property has an effect only
    * if a custom modified property is set.
    */
  val PropModifiedNamespace = "modified-namespace"

  /**
    * Property to determine whether a file to be overridden should be deleted
    * before it is uploaded. This may be necessary for some servers to have a
    * reliable behavior. The value of the property is a string that is
    * interpreted as a boolean value (in terms of ''Boolean.parseBoolean()'').
    */
  val PropDeleteBeforeOverride = "delete-before-override"

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
    new DavConfig(rootUri, optModifiedProperty getOrElse DefaultModifiedProperty,
      optModifiedNamespace, deleteBeforeOverride, createModifiedProperties(optModifiedProperty), timeout,
      optBasicAuthConfig, optOAuthConfig)

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
case class DavConfig(rootUri: Uri,
                     lastModifiedProperty: String,
                     lastModifiedNamespace: Option[String],
                     deleteBeforeOverride: Boolean,
                     modifiedProperties: List[String],
                     timeout: Timeout,
                     optBasicAuthConfig: Option[BasicAuthConfig],
                     optOAuthConfig: Option[OAuthStorageConfig])

/**
  * A data class that collects user credentials for accessing a WebDav server
  * via basic auth.
  *
  * @param user     the user name
  * @param password the password
  */
case class BasicAuthConfig(user: String, password: Secret)

/**
  * A data class with information required to access the persistent information
  * related to an OAuth identity provider.
  *
  * When loading or storing an OAuth configuration an instance of this class
  * must be provided. It specifies the storage location of the configuration
  * and also determines some other properties like the encryption status.
  *
  * The whole OAuth configuration for a specific identity provider (IDP) is
  * stored in multiple files in a directory. All files belonging to the same
  * configuration share the same base name. Sensitive information like tokens
  * or client secrets can be encrypted.
  *
  * @param rootDir     the root directory where all files are stored
  * @param baseName    the base name of the files of this configuration
  * @param optPassword an option with a password to encrypt sensitive files
  */
case class OAuthStorageConfig(rootDir: Path,
                              baseName: String,
                              optPassword: Option[Secret]) {
  /**
    * Returns a path in the root directory that is derived from the base name
    * with the given suffix. This is useful to locate concrete files related to
    * a specific IDP configuration.
    *
    * @param suffix the suffix to append to the base name
    * @return the path pointing to the file with the given suffix
    */
  def resolveFileName(suffix: String): Path =
    rootDir.resolve(baseName + suffix)
}
