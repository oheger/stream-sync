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

import com.github.cloudfiles.core.http.RetryAfterExtension
import com.github.cloudfiles.core.http.RetryExtension
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, Spawner}
import com.github.sync.SyncTypes.{FsElement, SyncOperation, SyncOperationResult}
import com.github.sync.cli.SyncParameterManager.{CryptConfig, CryptMode, SyncConfig}
import com.github.sync.cli.SyncSetup.{AuthSetupFunc, ProtocolFactorySetupFunc}
import com.github.sync.protocol.SyncProtocol
import com.github.sync.protocol.config.StructureCryptConfig
import com.github.sync.stream.{ProtocolOperationHandler, ProtocolOperationHandlerStage}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.stream.{KillSwitch, KillSwitches, SharedKillSwitch}
import org.apache.pekko.util.Timeout

import scala.concurrent.{ExecutionContext, Future}

object SyncProtocolHolder:
  /**
    * A factory function for creating a [[SyncProtocolHolder]] instance with
    * the protocols to use for the current sync process. This function
    * evaluates the configuration objects provided and creates suitable protocol
    * objects for them. With these, a new holder instance is created.
    *
    * @param syncConfig        the config for the sync process
    * @param spawner           the spawner
    * @param resolverFunc      the function to resolve credentials
    * @param authSetupFunc     the function to set up authentication
    * @param protocolSetupFunc the function to set up the protocol factory
    * @param system            the actor system
    * @param ec                the execution context
    * @return a ''Future'' with the resulting ''SyncProtocolHolder''
    */
  def apply(syncConfig: SyncConfig, spawner: Spawner)
           (resolverFunc: CredentialsResolver.ResolverFunc)
           (authSetupFunc: AuthSetupFunc)
           (protocolSetupFunc: ProtocolFactorySetupFunc)
           (using system: ActorSystem[?], ec: ExecutionContext): Future[SyncProtocolHolder] =
    val killSwitch = KillSwitches.shared("oauth-token-refresh")
    val futSenderConfigSrc = createHttpSenderConfig(authSetupFunc, syncConfig.srcConfig, killSwitch)
    val futSenderConfigDst = createHttpSenderConfig(authSetupFunc, syncConfig.dstConfig, killSwitch)
    val futSrcCryptConfig = createStructureCryptConfig(
      syncConfig.cryptConfig,
      syncConfig.cryptConfig.srcPassword,
      syncConfig.cryptConfig.srcCryptMode
    )(resolverFunc)
    val futDstCryptConfig = createStructureCryptConfig(
      syncConfig.cryptConfig,
      syncConfig.cryptConfig.dstPassword,
      syncConfig.cryptConfig.dstCryptMode
    )(resolverFunc)

    for
      senderConfigSrc <- futSenderConfigSrc
      senderConfigDst <- futSenderConfigDst
      srcCryptConfig <- futSrcCryptConfig
      dstCryptConfig <- futDstCryptConfig
    yield
      val srcProtocolFactory =
        protocolSetupFunc(syncConfig.srcConfig.structureConfig, syncConfig, senderConfigSrc, spawner)
      val srcProtocol = srcProtocolFactory.createProtocol(syncConfig.srcUri, srcCryptConfig)
      val dstProtocolFactory =
        protocolSetupFunc(syncConfig.dstConfig.structureConfig, syncConfig, senderConfigDst, spawner)
      val dstProtocol = dstProtocolFactory.createProtocol(syncConfig.dstUri, dstCryptConfig)
      new SyncProtocolHolder(srcProtocol, dstProtocol, killSwitch)

  /**
    * Creates the configuration for the HTTP request sender actor to be used
    * for a structure based on the authentication config for this structure.
    *
    * @param authSetupFunc   the function to set up authentication
    * @param structureConfig the configuration for this structure
    * @param killSwitch      the kill switch for a failed token refresh
    * @param ec              the execution context
    * @return a ''Future'' with the HTTP actor configuration
    */
  private[cli] def createHttpSenderConfig(authSetupFunc: AuthSetupFunc,
                                          structureConfig: SyncCliStructureConfig.StructureSyncConfig,
                                          killSwitch: KillSwitch)
                                         (using ec: ExecutionContext): Future[HttpRequestSenderConfig] =
    authSetupFunc(structureConfig.authConfig, killSwitch) map { authConfig =>
      val optRetryAfterConfig = structureConfig.optRetryConfig.map: retry =>
        RetryAfterExtension.RetryAfterConfig(retry.minDelay)
      val optRetryConfig = structureConfig.optRetryConfig.map: retry =>
        val backoffConfig = RetryExtension.BackoffConfig(retry.minDelay, retry.maxDelay)
        RetryExtension.RetryConfig(optMaxTimes = Some(retry.maxRetries), optBackoff = Some(backoffConfig))

      HttpRequestSenderConfig(
        authConfig = authConfig,
        retryAfterConfig = optRetryAfterConfig,
        retryConfig = optRetryConfig
      )
    }

  /**
    * Creates a [[StructureCryptConfig]] from the passed in parameters. The
    * provided password is resolved using the resolver function if it is 
    * present.
    *
    * @param cryptConfig the original ''CryptConfig''
    * @param password    the optional password
    * @param cryptMode   the ''CryptMode''
    * @param ec          the execution context
    * @return a [[Future]] the resulting [[StructureCryptConfig]]
    */
  private def createStructureCryptConfig(cryptConfig: CryptConfig,
                                         password: Option[String],
                                         cryptMode: CryptMode.Value)
                                        (resolverFunc: CredentialsResolver.ResolverFunc)
                                        (using ec: ExecutionContext): Future[StructureCryptConfig] =
    val futResolvedPwd = password match
      case Some(pwd) =>
        resolverFunc(pwd).map(resolved => Some(resolved))
      case None =>
        Future.successful(None)

    futResolvedPwd.map: optPwd =>
      StructureCryptConfig(optPwd, cryptMode == CryptMode.FilesAndNames, cryptConfig.cryptCacheSize)
