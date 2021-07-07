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

import com.github.cloudfiles.core.http.Secret

import java.nio.file.Path

/**
  * A trait representing a configuration for an authentication mechanism.
  *
  * This is just a marker interface. An object implementing this trait is part
  * of an HTTP configuration.
  */
sealed trait SyncAuthConfig

/**
  * A data class that collects user credentials for accessing a WebDav server
  * via basic auth.
  *
  * @param user     the user name
  * @param password the password
  */
case class SyncBasicAuthConfig(user: String, password: Secret) extends SyncAuthConfig

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
case class SyncOAuthStorageConfig(rootDir: Path,
                                  baseName: String,
                                  optPassword: Option[Secret]) extends SyncAuthConfig {
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
case object SyncNoAuth extends SyncAuthConfig

