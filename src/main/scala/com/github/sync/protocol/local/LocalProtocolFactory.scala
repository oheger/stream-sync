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

package com.github.sync.protocol.local

import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, Spawner}
import com.github.cloudfiles.localfs.LocalFsModel
import com.github.sync.protocol.FileSystemSyncProtocolFactory
import com.github.sync.protocol.config.FsStructureConfig
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.util.Timeout

import java.nio.file.Path
import scala.concurrent.ExecutionContext

/**
  * A concrete ''SyncProtocolFactory'' for creating a protocol to access the
  * local file system.
  *
  * @param config           the protocol-specific configuration
  * @param httpSenderConfig the config for the HTTP sender actor
  * @param timeout          the timeout for sync operations
  * @param spawner          the object to spawn new actors
  * @param ec               the execution context for the local file system
  * @param system           the actor system
  */
class LocalProtocolFactory(config: FsStructureConfig,
                           httpSenderConfig: HttpRequestSenderConfig,
                           timeout: Timeout,
                           spawner: Spawner,
                           ec: ExecutionContext)
                          (implicit system: ActorSystem[_])
  extends FileSystemSyncProtocolFactory[Path, LocalFsModel.LocalFile, LocalFsModel.LocalFolder,
    FsStructureConfig](new LocalProtocolCreator(ec), config, httpSenderConfig, timeout, spawner)