end SyncProtocolHolder

/**
  * A class that holds the [[SyncProtocol]] objects used by the current sync
  * process.
  *
  * There is a factory function to create an instance with protocol objects
  * created from the configurations for the source and destination structures.
  * From this instance then the components required for the sync process can be
  * obtained, such as sources or handlers.
  *
  * There is also support for cleaning up resources when a sync process
  * completes.
  *
  * Further, the class manages a ''KillSwitch'' that is triggered when an OAuth
  * token refresh operation fails. If this kill switch is integrated into the
  * sync stream, it can be aborted when such an error occurs.
  *
  * @param srcProtocol            the protocol for the source structure
  * @param dstProtocol            the protocol for the destination structure
  * @param oAuthRefreshKillSwitch the kill switch triggered for failed OAuth
  *                               token refresh operations
  * @param system                 the actor system
  */
class SyncProtocolHolder(srcProtocol: SyncProtocol,
                         dstProtocol: SyncProtocol,
                         val oAuthRefreshKillSwitch: SharedKillSwitch)
                        (using system: ActorSystem[?]):
  /**
    * Creates a source for iterating over the elements of the source structure.
    *
    * @return a [[Future]] with the source for the source structure
    */
  def createSourceElementSource(): Future[Source[FsElement, Any]] = srcProtocol.elementSource

  /**
    * Creates a source for iterating over the elements of the destination
    * structure.
    *
    * @return a [[Future]] with the source for the destination structure
    */
  def createDestinationElementSource(): Future[Source[FsElement, Any]] = dstProtocol.elementSource

  /**
    * Creates the flow stage for applying the sync operations against the
    * destination structure.
    *
    * @param syncConfig the config for the sync process
    * @param spawner    an object to create actors
    * @return the apply stage
    */
  def createApplyStage(syncConfig: SyncConfig, spawner: Spawner): Flow[SyncOperation, SyncOperationResult, NotUsed] =
    val protocolHandler = new ProtocolOperationHandler(dstProtocol, srcProtocol)
    implicit val timeout: Timeout = syncConfig.streamConfig.timeout

    ProtocolOperationHandlerStage(protocolHandler, spawner)

  /**
    * Registers a handler at the given ''Future'' that closes the managed
    * protocols when the future completes (either successfully or with a
    * failure). This function should be called to register this handler on the
    * main future of the sync process to make sure that the protocols are
    * released properly at the end of the process.
    *
    * @param future the ''Future'' to register the handler
    * @tparam A the result type of the future
    * @return the ''Future'' with the handler registered
    */
  def registerCloseHandler[A](future: Future[A]): Future[A] =
    future.andThen:
      case _ =>
        srcProtocol.close()
        dstProtocol.close()

  /** The execution context from the actor system in implicit scope. */
  private given executionContext: ExecutionContext = system.executionContext
