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

package com.github.sync.http.oauth

import java.nio.file.Path

import akka.Done
import akka.actor.ActorSystem

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
trait OAuthStorageService[STORAGE_CONFIG, CONFIG, CLIENT_SECRET, TOKENS] {
  /**
    * Saves the given OAuth configuration according to the given storage
    * configuration.
    *
    * @param storageConfig the storage configuration
    * @param config        the OAuth configuration to be stored
    * @param ec            the execution context
    * @param system        the actor system
    * @return a future indicating the success of this operation
    */
  def saveConfig(storageConfig: STORAGE_CONFIG, config: CONFIG)
                (implicit ec: ExecutionContext, system: ActorSystem): Future[Done]

  /**
    * Loads the ''OAuthConfig'' defined by the given storage config. If
    * successful, it can be obtained from the resulting future.
    *
    * @param storageConfig the storage configuration
    * @param ec            the execution context
    * @param system        the actor system
    * @return a ''Future'' with the ''OAuthConfig''
    */
  def loadConfig(storageConfig: STORAGE_CONFIG)
                (implicit ec: ExecutionContext, system: ActorSystem): Future[CONFIG]

  /**
    * Saves the given OAuth client secret according to the given storage
    * configuration. As the secret is sensitive information, it is encrypted if
    * a password is provided in the storage configuration.
    *
    * @param storageConfig the storage configuration
    * @param secret        the OAuth client secret to be saved
    * @param ec            the execution context
    * @param system        the actor system
    * @return a ''Future'' indicating the success of this operation
    */
  def saveClientSecret(storageConfig: STORAGE_CONFIG, secret: CLIENT_SECRET)
                      (implicit ec: ExecutionContext, system: ActorSystem): Future[Done]

  /**
    * Loads the OAuth client secret defined by the given storage configuration.
    *
    * @param storageConfig the storage configuration
    * @param ec            the execution context
    * @param system        the actor system
    * @return a ''Future'' with the OAuth client secret
    */
  def loadClientSecret(storageConfig: STORAGE_CONFIG)
                      (implicit ec: ExecutionContext, system: ActorSystem): Future[CLIENT_SECRET]

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
    * Loads token information defined by the given storage configuration.
    *
    * @param storageConfig the storage configuration
    * @param ec            the execution context
    * @param system        the actor system
    * @return a ''Future'' with the token material that has been loaded
    */
  def loadTokens(storageConfig: STORAGE_CONFIG)
                (implicit ec: ExecutionContext, system: ActorSystem): Future[TOKENS]

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
}
