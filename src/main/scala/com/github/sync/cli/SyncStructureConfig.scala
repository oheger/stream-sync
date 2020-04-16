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

package com.github.sync.cli

import java.time.ZoneId

import com.github.sync.cli.ParameterManager.CliProcessor
import com.github.sync.http.AuthConfig

import scala.util.Try

/**
  * A module defining configuration parameters for the structures taking part
  * in a sync process.
  *
  * Depending on the types of structures to be synced, different parameters
  * need to be passed to the command line. This module defines configuration
  * objects for the different structure types and ''CliProcessor'' objects to
  * construct them from the current command line.
  *
  * The configuration objects for the source and the destination structure are
  * part of the overall sync configuration. They are parsed dynamically from
  * the command line; this is done via a conditional ''CliProcessor'' that
  * evaluates the URI defining the structure.
  */
object SyncStructureConfig {

  /**
    * A trait defining the role a structure plays in a sync process.
    *
    * The type determines whether a structure acts as source or destination of a
    * sync process. It is passed to some functions that create certain elements
    * to handle sync actions like sources or processing stages.
    *
    * From the parameters passed to a sync process it must be possible to find
    * out which ones apply to the source and to the destination structure. This
    * is done by defining a unique ''name'' property for the role type.
    * Parameters can then be prefixed with this name to make clear to which
    * role they apply.
    */
  sealed trait RoleType {
    /**
      * Returns a name of this role type.
      *
      * @return the name property
      */
    def name: String

    /**
      * Determines the name of a configuration property with the given name for
      * this ''RoleType''. The full property name is determined by prefixing
      * it with the name of this type. In addition, the parameter prefix is
      * prepended.
      *
      * @param property the property name
      * @return the full property name for this source type
      */
    def configPropertyName(property: String): String = s"${ParameterManager.OptionPrefix}$name$property"
  }

  /**
    * A concrete ''RoleType'' representing the source structure.
    */
  case object SourceRoleType extends RoleType {
    override val name: String = "src-"
  }

  /**
    * A concrete ''RoleType'' representing the destination structure.
    */
  case object DestinationRoleType extends RoleType {
    override val name: String = "dst-"
  }

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
  case class FsStructureConfig(optTimeZone: ZoneId)

  /**
    * Parameter configuration class for the structure type ''WebDav''.
    *
    * @param lastModifiedProperty  the property with the last modified
    *                              timestamp
    * @param lastModifiedNamespace optional namespace for the last modified
    *                              property
    * @param deleteBeforeOverride  the delete before override flag
    * @param authConfig            the authentication configuration
    */
  case class DavStructureConfig(lastModifiedProperty: String,
                                lastModifiedNamespace: Option[String],
                                deleteBeforeOverride: Boolean,
                                authConfig: AuthConfig)

  /**
    * Parameter configuration class for the structure type ''OneDrive''.
    *
    * @param syncPath          the relative path in the drive to be synced
    * @param uploadChunkSizeMB the chunk size for uploads of large files (MB)
    * @param optServerUri      optional (alternative) server URI
    * @param authConfig        the config of the auth mechanism
    */
  case class OneDriveStructureConfig(syncPath: String,
                                     uploadChunkSizeMB: Int,
                                     optServerUri: Option[String],
                                     authConfig: AuthConfig)

  /**
    * Returns a ''CliProcessor'' to extract the configuration of the sync
    * structure for the role specified. This processor checks the type of the
    * structure (based on the sync URI parameter), and then creates a
    * corresponding ''StructureConfig'' object.
    *
    * @param roleType the role type
    * @return the ''CliProcessor'' to extract the config of this role
    */
  def structureConfigProcessor(roleType: RoleType): CliProcessor[Try[StructureConfig]] = ???
}
