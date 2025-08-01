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

package com.github.sync.protocol.gdrive

import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, Spawner}
import com.github.cloudfiles.gdrive.GoogleDriveModel
import com.github.sync.protocol.FileSystemSyncProtocolFactory
import com.github.sync.protocol.config.GoogleDriveStructureConfig
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.util.Timeout

/**
  * A concrete ''SyncProtocolFactory'' for creating a protocol to access a
  * file system via the OneDrive protocol.
  *
  * @param config           the protocol-specific configuration
  * @param httpSenderConfig the config for the HTTP sender actor
  * @param timeout          the timeout for sync operations
  * @param spawner          the object to spawn new actors
  * @param system           the actor system
  */
class GoogleDriveProtocolFactory(config: GoogleDriveStructureConfig,
                                 httpSenderConfig: HttpRequestSenderConfig,
                                 timeout: Timeout,
                                 spawner: Spawner)
                                (implicit system: ActorSystem[?])
  extends FileSystemSyncProtocolFactory[String, GoogleDriveModel.GoogleDriveFile, GoogleDriveModel.GoogleDriveFolder,
    GoogleDriveStructureConfig](GoogleDriveProtocolCreator, config, httpSenderConfig, timeout, spawner)

