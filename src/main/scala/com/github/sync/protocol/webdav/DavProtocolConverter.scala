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

import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.cloudfiles.webdav.{DavModel, DavParser}
import com.github.sync.SyncTypes
import com.github.sync.protocol.FileSystemProtocolConverter
import com.github.sync.protocol.config.DavStructureConfig
import com.github.sync.protocol.webdav.DavProtocolConverter.PatchTimeFormatter
import org.apache.pekko.http.scaladsl.model.Uri

import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalQuery
import java.time.{Instant, ZoneId}
import scala.util.Try

private object DavProtocolConverter:
  /**
    * A formatter instance used to generate the time representation for the
    * last modified attribute.
    */
  private val PatchTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneId.of("Z"))

/**
  * A [[FileSystemProtocolConverter]] implementation for the WebDAV file
  * system.
  *
  * This class converts between file and folder representations of the Sync
  * model and the ones defined by the [[DavModel]] module. While this is
  * straight-forward for the basic properties, the last modified time has to be
  * loaded from attributes.
  *
  * @param davConfig            the WebDAV configuration
  * @param optModifiedAttribute an optional custom property to access the last
  *                             modified time                   
  */
private class DavProtocolConverter(val davConfig: DavStructureConfig,
                                   val optModifiedAttribute: Option[DavModel.AttributeKey])
  extends FileSystemProtocolConverter[Uri, DavModel.DavFile, DavModel.DavFolder]:
  /** The attribute to be used when setting the last modified time. */
  private val setModifiedAttribute = optModifiedAttribute getOrElse DavParser.AttrModifiedAt

  override def elementIDFromString(strID: String): Uri = strID

  override def toFsFile(fileElement: SyncTypes.FsFile, name: String, useID: Boolean): DavModel.DavFile =
    val lastModifiedStr = PatchTimeFormatter.format(fileElement.lastModified)
    val attributes = Map(setModifiedAttribute -> lastModifiedStr)
    val fileUri = if useID then Uri(fileElement.id) else null
    DavModel.newFile(name, fileElement.size, id = fileUri, attributes = DavModel.Attributes(attributes))

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
    optModifiedAttribute flatMap file.attributes.values.get map parseTimeAttribute getOrElse
      file.lastModifiedAt

  /**
    * Parses a date in string form to a corresponding ''Instant''. The date is
    * expected to be in the format defined by RFC 1123, but this function tries
    * to be more tolerant and accepts also ISO timestamps. If parsing of the
    * date fails, result is the ''DateUndefined'' constant.
    *
    * @param strDate the date as string
    * @return the resulting ''Instant''
    */
  private def parseTimeAttribute(strDate: String): Instant = Try {
    val query: TemporalQuery[Instant] = Instant.from
    DateTimeFormatter.RFC_1123_DATE_TIME.parse(strDate, query)
  } orElse Try(Instant.parse(strDate)) getOrElse DavParser.UndefinedDate
