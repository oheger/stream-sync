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

import akka.actor.ActorSystem
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthConfig.TokenRefreshNotificationFunc
import com.github.cloudfiles.core.http.auth.{AuthConfig, BasicAuthConfig, NoAuthConfig, OAuthTokenData}
import com.github.sync.http.{SyncAuthConfig, SyncBasicAuthConfig, SyncOAuthStorageConfig}
import com.github.sync.http.oauth.{IDPConfig, OAuthStorageService}

import scala.concurrent.{ExecutionContext, Future}

/**
  * A helper class that converts a sync-specific authentication configuration
  * to an equivalent configuration to be passed to the CloudFiles API.
  *
  * The configuration classes used internally by the Sync CLI are rather
  * similar to the ones of CloudFiles - except for the OAuth configuration.
  * The CLI-based configuration basically refers to (potentially encrypted)
  * files, which store the IDP-related information. These files have to be
  * loaded first. Therefore, the API of this class uses a ''Future'' as result
  * type, to do the loading in background and to handle possible I/O
  * exceptions.
  *
  * @param storageService the service for loading the OAuth configuration
  * @param ec             the execution context
  * @param system         the actor system
  */
class AuthFactory(storageService: OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData])
                 (implicit ec: ExecutionContext, system: ActorSystem) {
  def createAuthConfig(syncAuthConfig: SyncAuthConfig): Future[AuthConfig] =
    syncAuthConfig match {
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
