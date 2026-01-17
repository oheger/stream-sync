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

package com.github.sync

import com.github.cloudfiles.core.http.Secret
import com.github.sync.auth.CredentialsServiceImpl
import com.github.sync.auth.oauth.SyncNoAuth
import com.github.sync.cli.FilterManager.SyncFilterData
import com.github.sync.cli.SyncParameterManager.SyncConfig
import com.github.sync.cli.{Sync, SyncCliStreamConfig, SyncCliStructureConfig, SyncParameterManager}
import com.github.sync.protocol.config.FsStructureConfig
import com.github.sync.stream.Throttle
import org.apache.logging.log4j.Level
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.util.Timeout
import org.scalatest.Suite

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

object CredentialsSupport:
  /** A default log configuration. */
  final val DefaultLogConfig = SyncParameterManager.LogConfig(
    logFilePath = None,
    errorLogFilePath = None,
    logLevel = Level.WARN
  )

  /** A filter data instance that does not define any filers. */
  final val UndefinedFilterData = SyncFilterData(Map.empty)

  /** A default crypt config disabling encryption. */
  final val DisabledCryptConfig = SyncParameterManager.CryptConfig(
    srcPassword = None,
    srcCryptMode = SyncParameterManager.CryptMode.None,
    dstPassword = None,
    dstCryptMode = SyncParameterManager.CryptMode.None,
    cryptCacheSize = 8
  )

  /** A default structure config for the local file system. */
  final val DefaultLocalStructConfig = SyncCliStructureConfig.StructureSyncConfig(
    optRetryConfig = None,
    authConfig = SyncNoAuth,
    structureConfig = FsStructureConfig(None)
  )

  /** The secret to encrypt the credential store file. */
  private val StorageSecret = Secret("cred-store-s3cr3t")

  /** The name of the file storing credentials. */
  private val StorageFileName = "credentials.crypt"

  /**
    * A test implementation of [[Sync]] that uses the correct actor system from
    * the test class and exposes the main run function.
    *
    * @param ec          the execution context
    * @param actorSystem the actor system
    */
  private class SyncTestImpl(override val ec: ExecutionContext,
                             override val actorSystem: ActorSystem) extends Sync:
    override def runApp(config: SyncParameterManager.SyncConfig): Future[String] = super.runApp(config)
  end SyncTestImpl

  /**
    * Generates a reference to a credential for a given key. This key is then
    * looked up in the credential storage.
    *
    * @param key the key for the credential
    * @return the reference to this key in the credential storage
    */
  def credentialsRef(key: String): Secret = Secret(s"${SyncCliStreamConfig.DefaultCredentialsPrefix}$key")

  /**
    * Creates a config for a sync process based on the provided parameters. For
    * some configurations that are irrelevant for tests, default values are
    * set.
    *
    * @param srcUri       the URI for the source structure
    * @param dstUri       the URI for the destination structure
    * @param srcConfig    the configuration for the source structure
    * @param dstConfig    the configuration for the destination structure
    * @param streamConfig the stream configuration
    * @param cryptConfig  the configuration for encryption
    * @return the sync configuration
    */
  def testSyncConfig(srcUri: String,
                     dstUri: String,
                     srcConfig: SyncCliStructureConfig.StructureSyncConfig,
                     dstConfig: SyncCliStructureConfig.StructureSyncConfig,
                     streamConfig: SyncCliStreamConfig.StreamConfig,
                     cryptConfig: SyncParameterManager.CryptConfig = DisabledCryptConfig):
  SyncParameterManager.SyncConfig =
    SyncParameterManager.SyncConfig(
      srcUri = srcUri,
      dstUri = dstUri,
      srcConfig = srcConfig,
      dstConfig = dstConfig,
      streamConfig = streamConfig,
      cryptConfig = cryptConfig,
      logConfig = DefaultLogConfig,
      filterData = UndefinedFilterData
    )
end CredentialsSupport

/**
  * A trait providing functionality to test the credentials management in sync
  * processes.
  *
  * With the functions provided here, test classes can create an encrypted
  * credential storage and a corresponding configuration, so that this storage
  * can be referenced from a sync process. This enables test for the resolving
  * of credentials.
  */
trait CredentialsSupport:
  this: Suite & FileTestHelper =>

  import CredentialsSupport.*

  /**
    * Writes an encrypted file with the specified credentials and returns a 
    * configuration for a mirror sync process pointing to this file.
    *
    * @param credentials a map with the credentials to store
    * @param dryRun      flag whether the dry-run mode should be enabled
    * @param ec          the execution context
    * @param system      the actor system
    * @return a [[Future]] with a configuration pointing to the storage
    */
  def setUpCredentialStore(credentials: Map[String, String], dryRun: Boolean = false)
                          (using ec: ExecutionContext, system: ActorSystem):
  Future[SyncCliStreamConfig.StreamConfig] =
    val credentialsPath = createPathInDirectory(StorageFileName)
    val credentialItems = credentials.map: e =>
      CredentialsServiceImpl.CredentialEntry(e._1, Secret(e._2))
    CredentialsServiceImpl.storeCredentials(credentialsPath, StorageSecret, credentialItems) map : _ =>
      val credConfig = SyncCliStreamConfig.CredentialsConfig(
        credentialsFile = credentialsPath,
        credentialsSecret = StorageSecret,
        credentialsPrefix = SyncCliStreamConfig.DefaultCredentialsPrefix
      )
      SyncCliStreamConfig.StreamConfig(
        dryRun = dryRun,
        timeout = Timeout(10.minutes),
        ignoreTimeDelta = None,
        opsPerUnit = None,
        throttleUnit = Throttle.TimeUnit.Hour,
        modeConfig = SyncCliStreamConfig.MirrorStreamConfig(None, switched = false),
        credentialsConfig = Some(credConfig)
      )

  /**
    * Executes a sync process based on the provided configuration and returns 
    * the generated output.
    *
    * @param config the sync configuration
    * @param ec     the execution context
    * @param system the actor system
    * @return a [[Future]] with the output generated by the sync process
    */
  def runSync(config: SyncConfig)
             (using ec: ExecutionContext, system: ActorSystem): Future[String] =
    new SyncTestImpl(ec, system).runApp(config)
    