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

package com.github.sync.protocol.webdav

import akka.actor.typed.ActorRef
import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.webdav.{DavConfig, DavFileSystem, DavModel}
import com.github.sync.protocol.{FileSystemProtocolConverter, FileSystemProtocolCreator}
import com.github.sync.protocol.config.DavStructureConfig

/**
  * A [[FileSystemProtocolCreator]] implementation for structures accessed via
  * the WebDAV protocol.
  *
  * It creates a ''DavFileSystem'' and compatible components.
  */
class DavProtocolCreator
  extends FileSystemProtocolCreator[Uri, DavModel.DavFile, DavModel.DavFolder, DavStructureConfig] {
  override def createFileSystem(uri: String, config: DavStructureConfig, timeout: Timeout):
  ExtensibleFileSystem[Uri, DavModel.DavFile, DavModel.DavFolder,
    Model.FolderContent[Uri, DavModel.DavFile, DavModel.DavFolder]] = {
    val davConfig = DavConfig(rootUri = uri, deleteBeforeOverride = config.deleteBeforeOverride,
      timeout = timeout)
    new DavFileSystem(davConfig)
  }

  override def createHttpSender(spawner: Spawner, factory: HttpRequestSenderFactory, uri: String,
                                config: DavStructureConfig, senderConfig: HttpRequestSenderConfig):
  ActorRef[HttpRequestSender.HttpCommand] =
    factory.createRequestSender(spawner, uri, senderConfig)

  override def createConverter(config: DavStructureConfig):
  FileSystemProtocolConverter[Uri, DavModel.DavFile, DavModel.DavFolder] =
    new DavProtocolConverter(config)
}
