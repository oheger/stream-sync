/*
 * Copyright 2018-2026 The Developers Team.
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

import com.github.sync.auth.{CredentialsService, CredentialsServiceImpl}
import com.github.sync.cli.SyncCliStreamConfig.CredentialsConfig
import org.apache.pekko.actor.ActorSystem

import scala.concurrent.{ExecutionContext, Future}

/**
  * An object providing functionality to resolve credentials via a credentials
  * management and the corresponding configuration.
  *
  * The sync application allows looking up credentials from an encrypted
  * storage. If this is enabled, secrets are looked up from this storage if
  * their value starts with a configurable prefix.
  *
  * This object defines a resolver function for that use case. Depending on the
  * command line arguments passed to the sync application, the function either
  * checks for the prefix and performs a lookup via the storage or returns the
  * secret value unchanged.
  */
object CredentialsResolver:
  /**
    * Type alias for the service to load credentials which is used by this
    * object.
    */
  type CredentialsLoader = CredentialsService[CredentialsServiceImpl.CredentialEntry]

  /**
    * A function type to resolve a credential. The function expects the
    * original credential value and returns a [[Future]] with the resolved
    * value. If credentials management is active and the credential's value
    * starts with the configured prefix, it is looked up against the
    * credential storage. If the storage could not be initialized or the
    * credential could not be resolved, a failed [[Future]] is returned.
    */
  type ResolverFunc = String => Future[String]

  /**
    * A special exception class to report a credential that cannot be resolved
    * via the current credential storage.
    *
    * @param key the key of the affected credential
    */
  class UnresolvableCredentialException(val key: String)
    extends RuntimeException(s"Cannot resolve credential '$key'.")

  /**
    * Returns a proper [[ResolverFunc]] for the given optional credentials
    * configuration. The service to access the credentials can also be
    * provided.
    *
    * @param credentialsConfig  the credentials configuration
    * @param credentialsService the service to load credentials
    * @param ec                 the execution context
    * @param system             the actor system
    * @return the function to resolve credentials
    */
  def createResolver(credentialsConfig: Option[CredentialsConfig],
                     credentialsService: => CredentialsLoader = CredentialsServiceImpl)
                    (using ec: ExecutionContext, system: ActorSystem): ResolverFunc =
    credentialsConfig.map(credentialStorageResolver(_, credentialsService)).getOrElse(dummyResolver)

  /**
    * A dummy resolver function that returns the passed in credential
    * unchanged. This is used if no credentials configuration has been
    * specified.
    *
    * @param credential the credential to resolve
    * @return a [[Future]] with the resolved credential
    */
  private def dummyResolver(credential: String): Future[String] = Future.successful(credential)

  /**
    * Returns a [[ResolverFunc]] for a defined [[CredentialsConfig]]. The
    * function tries to load the credential storage based on the passed in
    * config. Credential keys are then resolved against this storage.
    *
    * @param credentialsConfig  the credentials configuration
    * @param credentialsService the service to load the storage
    * @param ec                 the execution context
    * @param system             the actor system
    * @return the resolver function backed by a storage
    */
  private def credentialStorageResolver(credentialsConfig: CredentialsConfig, credentialsService: CredentialsLoader)
                                       (using ec: ExecutionContext, system: ActorSystem): ResolverFunc =
    val futCredentials = credentialsService.loadCredentials(
      credentialsConfig.credentialsFile,
      credentialsConfig.credentialsSecret
    ).map: entries =>
      entries.map(e => e.key -> e.value.secret).toMap

    credential =>
      if credential.startsWith(credentialsConfig.credentialsPrefix) then
        resolveFromStorage(credential.stripPrefix(credentialsConfig.credentialsPrefix), futCredentials)
      else
        Future.successful(credential)

  /**
    * Tries to resolve a credential key from the credential store which is
    * loaded asynchronously. The prefix must have been stripped already.
    *
    * @param credential     the key to resolve
    * @param futCredentials a [[Future]] with the credential information
    * @param ec             the execution context
    * @return a [[Future]] with the resolved value of the credential; this is
    *         failed if the key cannot be resolved
    */
  private def resolveFromStorage(credential: String, futCredentials: Future[Map[String, String]])
                                (using ec: ExecutionContext): Future[String] =
    futCredentials flatMap : credentials =>
      credentials.get(credential).fold(unresolved(credential))(Future.successful)

  /**
    * Returns a failed future with an exception that indicates that a
    * credential could not be resolved.
    *
    * @param credential the key of the affected credential
    * @return the future for the failed resolve operation
    */
  private def unresolved(credential: String): Future[String] =
    Future.failed(new UnresolvableCredentialException(credential))