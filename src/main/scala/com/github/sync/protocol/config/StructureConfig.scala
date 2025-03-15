/*
 * Copyright 2018-2025 The Developers Team.
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

package com.github.sync.protocol.config

import com.github.cloudfiles.gdrive.GoogleDriveConfig

import java.time.ZoneId

/**
  * A trait representing the configuration of a concrete sync role type.
  *
  * This is just a marker trait. There are concrete sub classes defining the
  * command line arguments for all the structure types supported for sync
  * processes. The properties of these sub classes are a sub set of the data
  * expected by the actual configuration classes.
  */
sealed trait StructureConfig

/**
  * Parameter configuration class for the structure type ''local file
  * system''.
  *
  * @param optTimeZone an optional timezone that determines how the timestamps
  *                    of files are to be interpreted
  */
case class FsStructureConfig(optTimeZone: Option[ZoneId]) extends StructureConfig

/**
  * Parameter configuration class for the structure type ''WebDav''.
  *
  * @param optLastModifiedProperty  optional property with the last modified
  *                                 timestamp
  * @param optLastModifiedNamespace optional namespace for the last modified
  *                                 property
  * @param deleteBeforeOverride     the delete before override flag
  */
case class DavStructureConfig(optLastModifiedProperty: Option[String],
                              optLastModifiedNamespace: Option[String],
                              deleteBeforeOverride: Boolean) extends StructureConfig

/**
  * Parameter configuration class for the structure type ''OneDrive''.
  *
  * @param syncPath             the relative path in the drive to be synced
  * @param optUploadChunkSizeMB optional chunk size for uploads of large
  *                             files (in MB)
  * @param optServerUri         optional (alternative) server URI
  */
case class OneDriveStructureConfig(syncPath: String,
                                   optUploadChunkSizeMB: Option[Int],
                                   optServerUri: Option[String]) extends StructureConfig

/**
  * Parameter configuration for the structure type ''GoogleDrive''.
  *
  * For ''GoogleDrive'', only a limited set of configuration options can be
  * set. The relative sync path is part of the structure URI.
  *
  * @param optServerUri optional (alternative) server URI
  */
case class GoogleDriveStructureConfig(optServerUri: Option[String]) extends StructureConfig:
  /**
    * Returns the URI of the GoogleDrive server to access. If the optional
    * server URI is defined, it is returned; otherwise, result is the default
    * GoogleDrive API URI.
    *
    * @return the URI of the GoogleDrive server
    */
  def serverUri: String = optServerUri getOrElse GoogleDriveConfig.GoogleDriveServerUri
