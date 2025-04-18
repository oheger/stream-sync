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

package com.github.sync.oauth

import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

/**
  * A trait defining a service that supports loading and saving information
  * related to OAuth.
  *
  * The service has functions to deal with basic properties of an OAuth
  * identity provider, but also with dynamic data like tokens.
  *
  * @tparam STORAGE_CONFIG the type representing the storage configuration
  * @tparam CONFIG         the type representing the OAuth configuration
  * @tparam CLIENT_SECRET  the type representing the client secret
  * @tparam TOKENS         the type representing token data
  */
trait OAuthStorageService[STORAGE_CONFIG, CONFIG, CLIENT_SECRET, TOKENS]:
  /**
    * Saves all data stored in the given OAuth configuration in a way as
    * defined by the storage configuration. The data contained in the OAuth
    * configuration is written into multiple files; as some of them store
    * sensitive information (tokens or secrets), they are encrypted if the
    * storage configuration defines a password.
    *
    * @param storageConfig the storage configuration
    * @param config        the OAuth configuration to be stored
    * @param ec            the execution context
    * @param system        the actor system
    * @return a future indicating the success of this operation
    */
  def saveIdpConfig(storageConfig: STORAGE_CONFIG, config: CONFIG)
                   (implicit ec: ExecutionContext, system: ActorSystem): Future[Done]

  /**
    * Loads the OAuth configuration defined by the given storage configuration.
    * This includes all the information required for the interaction with the
    * IDP.
    *
    * @param storageConfig the storage configuration
    * @param ec            the execution context
    * @param system        the actor system
    * @return a ''Future'' with the OAuth configuration
    */
  def loadIdpConfig(storageConfig: STORAGE_CONFIG)
                   (implicit ec: ExecutionContext, system: ActorSystem): Future[CONFIG]

  /**
    * Saves the given OAuth token data according to the given storage
    * configuration. As this is sensitive information, it is encrypted if a
    * password is provided in the storage configuration.
    *
    * @param storageConfig the storage configuration
    * @param tokens        the object with token data
    * @param ec            the execution context
    * @param system        the actor system
    * @return a ''Future'' indicating the success of this operation
    */
  def saveTokens(storageConfig: STORAGE_CONFIG, tokens: TOKENS)
                (implicit ec: ExecutionContext, system: ActorSystem): Future[Done]

  /**
    * Removes all files related to a specific identity provider defined by the
    * given storage configuration. An implementation should remove all possible
    * files, e.g. for tokens, secrets, etc. The resulting future contains a
    * list with the paths that have been removed.
    *
    * @param storageConfig the storage configuration
    * @param ec            the eecution context
    * @return a ''Future'' with the paths that have been removed
    */
  def removeStorage(storageConfig: STORAGE_CONFIG)(implicit ec: ExecutionContext): Future[List[Path]]
