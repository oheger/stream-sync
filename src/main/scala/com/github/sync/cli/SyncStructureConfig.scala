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

import com.github.sync.cli.ParameterManager._
import com.github.sync.cli.oauth.OAuthParameterManager
import com.github.sync.crypt.Secret
import com.github.sync.http.{AuthConfig, BasicAuthConfig, NoAuth}

import scala.util.{Success, Try}

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
  /** URI prefix indicating a WebDav structure. */
  final val PrefixWebDav = "dav:"

  /** URI prefix indicating a OneDrive structure. */
  final val PrefixOneDrive = "onedrive:"

  /**
    * Property for the time zone to be applied to the last-modified timestamps
    * of files encountered on the local FS. This property is optional. If it is
    * not defined, timestamps are obtained directly from the file system
    * without modifications. This is appropriate if the file system stores them
    * in a defined way. If this is not the case (e.g. for a FAT32 file system
    * which stores them in a local time zone), the time zone must be specified
    * explicitly. Otherwise, the comparison of timestamps (which is one
    * criterion to decide whether a file has been changed) is going to fail.
    */
  final val PropLocalFsTimeZone = "time-zone"

  /** Property for the user name if Basic Auth is used. */
  final val PropAuthUser = "user"

  /** Property for the password if Basic Auth is used. */
  final val PropAuthPassword = "password"

  /**
    * Property for the name of the WebDav property defining the last modified
    * time of an element. This is optional; if unspecified, the default WebDav
    * property for the last modified time is used.
    */
  final val PropDavModifiedProperty = "modified-property"

  /**
    * Property for the name of the WebDav property that defines a namespace for
    * the property with the last modified time. If this property is defined, in
    * patch requests to the WebDav server to update the modified time of a file
    * this namespace will be used. Note that this property has an effect only
    * if a custom modified property is set.
    */
  final val PropDavModifiedNamespace = "modified-namespace"

  /**
    * Property to determine whether a file to be overridden should be deleted
    * before it is uploaded. This may be necessary for some servers to have a
    * reliable behavior. The value of the property is a string that is
    * interpreted as a boolean value (in terms of ''Boolean.parseBoolean()'').
    */
  final val PropDavDeleteBeforeOverride = "delete-before-override"

  /**
    * Property for the relative path to be synced on a OneDrive drive.
    */
  final val PropOneDrivePath = "path"

  /**
    * Property for the URI of the OneDrive server. This property is optional;
    * the default server URI is used if not specified.
    */
  final val PropOneDriveServer = "server-uri"

  /**
    * Property for the chunk size (in MB) for file uploads to a OneDrive
    * server. This is an optional property.
    */
  final val PropOneDriveUploadChunkSize = "upload-chunk-size"

  /** Group name for the options for basic auth. */
  final val GroupBasicAuth = "authBasic"

  /** Group name of the options for OAuth. */
  final val GroupOAuth = "authOAuth"

  /** Group name to be used if no authentication is desired. */
  final val GroupNoAuth = "authNone"

  /** Group name for the parameters related to the local file system. */
  final val GroupLocalFs = "localFS"

  /** Group name for the parameters related to WebDav. */
  final val GroupDav = "dav"

  /** Group name for the parameters related to OneDrive. */
  final val GroupOneDrive = "onedrive"

  /** Regular expression for parsing a WebDav URI. */
  final val RegDavUri = (PrefixWebDav + "(.+)").r

  /** Regular expression for parsing a OneDrive drive ID. */
  final val RegOneDriveID = (PrefixOneDrive + "(.+)").r

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
    * role they apply. In addition, there is an index corresponding to the
    * input parameter that needs to be evaluated to determine the role type -
    * the source or the destination URI.
    */
  sealed trait RoleType {
    /**
      * Returns a name of this role type.
      *
      * @return the name property
      */
    def name: String

    /**
      * Returns the index of the input parameter with the URI that corresponds
      * to this role type.
      *
      * @return the index of the associated input parameter
      */
    def parameterIndex: Int

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

    override val parameterIndex: Int = 0
  }

  /**
    * A concrete ''RoleType'' representing the destination structure.
    */
  case object DestinationRoleType extends RoleType {
    override val name: String = "dst-"

    override val parameterIndex: Int = 1
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
  case class FsStructureConfig(optTimeZone: Option[ZoneId]) extends StructureConfig

  /**
    * Parameter configuration class for the structure type ''WebDav''.
    *
    * @param optLastModifiedProperty  optional property with the last modified
    *                                 timestamp
    * @param optLastModifiedNamespace optional namespace for the last modified
    *                                 property
    * @param deleteBeforeOverride     the delete before override flag
    * @param authConfig               the authentication configuration
    */
  case class DavStructureConfig(optLastModifiedProperty: Option[String],
                                optLastModifiedNamespace: Option[String],
                                deleteBeforeOverride: Boolean,
                                authConfig: AuthConfig) extends StructureConfig

  /**
    * Parameter configuration class for the structure type ''OneDrive''.
    *
    * @param syncPath             the relative path in the drive to be synced
    * @param optUploadChunkSizeMB optional chunk size for uploads of large
    *                             files (in MB)
    * @param optServerUri         optional (alternative) server URI
    * @param authConfig           the config of the auth mechanism
    */
  case class OneDriveStructureConfig(syncPath: String,
                                     optUploadChunkSizeMB: Option[Int],
                                     optServerUri: Option[String],
                                     authConfig: AuthConfig) extends StructureConfig

  /**
    * Returns a ''CliProcessor'' to extract the configuration of the sync
    * structure for the role specified. This processor checks the type of the
    * structure (based on the sync URI parameter), and then creates a
    * corresponding ''StructureConfig'' object.
    *
    * @param roleType the role type
    * @return the ''CliProcessor'' to extract the config of this role
    */
  def structureConfigProcessor(roleType: RoleType): CliProcessor[Try[StructureConfig]] = {
    val procMap = Map(GroupLocalFs -> localFsConfigProcessor(roleType),
      GroupDav -> davConfigProcessor(roleType),
      GroupOneDrive -> oneDriveConfigProcessor(roleType))
    conditionalGroupValue(structureTypeSelectorProcessor(roleType), procMap)
  }

  /**
    * Returns a ''CliProcessor'' that maps the input parameter for the URI of
    * the given role type to a group name, based on the concrete structure
    * type. The group name is then used to parse the correct command line
    * options to construct a corresponding structure config.
    *
    * @param roleType the role type
    * @return the processor that determines the group of the structure type
    */
  private def structureTypeSelectorProcessor(roleType: RoleType): CliProcessor[Try[String]] =
    inputValue(index = roleType.parameterIndex)
      .mapTo {
        case RegDavUri(_) => GroupDav
        case RegOneDriveID(_) => GroupOneDrive
        case _ => GroupLocalFs
      }.single
      .mandatory

  /**
    * Returns a ''CliProcessor'' that extracts the configuration for the local
    * file system from the current command line arguments.
    *
    * @param roleType the role type
    * @return the ''CliProcessor'' for the file system configuration
    */
  private def localFsConfigProcessor(roleType: RoleType): CliProcessor[Try[StructureConfig]] =
    optionValue(roleType.configPropertyName(PropLocalFsTimeZone))
      .mapTo(ZoneId.of)
      .single
      .map(_.map(optZone => FsStructureConfig(optZone)))

  /**
    * Returns a ''CliProcessor'' that extracts the configuration for a WebDav
    * server from the current command line arguments.
    *
    * @param roleType the role type
    * @return the ''CliProcessor'' for the WebDav configuration
    */
  private def davConfigProcessor(roleType: RoleType): CliProcessor[Try[StructureConfig]] = {
    val procModProp = optionValue(roleType.configPropertyName(PropDavModifiedProperty)).single
    val procModNs = optionValue(roleType.configPropertyName(PropDavModifiedNamespace)).single
    val procDel = optionValue(roleType.configPropertyName(PropDavDeleteBeforeOverride))
      .toBoolean
      .fallbackValues(false)
      .single
      .mandatory
    for {
      triedModProp <- procModProp
      triedModNs <- procModNs
      triedDel <- procDel
      triedAuth <- authConfigProcessor(roleType)
    } yield createDavConfig(triedModProp, triedModNs, triedDel, triedAuth)
  }

  /**
    * Returns a ''CliProcessor'' that extracts the configuration for a OneDrive
    * server from the current command line arguments.
    *
    * @param roleType the structure type
    * @return the ''CliProcessor'' for the OneDrive configuration
    */
  private def oneDriveConfigProcessor(roleType: RoleType): CliProcessor[Try[StructureConfig]] = {
    val procPath = optionValue(roleType.configPropertyName(PropOneDrivePath))
      .single
      .mandatory
    val procChunkSize = optionValue(roleType.configPropertyName(PropOneDriveUploadChunkSize))
      .toInt
      .single
    val procServer = optionValue(roleType.configPropertyName(PropOneDriveServer)).single
    for {
      triedPath <- procPath
      triedChunkSize <- procChunkSize
      triedServer <- procServer
      triedAuth <- authConfigProcessor(roleType)
    } yield createOneDriveConfig(triedPath, triedChunkSize, triedServer, triedAuth)
  }

  /**
    * Returns a ''CliProcessor'' for obtaining the authentication
    * configuration. The processor creates a concrete implementation of the
    * [[AuthConfig]] trait depending on the properties that are specified.
    *
    * @param roleType the role type
    * @return the ''CliProcessor'' for the auth config
    */
  private def authConfigProcessor(roleType: RoleType): CliProcessor[Try[AuthConfig]] = {
    val procBasicDefined = isDefinedProcessor(roleType.configPropertyName(PropAuthUser))
    val procOAuthDefined = isDefinedProcessor(roleType.configPropertyName(
      OAuthParameterManager.NameOptionName))
    val condNoAuth = conditionalValue(procOAuthDefined, ifProc = constantOptionValue(GroupOAuth),
      elseProc = constantOptionValue(GroupNoAuth))
    val groupSelector: CliProcessor[Try[String]] =
      conditionalValue(procBasicDefined, ifProc = constantOptionValue(GroupBasicAuth), elseProc = condNoAuth)
        .single.mandatory
    val groupMap = Map[String, CliProcessor[Try[AuthConfig]]](
      GroupBasicAuth -> basicAuthProcessor(roleType),
      GroupOAuth -> oauthConfigProcessor(roleType),
      GroupNoAuth -> constantProcessor(Success(NoAuth)))
    conditionalGroupValue(groupSelector, groupMap)
  }

  /**
    * Returns a ''CliProcessor'' for obtaining the basic auth configuration.
    *
    * @param roleType the role type
    * @return the ''CliProcessor'' for the basic auth config
    */
  private def basicAuthProcessor(roleType: RoleType): CliProcessor[Try[AuthConfig]] = {
    val procUser = optionValue(roleType.configPropertyName(PropAuthUser))
      .single
      .mandatory
    for {
      triedUser <- procUser
      triedPassword <- davPasswordOption(roleType)
    } yield createBasicAuthConfig(triedUser, triedPassword)
  }

  /**
    * Returns a ''CliProcessor'' for obtaining the password for Basic Auth.
    * The password is mandatory; if it is not specified in the arguments, it is
    * read from the console.
    *
    * @param roleType the role type
    * @return the ''CliProcessor'' for the Basic Auth password
    */
  private def davPasswordOption(roleType: RoleType): CliProcessor[Try[String]] = {
    val prop = roleType.configPropertyName(PropAuthPassword)
    optionValue(prop)
      .fallback(consoleReaderValue(prop, password = true))
      .single
      .mandatory
  }

  /**
    * Returns a ''CliProcessor'' for obtaining the OAuth configuration.
    *
    * @param roleType the role type
    * @return the ''CliProcessor'' for the OAuth configuration
    */
  private def oauthConfigProcessor(roleType: RoleType): CliProcessor[Try[AuthConfig]] = {
    OAuthParameterManager.storageConfigProcessor(needPassword = true,
      prefix = OptionPrefix + roleType.name)
      .map { triedConfig =>
        triedConfig map (config => config.asInstanceOf[AuthConfig])
      }
  }

  /**
    * Creates an ''OptionValue'' with a ''BasicAuthConfig'' based on the given
    * components.
    *
    * @param triedUser     the user component
    * @param triedPassword the password component
    * @return the option value for the ''BasicAuthConfig''
    */
  private def createBasicAuthConfig(triedUser: Try[String], triedPassword: Try[String]):
  Try[BasicAuthConfig] =
    createRepresentation(triedUser, triedPassword) { (usr, pwd) =>
      BasicAuthConfig(usr, Secret(pwd))
    }

  /**
    * Creates a ''DavStructureConfig'' object from the given components. Errors
    * are aggregated in the resulting ''Try''.
    *
    * @param triedOptModifiedProp      the component for the modified property
    * @param triedOptModifiedNamespace the component for the modified namespace
    * @param triedDelBeforeOverride    the component for the delete before
    *                                  override flag
    * @param triedAuthConfig           the component for the auth config
    * @return a ''Try'' with the configuration for a Dav server
    */
  private def createDavConfig(triedOptModifiedProp: Try[Option[String]],
                              triedOptModifiedNamespace: Try[Option[String]],
                              triedDelBeforeOverride: Try[Boolean],
                              triedAuthConfig: Try[AuthConfig]): Try[DavStructureConfig] = {
    createRepresentation(triedOptModifiedProp, triedOptModifiedNamespace,
      triedDelBeforeOverride, triedAuthConfig)(DavStructureConfig)
  }

  /**
    * Creates a ''OneDriveStructureConfig'' object from the given components.
    * Errors are aggregated in the resulting ''Try''.
    *
    * @param triedPath      the component for the sync path
    * @param triedChunkSize the component for the upload chung size
    * @param triedServer    the component for the optional server URI
    * @param triedAuth      the component for the auth config
    * @return a ''Try'' with the OneDrive configuration
    */
  private def createOneDriveConfig(triedPath: Try[String],
                                   triedChunkSize: Try[Option[Int]],
                                   triedServer: Try[Option[String]],
                                   triedAuth: Try[AuthConfig]): Try[OneDriveStructureConfig] =
    createRepresentation(triedPath, triedChunkSize, triedServer, triedAuth)(OneDriveStructureConfig)
}
