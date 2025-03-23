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

package com.github.sync.cli

import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, Spawner}
import com.github.sync.SyncTypes.{FsElement, SyncOperation, SyncOperationResult}
import com.github.sync.cli.SyncParameterManager.{CryptConfig, CryptMode, SyncConfig}
import com.github.sync.cli.SyncSetup.{AuthSetupFunc, ProtocolFactorySetupFunc}
import com.github.sync.oauth.SyncAuthConfig
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
    * evaluates the configuration objects provided an creates suitable protocol
    * objects for them. With these, a new holder instance is created.
    *
    * @param syncConfig        the config for the sync process
    * @param spawner           the spawner
    * @param authSetupFunc     the function to setup authentication
    * @param protocolSetupFunc the function to setup the protocol factory
    * @param system            the actor system
    * @return a ''Future'' with the resulting ''SyncProtocolHolder''
    */
  def apply(syncConfig: SyncConfig, spawner: Spawner)(authSetupFunc: AuthSetupFunc)
           (protocolSetupFunc: ProtocolFactorySetupFunc)
           (implicit system: ActorSystem[_]): Future[SyncProtocolHolder] =
    implicit val ec: ExecutionContext = system.executionContext
    val killSwitch = KillSwitches.shared("oauth-token-refresh")
    val futSenderConfigSrc = createHttpSenderConfig(authSetupFunc, syncConfig.srcConfig.authConfig, killSwitch)
    val futSenderConfigDst = createHttpSenderConfig(authSetupFunc, syncConfig.dstConfig.authConfig, killSwitch)

    for
      senderConfigSrc <- futSenderConfigSrc
      senderConfigDst <- futSenderConfigDst
    yield
      val srcProtocolFactory =
        protocolSetupFunc(syncConfig.srcConfig.structureConfig, syncConfig, senderConfigSrc, spawner)
      val srcCryptConfig = createStructureCryptConfig(syncConfig.cryptConfig, syncConfig.cryptConfig.srcPassword,
        syncConfig.cryptConfig.srcCryptMode)
      val srcProtocol = srcProtocolFactory.createProtocol(syncConfig.srcUri, srcCryptConfig)
      val dstProtocolFactory =
        protocolSetupFunc(syncConfig.dstConfig.structureConfig, syncConfig, senderConfigDst, spawner)
      val dstCryptConfig = createStructureCryptConfig(syncConfig.cryptConfig, syncConfig.cryptConfig.dstPassword,
        syncConfig.cryptConfig.dstCryptMode)
      val dstProtocol = dstProtocolFactory.createProtocol(syncConfig.dstUri, dstCryptConfig)
      new SyncProtocolHolder(srcProtocol, dstProtocol, killSwitch)

  /**
    * Creates the configuration for the HTTP request sender actor to be used
    * for a structure based on the authentication config for this structure.
    *
    * @param authSetupFunc  the function to setup authentication
    * @param syncAuthConfig the ''SyncAuthConfig'' for this structure
    * @param killSwitch     the kill switch for a failed token refresh
    * @param ec             the execution context
    * @return a ''Future'' with the HTTP actor configuration
    */
  private def createHttpSenderConfig(authSetupFunc: AuthSetupFunc, syncAuthConfig: SyncAuthConfig,
                                     killSwitch: KillSwitch)(implicit ec: ExecutionContext):
  Future[HttpRequestSenderConfig] =
    authSetupFunc(syncAuthConfig, killSwitch) map { authConfig => HttpRequestSenderConfig(authConfig = authConfig) }

  /**
    * Create a ''StructureCryptConfig'' from the passed in parameters.
    *
    * @param cryptConfig the original ''CryptConfig''
    * @param password    the optional password
    * @param cryptMode   the ''CryptMode''
    * @return the resulting ''StructureCryptConfig''
    */
  private def createStructureCryptConfig(cryptConfig: CryptConfig, password: Option[String],
                                         cryptMode: CryptMode.Value): StructureCryptConfig =
    StructureCryptConfig(password, cryptMode == CryptMode.FilesAndNames, cryptConfig.cryptCacheSize)

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
class SyncProtocolHolder(srcProtocol: SyncProtocol, dstProtocol: SyncProtocol,
                         val oAuthRefreshKillSwitch: SharedKillSwitch)(implicit system: ActorSystem[_]):
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
    future.andThen {
      case _ =>
        srcProtocol.close()
        dstProtocol.close()
    }

  /**
    * Returns the execution context from the actor system in implicit scope.
    *
    * @return the execution context
    */
  private implicit def executionContext: ExecutionContext = system.executionContext
