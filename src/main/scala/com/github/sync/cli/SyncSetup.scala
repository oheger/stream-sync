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

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.{actor => classic}
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthConfig.TokenRefreshNotificationFunc
import com.github.cloudfiles.core.http.auth.{AuthConfig, BasicAuthConfig, NoAuthConfig, OAuthTokenData}
import com.github.sync.http.oauth.{IDPConfig, OAuthStorageService, OAuthStorageServiceImpl}
import com.github.sync.http.{SyncAuthConfig, SyncBasicAuthConfig, SyncOAuthStorageConfig}

import scala.concurrent.{ExecutionContext, Future}

/**
  * A module providing functions to setup important components required for a
  * sync process.
  *
  * The functions defined here are used to process configuration objects
  * defined via command line arguments and to convert them to active components
  * that take part in the sync process.
  */
object SyncSetup {
  /**
    * A function type for creating a CloudFiles ''AuthConfig'' object based on
    * a ''SyncAuthConfig'' read from the command line. The creation is an
    * asynchronous process, as typically some data files have to be read.
    */
  type AuthSetupFunc = SyncAuthConfig => Future[AuthConfig]

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
                          (implicit system: ActorSystem[_]): AuthSetupFunc = {
    implicit val classicSystem: classic.ActorSystem = system.toClassic
    implicit val executionContext: ExecutionContext = system.executionContext

    {
      case SyncBasicAuthConfig(user, password) =>
        Future.successful(BasicAuthConfig(user, password))
      case storageConfig: SyncOAuthStorageConfig =>
        val refreshFunc: TokenRefreshNotificationFunc = triedTokens =>
          triedTokens.foreach(storageService.saveTokens(storageConfig, _))
        storageService.loadIdpConfig(storageConfig) map (_.oauthConfig.copy(refreshNotificationFunc = refreshFunc))
      case _ =>
        Future.successful(NoAuthConfig)
    }
  }
}
