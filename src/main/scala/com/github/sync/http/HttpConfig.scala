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

package com.github.sync.http

import java.nio.file.Path

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import com.github.sync.crypt.Secret
import com.github.sync.util.UriEncodingHelper

/**
  * A trait representing a configuration for an authentication mechanism.
  *
  * This is just a marker interface. An object implementing this trait is part
  * of an HTTP configuration.
  */
sealed trait AuthConfig

/**
  * A data class that collects user credentials for accessing a WebDav server
  * via basic auth.
  *
  * @param user     the user name
  * @param password the password
  */
case class BasicAuthConfig(user: String, password: Secret) extends AuthConfig

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
                              optPassword: Option[Secret]) extends AuthConfig {
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

/**
  * An object representing no authentication mechanism.
  *
  * This is used for the rare case that communication with a server should be
  * done without any authentication mechanism.
  */
case object NoAuth extends AuthConfig

/**
  * A trait defining configuration options that are common to all HTTP-based
  * protocols.
  *
  * The options defined by this trait are used by the base classes in the
  * ''http'' package. For a specific protocol a configuration class with
  * additional properties can be created the extends this trait.
  */
trait HttpConfig {
  /**
    * Returns the root URI to be used by the sync process for the HTTP server
    * represented by this configuration. Here a host plus the base path can be
    * set.
    *
    * @return the HTTP server's root URI
    */
  def rootUri: Uri

  /**
    * Returns a timeout for all operations against the represented HTTP server.
    *
    * @return a timeout
    */
  def timeout: Timeout

  /**
    * Returns the configuration for the authentication mechanism. Here one of
    * the supported implementations is returned.
    *
    * @return the configuration for the authentication mechanism
    */
  def authConfig: AuthConfig

  /**
    * Returns the root path of the HTTP server. This path is derived from the
    * root URI by extracting the path component.
    *
    * @return the root path
    */
  def rootPath: String = rootUri.path.toString()

  /**
    * Provides basic functionality to resolve relative URIs against the root
    * path of the HTTP server. The function is passed an un-encoded relative
    * URI, which can consist of multiple components. The single components are
    * encoded and appended to the given prefix. Optionally, a trailing slash is
    * added.
    *
    * @param uri               the relative URI to be resolved
    * @param prefix            the prefix to be added
    * @param withTrailingSlash flag whether the result should end on a slash
    * @return the resolved URI
    */
  def resolveRelativeUri(uri: String, prefix: String = rootPath, withTrailingSlash: Boolean = false): Uri = {
    val encodedUri = UriEncodingHelper encodeComponents uri
    val relativeUri = if (withTrailingSlash) encodedUri + UriEncodingHelper.UriSeparator else encodedUri
    Uri(prefix + relativeUri)
  }
}
