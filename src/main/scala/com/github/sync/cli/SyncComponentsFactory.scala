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

package com.github.sync.cli

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.stream.scaladsl.{Flow, Source}
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthTokenData
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes.{ElementSourceFactory, FsElement, SyncOperation}
import com.github.sync.cli.SyncParameterManager.SyncConfig
import com.github.sync.cli.SyncStructureConfig.{DavStructureConfig, FsStructureConfig, OneDriveStructureConfig}
import com.github.sync.http._
import com.github.sync.http.oauth.{IDPConfig, OAuthStorageService, OAuthStorageServiceImpl}
import com.github.sync.local.LocalFsConfig
import com.github.sync.onedrive.OneDriveConfig
import com.github.sync.webdav.DavConfig

import java.nio.file.Paths
import scala.concurrent.{ExecutionContext, Future}

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
                                     storageService: OAuthStorageService[OAuthStorageConfig, IDPConfig,
                                       Secret, OAuthTokenData])
                                    (implicit ec: ExecutionContext, system: ActorSystem):
  Future[HttpActorFactory] =
    httpConfig.authConfig match {
      case storageConfig: OAuthStorageConfig =>
        storageService.loadIdpConfig(storageConfig) map { config =>
          new OAuthHttpActorFactory(requestActorProps, storageConfig, config)
        }
      case _: BasicAuthConfig =>
        Future.successful(new BasicAuthHttpActorFactory(requestActorProps))
      case NoAuth =>
        Future.successful(new NoAuthHttpActorFactory(requestActorProps))
    }

  /**
    * Creates a ''DavConfig'' from the sync config, the structure URI, and the
    * ''DavStructureConfig''.
    *
    * @param config       the ''SyncConfig''
    * @param davUri       the URI of the structure
    * @param structConfig the ''DavStructureConfig''
    * @return the resulting ''DavConfig''
    */
  private def davConfigFromStructureConfig(config: SyncConfig, davUri: String, structConfig: DavStructureConfig):
  DavConfig = {
    DavConfig(davUri, structConfig.optLastModifiedProperty,
      structConfig.optLastModifiedNamespace, structConfig.deleteBeforeOverride,
      config.timeout, structConfig.authConfig)
  }

  /**
    * Creates a ''OneDriveConfig'' from the sync config, the drive ID, and
    * the ''OneDriveStructureConfig''.
    *
    * @param config       the '' SyncConfig''
    * @param driveID      the OneDrive ID
    * @param structConfig the ''OneDriveStructureConfig''
    * @return the resulting ''OneDriveConfig''
    */
  private def oneDriveConfigFromStructureConfig(config: SyncConfig, driveID: String,
                                                structConfig: OneDriveStructureConfig): OneDriveConfig = {
    OneDriveConfig(driveID, structConfig.syncPath,
      structConfig.optUploadChunkSizeMB getOrElse OneDriveConfig.DefaultUploadChunkSizeMB,
      config.timeout, structConfig.authConfig, structConfig.optServerUri)
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
  * parsed, and a corresponding configuration object has been created. Based on
  * this configuration object, the correct components can be constructed.
  *
  * @param oauthStorageService the service for storing OAuth data
  */
class SyncComponentsFactory(oauthStorageService: OAuthStorageService[OAuthStorageConfig, IDPConfig,
  Secret, OAuthTokenData]) {
  /**
    * Creates a new instance of ''SyncComponentsFactory'' with default
    * dependencies.
    */
  def this() = this(OAuthStorageServiceImpl)

  import SyncComponentsFactory._

  /**
    * Creates a factory for creating the stream components related to the
    * source structure of the sync process. All the necessary information is
    * contained in the passed in configuration object.
    *
    * @param config the configuration for the sync process
    * @param system the actor system
    * @param ec     the execution context
    * @return a future with the factory for creating source components
    */
  def createSourceComponentsFactory(config: SyncConfig)(implicit system: ActorSystem, ec: ExecutionContext):
  Future[SourceComponentsFactory] = config.srcConfig match {
    case FsStructureConfig(optTimeZone) =>
      val fsConfig = LocalFsConfig(Paths get config.srcUri, optTimeZone)
      Future.successful(new LocalFsSourceComponentsFactory(fsConfig))

    case davStructConfig: DavStructureConfig =>
      createDavComponentsFactory(config, config.srcUri, davStructConfig) { (davConfig, actorFactory) =>
        new DavComponentsSourceFactory(davConfig, actorFactory)
      }

    case oneStructConfig: OneDriveStructureConfig =>
      createOneDriveComponentsFactory(config, config.srcUri, oneStructConfig) { (oneConfig, actorFactory) =>
        new OneDriveComponentsSourceFactory(oneConfig, actorFactory)
      }
  }

  /**
    * Creates a factory for creating the stream components related to the
    * destination structure of the sync process. All the necessary information
    * is contained in the passed in configuration object.
    *
    * @param config the configuration for the sync process
    * @param system the actor system
    * @param ec     the execution context
    * @return a future with the factory for creating destination components
    */
  def createDestinationComponentsFactory(config: SyncConfig)(implicit system: ActorSystem, ec: ExecutionContext):
  Future[DestinationComponentsFactory] = config.dstConfig match {
    case FsStructureConfig(optTimeZone) =>
      val fsConfig = LocalFsConfig(Paths get config.dstUri, optTimeZone)
      Future.successful(new LocalFsDestinationComponentsFactory(fsConfig, config.timeout))

    case davStructConfig: DavStructureConfig =>
      createDavComponentsFactory(config, config.dstUri, davStructConfig) { (davConfig, actorFactory) =>
        new DavComponentsDestinationFactory(davConfig, actorFactory)
      }

    case oneStructConfig: OneDriveStructureConfig =>
      createOneDriveComponentsFactory(config, config.dstUri, oneStructConfig) { (oneConfig, actorFactory) =>
        new OneDriveComponentsDestinationFactory(oneConfig, actorFactory)
      }
  }

  /**
    * Handles the creation of a components factory for a DAV structure from the
    * parameters passed in.
    *
    * @param config       the sync configuration
    * @param uri          the URI of the DAV structure
    * @param structConfig the config for the DAV structure
    * @param fCreate      a function to create the resulting factory
    * @param system       the actor system
    * @param ec           the execution context
    * @tparam T the type of the resulting factory
    * @return a future with the resulting factory
    */
  private def createDavComponentsFactory[T](config: SyncConfig, uri: String, structConfig: DavStructureConfig)
                                           (fCreate: (DavConfig, HttpActorFactory) => T)
                                           (implicit system: ActorSystem, ec: ExecutionContext): Future[T] = {
    val futDavUri = uri match {
      case SyncStructureConfig.RegDavUri(uri) => Future.successful(uri)
      case _ =>
        // Paranoia check; this cannot happen in practice
        Future.failed(new IllegalArgumentException(s"No a valid WebDav URI: '$uri'"))
    }
    for {
      davUri <- futDavUri
      davConfig <- Future.successful(davConfigFromStructureConfig(config, davUri, structConfig)
      )
      factory <- createHttpActorFactory(HttpRequestActor(davUri), davConfig, oauthStorageService)
        .map(factory => fCreate(davConfig, factory))
    } yield factory
  }

  /**
    * Handles the creation of a components factory for a OneDrive structure
    * from the parameters passed in.
    *
    * @param config       the sync configuration
    * @param uri          the URI of the OneDrive structure
    * @param structConfig the config of the OneDrive structure
    * @param fCreate      a function to create the resulting factory
    * @param system       the actor system
    * @param ec           the execution context
    * @tparam T the type of the resulting factory
    * @return a future with the resulting factory
    */
  private def createOneDriveComponentsFactory[T](config: SyncConfig, uri: String,
                                                 structConfig: OneDriveStructureConfig)
                                                (fCreate: (OneDriveConfig, HttpActorFactory) => T)
                                                (implicit system: ActorSystem, ec: ExecutionContext): Future[T] = {
    val futDriveID = uri match {
      case SyncStructureConfig.RegOneDriveID(id) => Future.successful(id)
      case _ =>
        // Paranoia check; this cannot happen in practice
        Future.failed(new IllegalArgumentException(s"Not a valid OneDrive URI: '$uri'"))
    }
    for {
      driveID <- futDriveID
      oneConfig <- Future.successful(oneDriveConfigFromStructureConfig(config, driveID, structConfig))
      factory <- createHttpActorFactory(HttpMultiHostRequestActor(OneDriveHostCacheSize, 1),
        oneConfig, oauthStorageService) map (fCreate(oneConfig, _))
    } yield factory
  }
}
