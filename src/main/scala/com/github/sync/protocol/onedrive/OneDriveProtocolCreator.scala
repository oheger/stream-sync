/*
 * Copyright 2018-2024 The Developers Team.
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

package com.github.sync.protocol.onedrive

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.onedrive.{OneDriveConfig, OneDriveFileSystem, OneDriveModel}
import com.github.sync.protocol.config.OneDriveStructureConfig
import com.github.sync.protocol.{FileSystemProtocolConverter, FileSystemProtocolCreator}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.Timeout

/**
  * A [[FileSystemProtocolCreator]] implementation for structures accessed via
  * the OneDrive protocol.
  *
  * It creates a ''OneDriveFileSystem'' and compatible components.
  */
private object OneDriveProtocolCreator
  extends FileSystemProtocolCreator[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder,
    OneDriveStructureConfig]:
  /**
    * The prefix of URIs indicating the OneDrive protocol. This prefix needs to
    * be removed when determining the drive ID.
    */
  private val OneDriveUriPrefix = "onedrive:"

  override def createFileSystem(uri: String, config: OneDriveStructureConfig, timeout: Timeout):
  ExtensibleFileSystem[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder,
    Model.FolderContent[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder]] =
    val driveID = uri.stripPrefix(OneDriveUriPrefix)
    val baseConfig = OneDriveConfig(driveID = driveID, timeout = timeout, optRootPath = Some(config.syncPath))
    val chunkedConfig = config.optUploadChunkSizeMB.map(_ * 1024 * 1024).map(c => baseConfig.copy(uploadChunkSize = c))
      .getOrElse(baseConfig)
    val oneDriveConfig = config.optServerUri.map(u => chunkedConfig.copy(serverUri = u)).getOrElse(chunkedConfig)
    new OneDriveFileSystem(oneDriveConfig)

  override def createHttpSender(spawner: Spawner, factory: HttpRequestSenderFactory, uri: String,
                                config: OneDriveStructureConfig, senderConfig: HttpRequestSenderConfig):
  ActorRef[HttpRequestSender.HttpCommand] =
    factory.createMultiHostRequestSender(spawner, senderConfig)

  override def createConverter(config: OneDriveStructureConfig):
  FileSystemProtocolConverter[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder] =
    OneDriveProtocolConverter
