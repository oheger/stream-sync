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

package com.github.sync.protocol.webdav

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.webdav.{DavConfig, DavFileSystem, DavModel, DavParser}
import com.github.sync.protocol.config.DavStructureConfig
import com.github.sync.protocol.{FileSystemProtocolConverter, FileSystemProtocolCreator}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.util.Timeout

/**
  * A [[FileSystemProtocolCreator]] implementation for structures accessed via
  * the WebDAV protocol.
  *
  * It creates a ''DavFileSystem'' and compatible components.
  */
private object DavProtocolCreator
  extends FileSystemProtocolCreator[Uri, DavModel.DavFile, DavModel.DavFolder, DavStructureConfig]:
  /**
    * The prefix of URIs indicating the DAV protocol. This prefix needs to be
    * removed when passing the URI to the file system.
    */
  private val DavUriPrefix = "dav:"

  override def createFileSystem(uri: String, config: DavStructureConfig, timeout: Timeout):
  ExtensibleFileSystem[Uri, DavModel.DavFile, DavModel.DavFolder,
    Model.FolderContent[Uri, DavModel.DavFile, DavModel.DavFolder]] =
    val davConfig = DavConfig(rootUri = uri.stripPrefix(DavUriPrefix), timeout = timeout,
      deleteBeforeOverride = config.deleteBeforeOverride)
    val davConfigWithModifiedAttr = modifiedProperty(config).fold(davConfig) { attr =>
      davConfig.copy(additionalAttributes = List(attr))
    }

    new DavFileSystem(davConfigWithModifiedAttr)

  override def createHttpSender(spawner: Spawner, factory: HttpRequestSenderFactory, uri: String,
                                config: DavStructureConfig, senderConfig: HttpRequestSenderConfig):
  ActorRef[HttpRequestSender.HttpCommand] =
    factory.createRequestSender(spawner, uri.stripPrefix(DavUriPrefix), senderConfig)

  override def createConverter(config: DavStructureConfig):
  FileSystemProtocolConverter[Uri, DavModel.DavFile, DavModel.DavFolder] =
    new DavProtocolConverter(config, modifiedProperty(config))

  /**
    * Returns an ''Option'' with a key for a custom property for the last
    * modified time. If the configuration provided contains information about
    * such a property, the ''Option'' is populated accordingly.
    *
    * @param config the configuration
    * @return an ''Option'' with the key for a last modified property
    */
  private def modifiedProperty(config: DavStructureConfig): Option[DavModel.AttributeKey] =
    config.optLastModifiedProperty map { name =>
      val namespace = config.optLastModifiedNamespace getOrElse DavParser.NS_DAV
      DavModel.AttributeKey(namespace, name)
    }
