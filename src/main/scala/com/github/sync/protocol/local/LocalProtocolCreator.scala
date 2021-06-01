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

package com.github.sync.protocol.local

import akka.actor.typed.ActorRef
import akka.util.Timeout
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.localfs.{LocalFileSystem, LocalFsConfig, LocalFsModel}
import com.github.sync.protocol.config.FsStructureConfig
import com.github.sync.protocol.{FileSystemProtocolConverter, FileSystemProtocolCreator}

import java.nio.file.{Path, Paths}
import scala.concurrent.ExecutionContext

/**
  * A specialized [[FileSystemProtocolCreator]] implementation for the local
  * file system.
  *
  * Note that this implementation creates a dummy actor for sending HTTP
  * requests, although this is not needed for the local file system. However,
  * when the protocol is closed it also stops this actor.
  *
  * @param executionContext the execution context for the local file system
  */
class LocalProtocolCreator(val executionContext: ExecutionContext)
  extends FileSystemProtocolCreator[Path, LocalFsModel.LocalFile, LocalFsModel.LocalFolder, FsStructureConfig] {
  override def createFileSystem(uri: String, config: FsStructureConfig, timeout: Timeout): LocalFileSystem = {
    val config = LocalFsConfig(basePath = Paths get uri, sanitizePaths = true,
      executionContext = executionContext)
    new LocalFileSystem(config)
  }

  override def createHttpSender(spawner: Spawner, factory: HttpRequestSenderFactory, uri: String,
                                config: FsStructureConfig, senderConfig: HttpRequestSenderConfig):
  ActorRef[HttpRequestSender.HttpCommand] =
    factory.createRequestSender(spawner, "https://stream-sync.org", senderConfig)

  override def createConverter(config: FsStructureConfig):
  FileSystemProtocolConverter[Path, LocalFsModel.LocalFile, LocalFsModel.LocalFolder] =
    new LocalProtocolConverter(config.optTimeZone)
}
