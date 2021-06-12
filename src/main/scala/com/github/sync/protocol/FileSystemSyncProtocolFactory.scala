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

package com.github.sync.protocol

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, HttpRequestSenderFactoryImpl, Spawner}
import com.github.cloudfiles.crypt.alg.aes.Aes
import com.github.cloudfiles.crypt.fs.resolver.CachePathComponentsResolver
import com.github.cloudfiles.crypt.fs.{CryptConfig, CryptContentFileSystem, CryptNamesFileSystem}
import com.github.sync.protocol.FileSystemSyncProtocolFactory.createCryptConfig
import com.github.sync.protocol.config.{StructureConfig, StructureCryptConfig}

import java.security.SecureRandom

object FileSystemSyncProtocolFactory {
  /**
    * A ''SecureRandom'' object that is used as source for randomness for all
    * cryptographic operations.
    */
  private val RandomSource = new SecureRandom

  /**
    * Creates a ''CryptConfig'' with default settings and keys derived from the
    * passed in password.
    *
    * @param password the password
    * @return the ''CryptConfig'' using this password
    */
  private def createCryptConfig(password: String): CryptConfig = {
    val key = Aes.keyFromString(password)
    CryptConfig(Aes, key, key, RandomSource)
  }
}

/**
  * An implementation of [[SyncProtocolFactory]] that creates a protocol based
  * on a ''FileSystem''.
  *
  * This class implements all the steps necessary to construct a
  * fully-initialized [[FileSystemSyncProtocol]] implementation. This involves
  * the creation of the single components that are needed for such a protocol.
  * To do this in a protocol-specific manner, it delegates to a
  * [[FileSystemProtocolCreator]] object. The components created that way are
  * then further processed if necessary (e.g. by adding encryption support if
  * desired) and then bundled together to a working protocol instance.
  *
  * @param creator           the object to create protocol-specific components
  * @param config            the protocol-specific configuration
  * @param httpSenderConfig  the config for the HTTP sender actor
  * @param timeout           the timeout for sync operations
  * @param spawner           the object to spawn new actors
  * @param httpSenderFactory the factory for the HTTP sender actor
  * @param system            the actor system
  * @tparam ID     the type of file system IDs
  * @tparam FILE   the type of file system files
  * @tparam FOLDER the type of file system folders
  * @tparam C      the type of configuration for the supported sync structure
  */
class FileSystemSyncProtocolFactory[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID],
  C <: StructureConfig](val creator: FileSystemProtocolCreator[ID, FILE, FOLDER, C],
                        val config: C,
                        val httpSenderConfig: HttpRequestSenderConfig,
                        val timeout: Timeout,
                        spawner: Spawner,
                        httpSenderFactory: HttpRequestSenderFactory = HttpRequestSenderFactoryImpl)
                       (implicit system: ActorSystem[_]) extends SyncProtocolFactory {
  override def createProtocol(uri: String, cryptConfig: StructureCryptConfig): SyncProtocol = {
    val fileSystem = decorateFileSystem(creator.createFileSystem(uri, config, timeout), cryptConfig)
    val httpSender = creator.createHttpSender(spawner, httpSenderFactory, uri, config, httpSenderConfig)
    val converter = creator.createConverter(config)

    new FileSystemSyncProtocol(fileSystem, httpSender, converter)
  }

  /**
    * Decorates the passed in extensible file system to support the desired
    * cryptographic operations.
    *
    * @param fs                the original file system
    * @param structCryptConfig the cryptography-related configuration
    * @return the decorated file system
    */
  private def decorateFileSystem(fs: ExtensibleFileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]],
                                 structCryptConfig: StructureCryptConfig):
  ExtensibleFileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]] =
    structCryptConfig.password.fold(fs) { password =>
      val cryptConfig = createCryptConfig(password)
      val fsc = new CryptContentFileSystem(fs, cryptConfig)
      if (structCryptConfig.cryptNames) {
        implicit val resolverTimeout: Timeout = timeout
        new CryptNamesFileSystem(fsc, cryptConfig,
          resolver = CachePathComponentsResolver(spawner, structCryptConfig.cryptCacheSize))
      } else fsc
    }
}
