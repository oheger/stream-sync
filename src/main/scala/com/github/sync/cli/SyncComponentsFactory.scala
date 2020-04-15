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

import java.nio.file.Paths
import java.time.ZoneId

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes.{ElementSourceFactory, FsElement, SyncOperation}
import com.github.sync.cli.ParameterManager._
import com.github.sync.cli.oauth.OAuthParameterManager
import com.github.sync.crypt.Secret
import com.github.sync.http._
import com.github.sync.http.oauth.{OAuthConfig, OAuthStorageService, OAuthStorageServiceImpl, OAuthTokenData}
import com.github.sync.local.LocalFsConfig
import com.github.sync.onedrive.OneDriveConfig
import com.github.sync.webdav.DavConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object SyncComponentsFactory {
  /** URI prefix indicating a WebDav structure. */
  val PrefixWebDav = "dav:"

  /** URI prefix indicating a OneDrive structure. */
  val PrefixOneDrive = "onedrive:"

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
  val PropLocalFsTimeZone = "time-zone"

  /**
    * Property name for the root path of a local file system.
    */
  val PropLocalFsPath = "path"

  /**
    * Property for the URI of a WebDav server. This is used to generate an
    * error message if the URI is invalid.
    */
  val PropDavUri = "uri"

  /**
    * Property for the Dav user name. This is used to authenticate against the
    * WebDav server.
    */
  val PropDavUser = "user"

  /**
    * Property for the Dav password of the user. This is used to authenticate
    * against the WebDav server.
    */
  val PropDavPassword = "password"

  /**
    * Property for the name of the WebDav property defining the last modified
    * time of an element. This is optional; if unspecified, the default WebDav
    * property for the last modified time is used.
    */
  val PropDavModifiedProperty = "modified-property"

  /**
    * Property for the name of the WebDav property that defines a namespace for
    * the property with the last modified time. If this property is defined, in
    * patch requests to the WebDav server to update the modified time of a file
    * this namespace will be used. Note that this property has an effect only
    * if a custom modified property is set.
    */
  val PropDavModifiedNamespace = "modified-namespace"

  /**
    * Property to determine whether a file to be overridden should be deleted
    * before it is uploaded. This may be necessary for some servers to have a
    * reliable behavior. The value of the property is a string that is
    * interpreted as a boolean value (in terms of ''Boolean.parseBoolean()'').
    */
  val PropDavDeleteBeforeOverride = "delete-before-override"

  /**
    * Property for the relative path to be synced on a OneDrive drive.
    */
  val PropOneDrivePath = "path"

  /**
    * Property for the URI of the OneDrive server. This property is optional;
    * the default server URI is used if not specified.
    */
  val PropOneDriveServer = "server-uri"

  /**
    * Property for the chunk size (in MB) for file uploads to a OneDrive
    * server. This is an optional property.
    */
  val PropOneDriveUploadChunkSize = "upload-chunk-size"

  /**
    * Constant for the size of the cache with different hosts for a OneDrive
    * server.
    */
  val OneDriveHostCacheSize = 8

  /** Group name for the options for basic auth. */
  final val GroupBasicAuth = "authBasic"

  /** Group name of the options for OAuth. */
  final val GroupOAuth = "authOAuth"

  /** Group name to be used if no authentication is desired. */
  final val GroupNoAuth = "authNone"

  /** Regular expression for parsing a WebDav URI. */
  private val RegDavUri = (PrefixWebDav + "(.+)").r

  /** Regular expression for parsing a OneDrive drive ID. */
  private val RegOneDriveID = (PrefixOneDrive + "(.+)").r

  /**
    * A trait defining the type of a structure to be synced.
    *
    * The type determines whether a structure acts as source or destination of a
    * sync process. It is passed to some functions that create certain elements
    * to handle sync actions like sources or processing stages.
    *
    * From the parameters passed to a sync process it must be possible to find
    * out which ones apply to the source and to the destination structure. This
    * is done by defining a unique ''name'' property for the structure type.
    * Parameters can then be prefixed by this name to make clear to which
    * structure they apply.
    */
  sealed trait StructureType {
    /**
      * Returns a name of this structure type.
      *
      * @return the name property
      */
    def name: String

    /**
      * Determines the name of a configuration property with the given name for
      * this ''StructureType''. The full property name is determined by prefixing
      * it with the name of this type. In addition, the parameter prefix is
      * prepended.
      *
      * @param property the property name
      * @return the full property name for this source type
      */
    def configPropertyName(property: String): String = s"${ParameterManager.OptionPrefix}$name$property"
  }

  /**
    * A concrete ''StructureType'' representing the source structure.
    */
  case object SourceStructureType extends StructureType {
    override val name: String = "src-"
  }

  /**
    * A concrete ''StructureType'' representing the destination structure.
    */
  case object DestinationStructureType extends StructureType {
    override val name: String = "dst-"
  }

  /**
    * Type definition for a function that does cleanup for a components
    * factory. Resources created by the factory, such as actors, can be freed
    * by invoking this function.
    */
  type CleanupFunction = () => Unit

  /** Constant for a cleanup function that does nothing. */
  val EmptyCleanupFunction: CleanupFunction = () => {}

  /**
    * A data class collecting information about the apply stage of a sync
    * stream.
    *
    * This class is used by [[DestinationComponentsFactory]] to return multiple
    * results: the actual stage of the stream plus a function for cleaning up
    * resources when the stream completes.
    *
    * @param stage   the flow to apply sync operations
    * @param cleanUp a cleanup function
    */
  case class ApplyStageData(stage: Flow[SyncOperation, SyncOperation, NotUsed],
                            cleanUp: CleanupFunction = EmptyCleanupFunction)

  /**
    * A trait describing a factory for creating the components of a sync stream
    * that are related to the source structure.
    */
  trait SourceComponentsFactory {
    /**
      * Creates the ''Source''for iterating over the source structure of the sync
      * process.
      *
      * @param sourceFactory the factory for creating an element source
      * @return the source for iterating the source structure
      */
    def createSource(sourceFactory: ElementSourceFactory): Source[FsElement, Any]

    /**
      * Creates a ''SourceFileProvider'' to access files in the source
      * structure.
      *
      * @return the ''SourceFileProvider''
      */
    def createSourceFileProvider(): SourceFileProvider
  }

  /**
    * A trait describing a factory for creating the components of a sync stream
    * that are related to the destination structure.
    */
  trait DestinationComponentsFactory {
    /**
      * Creates the ''Source''for iterating over the destination structure of the sync
      * process.
      *
      * @param sourceFactory the factory for creating an element source
      * @return the source for iterating the destination structure
      */
    def createDestinationSource(sourceFactory: ElementSourceFactory): Source[FsElement, Any]

    /**
      * Creates a ''Source'' for iterating over the destination structure
      * starting with a given folder. This is needed for some use cases to
      * resolve files in the destination structure; e.g. if folder names are
      * encrypted.
      *
      * @param sourceFactory  the factory for creating an element source
      * @param startFolderUri the URI of the start folder of the iteration
      * @return the source for a partial iteration
      */
    def createPartialSource(sourceFactory: ElementSourceFactory, startFolderUri: String):
    Source[FsElement, Any]

    /**
      * Creates the flow stage that interprets sync operations and applies them
      * to the destination structure and returns a data object with this flow
      * and a function for cleaning up resources.
      *
      * @param targetUri    the target URI of the destination structure
      * @param fileProvider the provider for files from the source structure
      * @return the data object about the stage to process sync operations
      */
    def createApplyStage(targetUri: String, fileProvider: SourceFileProvider): ApplyStageData
  }

  /**
    * Extracts a configuration object of a specific type from command line
    * arguments with the help of a ''CliProcessor''. The processor is run, and
    * its result is mapped to a correct ''Future''.
    *
    * @param processor     the ''CliProcessor'' defining the configuration
    * @param parameters    the object with command line arguments
    * @param ec            the execution context
    * @param consoleReader the object for reading from the console
    * @tparam C the type of the configuration object
    * @return a ''Future'' with the updated parameters and the configuration
    */
  private def extractConfig[C](processor: CliProcessor[Try[C]], parameters: Parameters)
                              (implicit ec: ExecutionContext, consoleReader: ConsoleReader):
  Future[(C, ParameterContext)] =
    Future.fromTry(ParameterManager.tryProcessor(processor, parameters))

  /**
    * Extracts the configuration for the local file system from the given
    * parameters. Errors are handled, and the map with parameters is updated.
    *
    * @param uri           the URI acting as the root path for the file system
    * @param parameters    the object with command line parameters
    * @param structureType the structure type
    * @param ec            the execution context
    * @param consoleReader the object for reading from the console
    * @return a ''Future'' with the extracted file system configuration and the
    *         updated ''ParameterContext''
    */
  private def extractLocalFsConfig(uri: String, parameters: Parameters, structureType: StructureType)
                                  (implicit ec: ExecutionContext, consoleReader: ConsoleReader):
  Future[(LocalFsConfig, ParameterContext)] = {
    val processor = localFsConfigProcessor(uri, structureType)
    extractConfig(processor, parameters)
  }

  /**
    * Returns a ''CliProcessor'' that extracts the configuration for the local
    * file system from the current command line arguments.
    *
    * @param uri           the URI acting as root path for the file system
    * @param structureType the structure type
    * @return the ''CliProcessor'' for the file system configuration
    */
  private def localFsConfigProcessor(uri: String, structureType: StructureType): CliProcessor[Try[LocalFsConfig]] =
    optionValue(structureType.configPropertyName(PropLocalFsTimeZone))
      .mapTo(ZoneId.of)
      .single
      .map(triedZone => createLocalFsConfig(uri, structureType, triedZone))

  /**
    * Creates a ''LocalFsConfig'' object from the given components. Errors are
    * aggregated in the resulting ''Try''.
    *
    * @param uri           the URI acting as root path for the file system
    * @param structureType the structure type
    * @param triedZone     the tried time zone component
    * @return a ''Try'' with the file system configuration
    */
  private def createLocalFsConfig(uri: String, structureType: StructureType, triedZone: Try[Option[ZoneId]]):
  Try[LocalFsConfig] = {
    val triedPath = ParameterManager.paramTry(structureType.configPropertyName(PropLocalFsPath))(Paths get uri)
    ParameterManager.createRepresentation(triedPath, triedZone)(LocalFsConfig.apply)
  }

  /**
    * Extracts the configuration for a WebDav server from the given parameters.
    * Errors are handled, and the map with parameters is updated.
    *
    * @param uri           the URI of the WebDav server
    * @param timeout       the timeout for requests
    * @param parameters    the parameters object
    * @param structureType the structure type
    * @param ec            the execution context
    * @param consoleReader the object for reading from the console
    * @return a ''Future'' with the extracted configuration and updated
    *         ''ParameterContext''
    */
  private def extractDavConfig(uri: String, timeout: Timeout, parameters: Parameters, structureType: StructureType)
                              (implicit ec: ExecutionContext, consoleReader: ConsoleReader):
  Future[(DavConfig, ParameterContext)] =
    extractConfig(davConfigProcessor(uri, timeout, structureType), parameters)

  /**
    * Returns a ''CliProcessor'' that extracts the configuration for a WebDav
    * server from the current command line arguments.
    *
    * @param uri           the URI of the WebDav server
    * @param timeout       the timeout for requests
    * @param structureType the structure type
    * @return the ''CliProcessor'' for the WebDav configuration
    */
  private def davConfigProcessor(uri: String, timeout: Timeout, structureType: StructureType):
  CliProcessor[Try[DavConfig]] = {
    val keyDelBeforeOverride = structureType.configPropertyName(PropDavDeleteBeforeOverride)
    for {
      triedModProp <- stringOptionValue(structureType.configPropertyName(PropDavModifiedProperty))
      triedModNs <- stringOptionValue(structureType.configPropertyName(PropDavModifiedNamespace))
      triedDel <- asMandatory(booleanOptionValue(keyDelBeforeOverride, Some(false)))
      triedAuth <- authConfigProcessor(structureType)
    } yield createDavConfig(uri, timeout, structureType, triedModProp, triedModNs, triedDel, triedAuth)
  }

  /**
    * Extracts the configuration for a OneDrive server from the given
    * parameters, handling errors and updating the map with parameters.
    *
    * @param driveID       the OneDrive drive ID
    * @param timeout       the timeout for requests
    * @param parameters    the parameters object
    * @param structureType the structure type
    * @param ec            the execution context
    * @param consoleReader the object for reading from the console
    * @return a ''Future'' with the extracted configuration and updated
    *         ''ParameterContext''
    */
  private def extractOneDriveConfig(driveID: String, timeout: Timeout, parameters: Parameters,
                                    structureType: StructureType)
                                   (implicit ec: ExecutionContext, consoleReader: ConsoleReader):
  Future[(OneDriveConfig, ParameterContext)] =
    extractConfig(oneDriveConfigProcessor(driveID, timeout, structureType), parameters)

  /**
    * Returns a ''CliProcessor'' that extracts the configuration for a OneDrive
    * server from the current command line arguments.
    *
    * @param driveID       the OneDrive drive ID
    * @param timeout       the timeout for requests
    * @param structureType the structure type
    * @return the ''CliProcessor'' for the OneDrive configuration
    */
  private def oneDriveConfigProcessor(driveID: String, timeout: Timeout, structureType: StructureType):
  CliProcessor[Try[OneDriveConfig]] = {
    val keyPath = structureType.configPropertyName(PropOneDrivePath)
    val keyChunkSize = structureType.configPropertyName(PropOneDriveUploadChunkSize)
    for {
      triedChunkSize <- asMandatory(intOptionValue(keyChunkSize,
        Some(OneDriveConfig.DefaultUploadChunkSizeMB)))
      triedPath <- asMandatory(stringOptionValue(keyPath))
      triedServer <- stringOptionValue(structureType.configPropertyName(PropOneDriveServer))
      triedAuth <- authConfigProcessor(structureType)
    } yield createOneDriveConfig(driveID, timeout, triedPath, triedChunkSize, triedServer, triedAuth)
  }

  /**
    * Returns a ''CliProcessor'' for obtaining the password of the Dav server.
    * The password is mandatory; if it is not specified in the arguments, it is
    * read from the console.
    *
    * @param structureType the structure type
    * @return the ''CliProcessor'' for the Dav password
    */
  private def davPasswordOption(structureType: StructureType): CliProcessor[Try[String]] = {
    val prop = structureType.configPropertyName(PropDavPassword)
    optionValue(prop)
      .fallback(consoleReaderValue(prop, password = true))
      .single
      .mandatory
  }

  /**
    * Returns a ''CliProcessor'' for obtaining the authentication
    * configuration. The processor creates a concrete implementation of the
    * [[AuthConfig]] trait depending on the properties that are specified.
    *
    * @param structureType the structure type
    * @return the ''CliProcessor'' for the auth config
    */
  private def authConfigProcessor(structureType: StructureType): CliProcessor[Try[AuthConfig]] = {
    val procBasicDefined = isDefinedProcessor(structureType.configPropertyName(PropDavUser))
    val procOAuthDefined = isDefinedProcessor(structureType.configPropertyName(
      OAuthParameterManager.NameOptionName))
    val condNoAuth = conditionalValue(procOAuthDefined, ifProc = constantOptionValue(GroupOAuth),
      elseProc = constantOptionValue(GroupNoAuth))
    val groupSelector: CliProcessor[Try[String]] =
      conditionalValue(procBasicDefined, ifProc = constantOptionValue(GroupBasicAuth), elseProc = condNoAuth)
        .single.mandatory
    val groupMap = Map[String, CliProcessor[OptionValue[AuthConfig]]](
      GroupBasicAuth -> basicAuthProcessor(structureType),
      GroupOAuth -> oauthConfigProcessor(structureType),
      GroupNoAuth -> constantOptionValue(NoAuth))
    conditionalGroupValue(groupSelector, groupMap).single.mandatory
  }

  /**
    * Returns a ''CliProcessor'' for obtaining the basic auth configuration.
    *
    * @param structureType the structure type
    * @return the ''CliProcessor'' for the basic auth config
    */
  private def basicAuthProcessor(structureType: StructureType): CliProcessor[OptionValue[AuthConfig]] = {
    val keyUser = structureType.configPropertyName(PropDavUser)
    val procUser = optionValue(keyUser)
      .single
      .mandatory
    for {
      triedUser <- procUser
      triedPassword <- davPasswordOption(structureType)
    } yield createBasicAuthConfig(triedUser, triedPassword)
  }

  /**
    * Returns a ''CliProcessor'' for obtaining the OAuth configuration.
    *
    * @param structureType the structure type
    * @return the ''CliProcessor'' for the OAuth configuration
    */
  private def oauthConfigProcessor(structureType: StructureType): CliProcessor[OptionValue[AuthConfig]] = {
    OAuthParameterManager.storageConfigProcessor(needPassword = true,
      prefix = OptionPrefix + structureType.name)
      .map { triedConfig =>
        triedConfig map (config => Iterable(config.asInstanceOf[AuthConfig]))
      }
  }

  /**
    * Creates a ''DavConfig'' object from the given components. Errors are
    * aggregated in the resulting ''Try''.
    *
    * @param uri                       the URI of the Dav server
    * @param timeout                   the timeout for requests
    * @param structureType             the structure type
    * @param triedOptModifiedProp      the component for the modified property
    * @param triedOptModifiedNamespace the component for the modified namespace
    * @param triedDelBeforeOverride    the component for the delete before
    *                                  override flag
    * @param triedAuthConfig           the component for the auth config
    * @return a ''Try'' with the configuration for a Dav server
    */
  private def createDavConfig(uri: String, timeout: Timeout, structureType: StructureType,
                              triedOptModifiedProp: Try[Option[String]],
                              triedOptModifiedNamespace: Try[Option[String]],
                              triedDelBeforeOverride: Try[Boolean],
                              triedAuthConfig: Try[AuthConfig]): Try[DavConfig] = {
    val triedUri = ParameterManager.paramTry(structureType.configPropertyName(PropDavUri))(Uri(uri))
    ParameterManager.createRepresentation(triedUri, triedOptModifiedProp, triedOptModifiedNamespace,
      triedDelBeforeOverride, triedAuthConfig)(DavConfig(_, _, _, _, timeout, _))
  }

  /**
    * Creates a ''OneDriveConfig'' object from the given components. Errors are
    * aggregated in the resulting ''Try''.
    *
    * @param driveID         the OneDrive drive ID
    * @param timeout         the timeout for requests
    * @param triedPath       the sync path component
    * @param triedChunkSize  the upload chunk size component
    * @param triedServerUri  the server URI component
    * @param triedAuthConfig the component for auth config
    * @return a ''Try'' with the OneDrive configuration
    */
  private def createOneDriveConfig(driveID: String, timeout: Timeout,
                                   triedPath: Try[String], triedChunkSize: Try[Int],
                                   triedServerUri: Try[Option[String]],
                                   triedAuthConfig: Try[AuthConfig]): Try[OneDriveConfig] =
    ParameterManager.createRepresentation(triedPath, triedChunkSize, triedAuthConfig,
      triedServerUri)(OneDriveConfig(driveID, _, _, timeout, _, _))

  /**
    * Creates an ''OptionValue'' with a ''BasicAuthConfig'' based on the given
    * components.
    *
    * @param triedUser     the user component
    * @param triedPassword the password component
    * @return the option value for the ''BasicAuthConfig''
    */
  private def createBasicAuthConfig(triedUser: Try[String], triedPassword: Try[String]):
  OptionValue[BasicAuthConfig] =
    ParameterManager.createRepresentation(triedUser, triedPassword) { (usr, pwd) =>
      List(BasicAuthConfig(usr, Secret(pwd)))
    }

  /**
    * Creates the factory for creating HTTP actors for interacting with an HTTP
    * server based on the given configuration. Depending on the authentication
    * scheme configured, a different factory has to be used.
    *
    * @param requestActorProps ''Props'' to create the HTTP actor
    * @param httpConfig        the HTTP configuration
    * @param storageService    the storage service to be used
    * @param ec                the execution context
    * @param system            the actor system
    * @return a ''Future'' with the factory for HTTP actors
    */
  private def createHttpActorFactory(requestActorProps: Props, httpConfig: HttpConfig,
                                     storageService: OAuthStorageService[OAuthStorageConfig, OAuthConfig,
                                       Secret, OAuthTokenData])
                                    (implicit ec: ExecutionContext, system: ActorSystem):
  Future[HttpActorFactory] =
    httpConfig.authConfig match {
      case storageConfig: OAuthStorageConfig =>
        for {oauthConfig <- storageService.loadConfig(storageConfig)
             secret <- storageService.loadClientSecret(storageConfig)
             tokens <- storageService.loadTokens(storageConfig)
             } yield new OAuthHttpActorFactory(requestActorProps, storageConfig, oauthConfig, secret, tokens)
      case _ =>
        Future.successful(new BasicAuthHttpActorFactory(requestActorProps))
    }
}

