/*
 * Copyright 2018-2023 The Developers Team.
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

import com.github.cloudfiles.core.http.Secret
import com.github.scli.ParameterExtractor._
import com.github.sync.cli.oauth.OAuthParameterManager
import com.github.sync.oauth.{SyncAuthConfig, SyncBasicAuthConfig, SyncNoAuth}
import com.github.sync.protocol.config.{DavStructureConfig, FsStructureConfig, GoogleDriveStructureConfig, OneDriveStructureConfig, StructureConfig}

import java.time.ZoneId
import scala.util.{Success, Try}

/**
  * A module defining CLI-related configuration parameters for the structures
  * taking part in a sync process.
  *
  * Depending on the types of structures to be synced, different parameters
  * need to be passed to the command line. This module defines ''CliExtractor''
  * objects to construct the configuration objects for the source and
  * destination structure from the current command line.
  *
  * These configuration objects are part of the overall sync configuration.
  * They are parsed dynamically from the command line; this is done via a
  * conditional ''CliExtractor'' that evaluates the URI defining the structure.
  */
object SyncCliStructureConfig:
  /** URI prefix indicating a WebDav structure. */
  final val PrefixWebDav = "dav:"

  /** URI prefix indicating a OneDrive structure. */
  final val PrefixOneDrive = "onedrive:"

  /** URI prefix indicating a GoogleDrive structure. */
  final val PrefixGoogleDrive = "googledrive:"

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

  /** Help text for the local FS time zone option. */
  final val HelpLocalFsTimeZone =
    """Allows defining a time zone for the file system; modified timestamps of files are interpreted \
      |in this time zone. This can be necessary for file systems like FAT32, which do not store \
      |time zone information; otherwise, a change of the local time (e.g. when daylight saving \
      |time starts or ends) can cause changes in the files to be reported. The value must be a \
      |time zone ID that is accepted by the ZoneId class, such as "UTC+02".""".stripMargin

  /** Property for the user name if Basic Auth is used. */
  final val PropAuthUser = "user"

  /** Help text for the basic auth user. */
  final val HelpAuthUser =
    """Sets the user name for basic authentication for this structure."""

  /** Property for the password if Basic Auth is used. */
  final val PropAuthPassword = "password"

  /** Help text for the basic auth password. */
  final val HelpAuthPassword =
    """Sets the password for basic authentication for this structure. This option is required if a \
      |user name is set. If it is not specified, it is read from the console.""".stripMargin

  /**
    * Property for the name of the WebDav property defining the last modified
    * time of an element. This is optional; if unspecified, the default WebDav
    * property for the last modified time is used.
    */
  final val PropDavModifiedProperty = "modified-property"

  /** Help text for the WebDav last modified property option. */
  final val HelpDavModifiedProperty =
    """Allows setting the name of the property with the timestamp of the last modification. \
      |Different servers can use different properties for this purpose. This is optional; if \
      |it is undefined, a default name for the last modified property is used.""".stripMargin

  /**
    * Property for the name of the WebDav property that defines a namespace for
    * the property with the last modified time. If this property is defined, in
    * patch requests to the WebDav server to update the modified time of a file
    * this namespace will be used. Note that this property has an effect only
    * if a custom modified property is set.
    */
  final val PropDavModifiedNamespace = "modified-namespace"

  /** Help text for the WebDav modified namespace option. */
  final val HelpDavModifiedNamespace =
    """Optionally defines a dedicated namespace for the property with the timestamp of the last \
      |modification. This may be required for certain WebDav servers. If undefined, no special \
      |namespace is used for this property.""".stripMargin

  /**
    * Property to determine whether a file to be overridden should be deleted
    * before it is uploaded. This may be necessary for some servers to have a
    * reliable behavior. The value of the property is a string that is
    * interpreted as a boolean value (in terms of ''Boolean.parseBoolean()'').
    */
  final val PropDavDeleteBeforeOverride = "delete-before-override"

  /** Help text for the WebDav delete before override property. */
  final val HelpDavDeleteBeforeOverride =
    """Sets a flag whether a file override operation on a WebDav server should be preceded by a \
      |delete operation. This can be required for specific servers if overrides are not reliable.""".stripMargin

  /**
    * Property for the relative path to be synced on a OneDrive drive.
    */
  final val PropOneDrivePath = "path"

  /** Help text for the OneDrive sync path property. */
  final val HelpOneDrivePath =
    """Defines the relative path on the OneDrive server which should be synced. Using this option, arbitrary \
      |sub paths can be synced.""".stripMargin

  /**
    * Property for the URI of the OneDrive server. This property is optional;
    * the default server URI is used if not specified.
    */
  final val PropOneDriveServer = "server-uri"

  /** Help text for the OneDrive server URI property. */
  final val HelpOneDriveServer =
    """Allows setting an alternative URI for the OneDrive server. This is needed only in special cases, as \
      |the default OneDrive URI should be appropriate.""".stripMargin

  /**
    * Property for the chunk size (in MB) for file uploads to a OneDrive
    * server. This is an optional property.
    */
  final val PropOneDriveUploadChunkSize = "upload-chunk-size"

  /** Help text for the OneDrive upload chunk size property. */
  final val HelpOneDriveUploadChunkSize =
    """Allows setting a chunk size for uploads to a OneDrive server. OneDrive has a size restriction for \
      |file uploads. If a file larger than the threshold is to be uploaded, it needs to be split into \
      |multiple chunks. This option defines the maximum file size in MB. It is optional.""".stripMargin

  /**
    * Property for the URI of the GoogleDrive server. This property is
    * optional; the default server URI is used if not specified.
    */
  final val PropGoogleDriveServer = "server-uri"

  /** Help text for the GoogleDrive server property. */
  final val HelpGoogleDriveServer =
    """Allows setting an alternative URI for the GoogleDrive server. This is needed only in special cases, as \
      |the default GoogleDrive URI should be appropriate.""".stripMargin

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

  /** Group name for the parameters related to GoogleDrive */
  final val GroupGoogleDrive = "googledrive"

  /** Regular expression for parsing a WebDav URI. */
  final val RegDavUri = (PrefixWebDav + "(.+)").r

  /** Regular expression for parsing a OneDrive drive ID. */
  final val RegOneDriveID = (PrefixOneDrive + "(.+)").r

  /** Regular expression for checking for a GoogleDrive URI. */
  final val RegGoogleDriveID = (PrefixGoogleDrive + ".*").r

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
  sealed trait RoleType:
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
    def configPropertyName(property: String): String = s"$name$property"

  /**
    * A concrete ''RoleType'' representing the source structure.
    */
  case object SourceRoleType extends RoleType:
    override val name: String = "src-"

    override val parameterIndex: Int = 0

  /**
    * A concrete ''RoleType'' representing the destination structure.
    */
  case object DestinationRoleType extends RoleType:
    override val name: String = "dst-"

    override val parameterIndex: Int = 1

  /**
    * A class that combines a configuration for a sync structure with the
    * configuration for the authentication mechanism to be used for this
    * structure.
    *
    * @param structureConfig the configuration of the sync structure
    * @param authConfig      the configuration of the auth mechanism
    */
  case class StructureAuthConfig(structureConfig: StructureConfig,
                                 authConfig: SyncAuthConfig)

  /**
    * Returns the name of the structure group with the given role type.
    *
    * @param group    the plain group name
    * @param roleType the role type
    * @return the group name decorated by the role type
    */
  def structureGroup(group: String, roleType: RoleType): String =
    roleType.name + group

  /**
    * Returns a ''CliExtractor'' to extract the configuration of the sync
    * structure for the role specified. This extractor checks the type of the
    * structure (based on the sync URI parameter), and then creates a
    * corresponding ''StructureConfig'' object.
    *
    * @param roleType      the role type
    * @param uriOptionName the name of the option for the structure URI
    * @return the ''CliExtractor'' to extract the config of this role
    */
  def structureConfigExtractor(roleType: RoleType, uriOptionName: String): CliExtractor[Try[StructureAuthConfig]] =
    val extMap = Map(structureGroup(GroupLocalFs, roleType) -> localFsConfigExtractor(roleType),
      structureGroup(GroupDav, roleType) -> davConfigExtractor(roleType),
      structureGroup(GroupOneDrive, roleType) -> oneDriveConfigExtractor(roleType),
      structureGroup(GroupGoogleDrive, roleType) -> googleDriveConfigExtractor(roleType))
    conditionalGroupValue(structureTypeSelectorExtractor(roleType, uriOptionName), extMap)

  /**
    * Returns a ''CliExtractor'' that maps the input parameter for the URI of
    * the given role type to a group name, based on the concrete structure
    * type. The group name is then used to parse the correct command line
    * options to construct a corresponding structure config.
    *
    * @param roleType      the role type
    * @param uriOptionName the name of the option for the structure URI
    * @return the extractor that determines the group of the structure type
    */
  def structureTypeSelectorExtractor(roleType: RoleType, uriOptionName: String): CliExtractor[Try[String]] =
    inputValue(index = roleType.parameterIndex, optKey = Some(uriOptionName))
      .mapTo {
        case RegDavUri(_) => structureGroup(GroupDav, roleType)
        case RegOneDriveID(_) => structureGroup(GroupOneDrive, roleType)
        case RegGoogleDriveID() => structureGroup(GroupGoogleDrive, roleType)
        case _ => structureGroup(GroupLocalFs, roleType)
      }.mandatory

  /**
    * Returns a ''CliExtractor'' that extracts the configuration for the local
    * file system from the current command line arguments.
    *
    * @param roleType the role type
    * @return the ''CliExtractor'' for the file system configuration
    */
  private def localFsConfigExtractor(roleType: RoleType): CliExtractor[Try[StructureAuthConfig]] =
    optionValue(roleType.configPropertyName(PropLocalFsTimeZone), help = Some(HelpLocalFsTimeZone))
      .mapTo(ZoneId.of)
      .map(_.map(optZone => FsStructureConfig(optZone)))
      .map(_.map(StructureAuthConfig(_, SyncNoAuth)))

  /**
    * Returns a ''CliExtractor'' that extracts the configuration for a WebDav
    * server from the current command line arguments.
    *
    * @param roleType the role type
    * @return the ''CliExtractor'' for the WebDav configuration
    */
  private def davConfigExtractor(roleType: RoleType): CliExtractor[Try[StructureAuthConfig]] =
    val extModProp = optionValue(roleType.configPropertyName(PropDavModifiedProperty),
      help = Some(HelpDavModifiedProperty))
    val extModNs = optionValue(roleType.configPropertyName(PropDavModifiedNamespace),
      help = Some(HelpDavModifiedNamespace))
    val extDel = optionValue(roleType.configPropertyName(PropDavDeleteBeforeOverride),
      help = Some(HelpDavDeleteBeforeOverride))
      .toBoolean
      .fallbackValue(false)
      .mandatory
    for
      triedModProp <- extModProp
      triedModNs <- extModNs
      triedDel <- extDel
      triedAuth <- authConfigExtractor(roleType)
    yield createDavConfig(triedModProp, triedModNs, triedDel, triedAuth)

  /**
    * Returns a ''CliExtractor'' that extracts the configuration for a OneDrive
    * server from the current command line arguments.
    *
    * @param roleType the structure type
    * @return the ''CliExtractor'' for the OneDrive configuration
    */
  private def oneDriveConfigExtractor(roleType: RoleType): CliExtractor[Try[StructureAuthConfig]] =
    val extPath = optionValue(roleType.configPropertyName(PropOneDrivePath), help = Some(HelpOneDrivePath))
      .mandatory
    val extChunkSize = optionValue(roleType.configPropertyName(PropOneDriveUploadChunkSize),
      help = Some(HelpOneDriveUploadChunkSize))
      .toInt
    val extServer = optionValue(roleType.configPropertyName(PropOneDriveServer),
      help = Some(HelpOneDriveServer))
    for
      triedPath <- extPath
      triedChunkSize <- extChunkSize
      triedServer <- extServer
      triedAuth <- authConfigExtractor(roleType)
    yield createOneDriveConfig(triedPath, triedChunkSize, triedServer, triedAuth)

  /**
    * Returns a ''CliExtractor'' that extracts the configuration for a
    * GoogleDrive server from the current command line arguments.
    *
    * @param roleType the structure type
    * @return the ''CliExtractor'' for the GoogleDrive configuration
    */
  private def googleDriveConfigExtractor(roleType: RoleType): CliExtractor[Try[StructureAuthConfig]] =
    val extServer = optionValue(roleType.configPropertyName(PropGoogleDriveServer),
      help = Some(HelpGoogleDriveServer))
    for
      triedServer <- extServer
      triedAuth <- authConfigExtractor(roleType)
    yield createGoogleDriveConfig(triedServer, triedAuth)

  /**
    * Returns a ''CliExtractor'' for obtaining the authentication
    * configuration. The extractor creates a concrete implementation of the
    * [[SyncAuthConfig]] trait depending on the properties that are specified.
    *
    * @param roleType the role type
    * @return the ''CliExtractor'' for the auth config
    */
  private def authConfigExtractor(roleType: RoleType): CliExtractor[Try[SyncAuthConfig]] =
    val extBasicDefined = isDefinedExtractor(roleType.configPropertyName(PropAuthUser))
    val extOAuthDefined = isDefinedExtractor(roleType.configPropertyName(
      OAuthParameterManager.NameOption))
    val condNoAuth = conditionalValue(extOAuthDefined,
      ifExt = constantOptionValueWithDesc(None, GroupOAuth),
      elseExt = constantOptionValueWithDesc(None, GroupNoAuth))
    val groupSelector: CliExtractor[Try[String]] =
      conditionalValue(extBasicDefined, ifExt = constantOptionValueWithDesc(None, GroupBasicAuth),
        elseExt = condNoAuth)
        .single.mandatory
    val groupMap = Map[String, CliExtractor[Try[SyncAuthConfig]]](
      GroupBasicAuth -> basicAuthExtractor(roleType),
      GroupOAuth -> oauthConfigExtractor(roleType),
      GroupNoAuth -> constantExtractor(Success(SyncNoAuth)))
    conditionalGroupValue(groupSelector, groupMap)

  /**
    * Returns a ''CliExtractor'' for obtaining the basic auth configuration.
    *
    * @param roleType the role type
    * @return the ''CliExtractor'' for the basic auth config
    */
  private def basicAuthExtractor(roleType: RoleType): CliExtractor[Try[SyncAuthConfig]] =
    val extUser = optionValue(roleType.configPropertyName(PropAuthUser), help = Some(HelpAuthUser))
      .mandatory
    for
      triedUser <- extUser
      triedPassword <- davPasswordOption(roleType)
    yield createBasicAuthConfig(triedUser, triedPassword)

  /**
    * Returns a ''CliExtractor'' for obtaining the password for Basic Auth.
    * The password is mandatory; if it is not specified in the arguments, it is
    * read from the console.
    *
    * @param roleType the role type
    * @return the ''CliExtractor'' for the Basic Auth password
    */
  private def davPasswordOption(roleType: RoleType): CliExtractor[Try[String]] =
    val prop = roleType.configPropertyName(PropAuthPassword)
    optionValue(prop, help = Some(HelpAuthPassword))
      .fallback(consoleReaderValue(prop, password = true))
      .mandatory

  /**
    * Returns a ''CliExtractor'' for obtaining the OAuth configuration.
    *
    * @param roleType the role type
    * @return the ''CliExtractor'' for the OAuth configuration
    */
  private def oauthConfigExtractor(roleType: RoleType): CliExtractor[Try[SyncAuthConfig]] =
    OAuthParameterManager.storageConfigExtractor(needPassword = true, prefix = roleType.name)
      .map { triedConfig =>
        triedConfig map (config => config.asInstanceOf[SyncAuthConfig])
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
  Try[SyncBasicAuthConfig] =
    createRepresentation(triedUser, triedPassword) { (usr, pwd) =>
      SyncBasicAuthConfig(usr, Secret(pwd))
    }

  /**
    * Creates a ''StructureAuthConfig'' with a [[DavStructureConfig]] from the
    * given components. Errors are aggregated in the resulting ''Try''.
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
                              triedAuthConfig: Try[SyncAuthConfig]): Try[StructureAuthConfig] =
    createRepresentation(triedOptModifiedProp, triedOptModifiedNamespace,
      triedDelBeforeOverride, triedAuthConfig) { (modProp, modNs, fDel, auth) =>
      StructureAuthConfig(DavStructureConfig(modProp, modNs, fDel), auth)
    }

  /**
    * Creates a ''StructureAuthConfig'' with a [[OneDriveStructureConfig]]
    * from the given components. Errors are aggregated in the resulting
    * ''Try''.
    *
    * @param triedPath      the component for the sync path
    * @param triedChunkSize the component for the upload chung size
    * @param triedServer    the component for the optional server URI
    * @param triedAuth      the component for the auth config
    * @return a ''Try'' with the resulting configuration
    */
  private def createOneDriveConfig(triedPath: Try[String],
                                   triedChunkSize: Try[Option[Int]],
                                   triedServer: Try[Option[String]],
                                   triedAuth: Try[SyncAuthConfig]): Try[StructureAuthConfig] =
    createRepresentation(triedPath, triedChunkSize, triedServer, triedAuth) { (path, chunk, server, auth) =>
      StructureAuthConfig(OneDriveStructureConfig(path, chunk, server), auth)
    }

  /**
    * Creates a ''StructureAuthConfig'' with a [[GoogleDriveStructureConfig]]
    * from the given components.
    *
    * @param triedServer the component for the optional server URI
    * @param triedAuth   the component for the auth config
    * @return a ''Try'' with the resulting configuration
    */
  private def createGoogleDriveConfig(triedServer: Try[Option[String]],
                                      triedAuth: Try[SyncAuthConfig]): Try[StructureAuthConfig] =
    createRepresentation(triedServer, triedAuth) { (server, auth) =>
      StructureAuthConfig(GoogleDriveStructureConfig(server), auth)
    }
