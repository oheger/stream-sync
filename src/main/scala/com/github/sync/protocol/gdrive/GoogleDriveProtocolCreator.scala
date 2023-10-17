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

package com.github.sync.protocol.gdrive

import akka.actor.typed.ActorRef
import akka.util.Timeout
import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.core.http.{HttpRequestSender, UriEncodingHelper}
import com.github.cloudfiles.gdrive.{GoogleDriveConfig, GoogleDriveFileSystem, GoogleDriveModel}
import com.github.sync.protocol.config.GoogleDriveStructureConfig
import com.github.sync.protocol.{FileSystemProtocolConverter, FileSystemProtocolCreator}

/**
  * A [[FileSystemProtocolCreator]] implementation for structures accessed via
  * the OneDrive protocol.
  *
  * It creates a ''OneDriveFileSystem'' and compatible components.
  */
object GoogleDriveProtocolCreator
  extends FileSystemProtocolCreator[String, GoogleDriveModel.GoogleDriveFile, GoogleDriveModel.GoogleDriveFolder,
    GoogleDriveStructureConfig]:
  /**
    * The prefix of URIs indicating the GoogleDrive protocol. This prefix needs
    * to be removed when determining the sync path.
    */
  private val GoogleDriveUriPrefix = "googledrive:"

  override def createFileSystem(uri: String, config: GoogleDriveStructureConfig, timeout: Timeout):
  ExtensibleFileSystem[String, GoogleDriveModel.GoogleDriveFile, GoogleDriveModel.GoogleDriveFolder,
    Model.FolderContent[String, GoogleDriveModel.GoogleDriveFile, GoogleDriveModel.GoogleDriveFolder]] =
    val optRootPath = Some(UriEncodingHelper.removeLeadingSeparator(uri.stripPrefix(GoogleDriveUriPrefix)))
      .filter(_.nonEmpty)
    val googleConfig = GoogleDriveConfig(timeout = timeout,
      serverUri = config.serverUri,
      optRootPath = optRootPath)
    new GoogleDriveFileSystem(googleConfig)

  override def createHttpSender(spawner: Spawner, factory: HttpRequestSenderFactory, uri: String,
                                config: GoogleDriveStructureConfig, senderConfig: HttpRequestSenderConfig):
  ActorRef[HttpRequestSender.HttpCommand] =
    factory.createRequestSender(spawner, config.serverUri, senderConfig)

  override def createConverter(config: GoogleDriveStructureConfig):
  FileSystemProtocolConverter[String, GoogleDriveModel.GoogleDriveFile, GoogleDriveModel.GoogleDriveFolder] =
    GoogleDriveProtocolConverter
