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

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.stream.KillSwitch
import akka.{actor => classic}
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthConfig.TokenRefreshNotificationFunc
import com.github.cloudfiles.core.http.auth.{AuthConfig, BasicAuthConfig, NoAuthConfig, OAuthTokenData}
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, Spawner}
import com.github.sync.cli.SyncParameterManager.SyncConfig
import com.github.sync.oauth.{IDPConfig, OAuthStorageService, OAuthStorageServiceImpl, SyncAuthConfig, SyncBasicAuthConfig, SyncOAuthStorageConfig}
import com.github.sync.protocol.SyncProtocolFactory
import com.github.sync.protocol.config.{DavStructureConfig, FsStructureConfig, GoogleDriveStructureConfig, OneDriveStructureConfig, StructureConfig}
import com.github.sync.protocol.gdrive.GoogleDriveProtocolFactory
import com.github.sync.protocol.local.LocalProtocolFactory
import com.github.sync.protocol.onedrive.OneDriveProtocolFactory
import com.github.sync.protocol.webdav.DavProtocolFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * A module providing functions to setup important components required for a
  * sync process.
  *
  * The functions defined here are used to process configuration objects
  * defined via command line arguments and to convert them to active components
  * that take part in the sync process.
  */
object SyncSetup:
  /**
    * A function type for creating a CloudFiles ''AuthConfig'' object based on
    * a ''SyncAuthConfig'' read from the command line. The creation is an
    * asynchronous process, as typically some data files have to be read.
    */
  type AuthSetupFunc = (SyncAuthConfig, KillSwitch) => Future[AuthConfig]

  /**
    * A function for creating a factory for a ''SyncProtocol'' based on the
    * configuration passed via the command line.
    */
  type ProtocolFactorySetupFunc =
    (StructureConfig, SyncConfig, HttpRequestSenderConfig, Spawner) => SyncProtocolFactory

  /**
    * Definition of the concrete service type used to access persistent
    * OAuth-related  configuration data.
    */
  type SyncOAuthStorageService = OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData]

  /**
    * Returns a default setup function for authentication data. This function
    * uses a [[SyncOAuthStorageService]] to load OAuth-related configuration if
    * necessary.
    *
    * @param storageService the service to access the OAuth configuration
    * @param system         the actor system
    * @return the function to setup authentication
    */
  def defaultAuthSetupFunc(storageService: SyncOAuthStorageService = OAuthStorageServiceImpl)
                          (implicit system: ActorSystem[_]): AuthSetupFunc =
    implicit val classicSystem: classic.ActorSystem = system.toClassic
    implicit val executionContext: ExecutionContext = system.executionContext

    (authConfig, killSwitch) =>
      authConfig match
        case SyncBasicAuthConfig(user, password) =>
          Future.successful(BasicAuthConfig(user, password))
        case storageConfig: SyncOAuthStorageConfig =>
          val refreshFunc = createTokenRefreshNotificationFunc(storageService, storageConfig, killSwitch)
          storageService.loadIdpConfig(storageConfig) map (_.oauthConfig.copy(refreshNotificationFunc = refreshFunc))

        case _ =>
          Future.successful(NoAuthConfig)

  /**
    * Creates a function that is invoked when OAuth tokens are refreshed. If
    * successful, the function passes the new tokens to the storage service to
    * persist them. In case of a failure, the sync stream is aborted, as any
    * following operations are expected to fail.
    *
    * @param storageService the service to store token information
    * @param storageConfig  the OAuth storage configuration
    * @param killSwitch     the kill switch to terminate the sync stream
    * @param system         the actor system
    * @param ec             the execution context
    * @return the notification function for a token refresh
    */
  private def createTokenRefreshNotificationFunc(storageService: SyncOAuthStorageService,
                                                 storageConfig: SyncOAuthStorageConfig,
                                                 killSwitch: KillSwitch)
                                                (implicit system: classic.ActorSystem, ec: ExecutionContext):
  TokenRefreshNotificationFunc =
    val refreshFunc: TokenRefreshNotificationFunc =
      case Success(tokens) =>
        storageService.saveTokens(storageConfig, tokens)
      case Failure(exception) =>
        killSwitch.abort(exception)
    refreshFunc

  /**
    * Returns a default setup function for a protocol factory. As some concrete
    * factory implementations require an explicit ''ExecutionContext'' (which
    * is not necessarily the one of the actor system), this object has to be
    * passed in separately.
    *
    * @param system the actor system
    * @param ec     the execution context
    * @return the function to setup a protocol factory
    */
  def defaultProtocolFactorySetupFunc(implicit system: ActorSystem[_], ec: ExecutionContext):
  ProtocolFactorySetupFunc = (structConfig, syncConfig, senderConfig, spawner) => {
    val syncTimeout = syncConfig.streamConfig.timeout
    structConfig match
      case fsConfig: FsStructureConfig =>
        new LocalProtocolFactory(fsConfig, senderConfig, syncTimeout, spawner, ec)
      case davConfig: DavStructureConfig =>
        new DavProtocolFactory(davConfig, senderConfig, syncTimeout, spawner)
      case oneConfig: OneDriveStructureConfig =>
        new OneDriveProtocolFactory(oneConfig, senderConfig, syncTimeout, spawner)
      case googleConfig: GoogleDriveStructureConfig =>
        new GoogleDriveProtocolFactory(googleConfig, senderConfig, syncTimeout, spawner)
  }
