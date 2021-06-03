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

import akka.http.scaladsl.model.Uri
import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.cloudfiles.webdav.{DavModel, DavParser}
import com.github.sync.SyncTypes
import com.github.sync.protocol.FileSystemProtocolConverter
import com.github.sync.protocol.config.DavStructureConfig

import java.time.Instant

/**
  * A [[FileSystemProtocolConverter]] implementation for the WebDAV file
  * system.
  *
  * This class converts between file and folder representations of the Sync
  * model and the ones defined by the [[DavModel]] module. While this is
  * straight-forward for the basic properties, the last modified time has to be
  * loaded from attributes.
  *
  * @param davConfig the WebDAV configuration
  */
private class DavProtocolConverter(val davConfig: DavStructureConfig)
  extends FileSystemProtocolConverter[Uri, DavModel.DavFile, DavModel.DavFolder] {
  /** An optional custom property to access the last modified time. */
  private val optModifiedAttribute = davConfig.optLastModifiedProperty map { property =>
    val namespace = davConfig.optLastModifiedNamespace getOrElse DavParser.NS_DAV
    DavModel.AttributeKey(namespace, property)
  }

  /** The attribute to be used when setting the last modified time. */
  private val setModifiedAttribute = optModifiedAttribute getOrElse DavParser.AttrModifiedAt

  override def elementIDFromString(strID: String): Uri = strID

  override def toFsFile(fileElement: SyncTypes.FsFile, name: String): DavModel.DavFile = {
    val lastModifiedStr = fileElement.lastModified.toString
    val attributes = Map(setModifiedAttribute -> lastModifiedStr)
    val fileUri = if (fileElement.id == null) null else Uri(fileElement.id)
    DavModel.newFile(name, fileElement.size, id = fileUri, attributes = DavModel.Attributes(attributes))
  }

  override def toFsFolder(folderElement: SyncTypes.FsFolder, name: String): DavModel.DavFolder =
    DavModel.newFolder(name = name)

  override def toFileElement(file: DavModel.DavFile, path: String, level: Int): SyncTypes.FsFile =
    SyncTypes.FsFile(id = file.id.toString(), relativeUri = path + UriEncodingHelper.encode(file.name),
      size = file.size, lastModified = fetchModifiedTime(file), level = level)

  override def toFolderElement(folder: DavModel.DavFolder, path: String, level: Int): SyncTypes.FsFolder =
    SyncTypes.FsFolder(id = folder.id.toString(), relativeUri = path + UriEncodingHelper.encode(folder.name),
      level = level)

  /**
    * Obtains the last modified time from the given file. The way how this
    * information is obtained depends on the configuration, which might define
    * a custom time property.
    *
    * @param file the file
    * @return the last modified time
    */
  private def fetchModifiedTime(file: DavModel.DavFile): Instant =
    optModifiedAttribute flatMap file.attributes.values.get map DavParser.parseTimeAttribute getOrElse
      file.lastModifiedAt
}