/**
  * A factory class for creating the single components of a sync stream based
  * on command line arguments.
  *
  * This factory handles the construction of the parts of the sync stream that
  * depend on the types of the structures to be synced, as identified by the
  * concrete URIs for the source and destination structures.
  *
  * The usage scenario is that the command line arguments have already been
  * pre-processed. The URIs representing the source and destination structures
  * determine, which additional parameters are required to fully define these
  * structures. These parameters are extracted and removed from the
  * ''Parameters'' object. That way a verification of all input parameters is
  * possible.
  *
  * @param oauthStorageService the service for storing OAuth data
  */
class SyncComponentsFactory(oauthStorageService: OAuthStorageService[OAuthStorageConfig, OAuthConfig,
  Secret, OAuthTokenData]) {
  /**
    * Creates a new instance of ''SyncComponentsFactory'' with default
    * dependencies.
    */
  def this() = this(OAuthStorageServiceImpl)

  import SyncComponentsFactory._

  /**
    * Creates a factory for creating the stream components related to the
    * source structure of the sync process. The passed in parameters are
    * processed in order to create the factory and updated by removing the
    * parameters consumed.
    *
    * @param uri           the URI defining the source structure
    * @param timeout       a timeout when applying a sync operation
    * @param parameters    the object with command line parameters
    * @param system        the actor system
    * @param ec            the execution context
    * @param consoleReader the object for reading from the console
    * @return updated parameters and the factory for creating source components
    */
  def createSourceComponentsFactory(uri: String, timeout: Timeout, parameters: Parameters)
                                   (implicit system: ActorSystem, ec: ExecutionContext,
                                    consoleReader: ConsoleReader):
  Future[(Parameters, SourceComponentsFactory)] = uri match {
    case RegDavUri(davUri) =>
      for {(config, nextParamCtx) <- extractDavConfig(davUri, timeout, parameters, SourceStructureType)
           httpFactory <- createHttpActorFactory(HttpRequestActor(davUri), config, oauthStorageService)
           } yield (nextParamCtx.parameters, new DavComponentsSourceFactory(config, httpFactory))
    case RegOneDriveID(driveID) =>
      for {(config, nextParamCtx) <- extractOneDriveConfig(driveID, timeout, parameters, SourceStructureType)
           httpFactory <- createHttpActorFactory(HttpMultiHostRequestActor(OneDriveHostCacheSize, 1),
             config, oauthStorageService)
           } yield (nextParamCtx.parameters, new OneDriveComponentsSourceFactory(config, httpFactory))
    case _ =>
      extractLocalFsConfig(uri, parameters, SourceStructureType)
        .map(t => (t._2.parameters, new LocalFsSourceComponentsFactory(t._1)))
  }

  /**
    * Creates a factory for creating the stream components related to the
    * destination structure of the sync process. The passed in parameters are
    * processed in order to create the factory and updated by removing the
    * parameters consumed.
    *
    * @param uri           the URI defining the source structure
    * @param timeout       a timeout when applying a sync operation
    * @param parameters    the object with command line parameters
    * @param system        the actor system
    * @param ec            the execution context
    * @param consoleReader the object for reading from the console
    * @return updated parameters and the factory for creating dest components
    */
  def createDestinationComponentsFactory(uri: String, timeout: Timeout, parameters: Parameters)
                                        (implicit system: ActorSystem, ec: ExecutionContext,
                                         consoleReader: ConsoleReader):
  Future[(Parameters, DestinationComponentsFactory)] = uri match {
    case RegDavUri(davUri) =>
      for {(config, nextParamCtx) <- extractDavConfig(davUri, timeout, parameters, DestinationStructureType)
           httpFactory <- createHttpActorFactory(HttpRequestActor(davUri), config, oauthStorageService)
           } yield (nextParamCtx.parameters, new DavComponentsDestinationFactory(config, httpFactory))
    case RegOneDriveID(driveID) =>
      for {(config, nextParamCtx) <- extractOneDriveConfig(driveID, timeout, parameters, DestinationStructureType)
           httpFactory <- createHttpActorFactory(HttpMultiHostRequestActor(OneDriveHostCacheSize, 1),
             config, oauthStorageService)
           } yield (nextParamCtx.parameters, new OneDriveComponentsDestinationFactory(config, httpFactory))
    case _ =>
      extractLocalFsConfig(uri, parameters, DestinationStructureType)
        .map(t => (t._2.parameters, new LocalFsDestinationComponentsFactory(t._1, timeout)))
  }
}
