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

import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.cloudfiles.localfs.LocalFsModel
import com.github.sync.SyncTypes
import com.github.sync.protocol.FileSystemProtocolConverter

import java.nio.file.{Path, Paths}
import java.time.{Instant, ZoneId, ZonedDateTime}

object LocalProtocolConverter:
  /** Constant for the UTC time zone. */
  private val ZoneUTC = ZoneId.of("Z")

  /**
    * Interprets the given ''Instant'' in the specified time zone. This
    * function can be used to deal with timestamps of files stemming from a
    * file system with a different time zone. If a zone is defined, the instant
    * is adjusted accordingly; otherwise, the same instant is returned.
    *
    * @param instant the ''Instant''
    * @param optZone an optional source time zone
    * @return the adjusted ''Instant''
    */
  private def instantFromTimeZone(instant: Instant, optZone: Option[ZoneId]): Instant =
    instantTimeZoneConversion(instant, optZone) { (inst, zone) =>
      val zonedDateTime = ZonedDateTime.ofInstant(inst, zone)
      zonedDateTime.withZoneSameLocal(ZoneUTC).toInstant
    }

  /**
    * Converts the given ''Instant'', so that it represents a local timestamp
    * in the given time zone. This is needed when storing files on a file
    * system that uses local timestamps in a specific time zone. If a zone is
    * defined, the instant is adjusted to match this zone; otherwise, the same
    * instant is returned.
    *
    * @param instant the ''Instant''
    * @param optZone an optional target time zone
    * @return the adjusted ''Instant''
    */
  private def instantToTimeZone(instant: Instant, optZone: Option[ZoneId]): Instant =
    instantTimeZoneConversion(instant, optZone) { (inst, zone) =>
      val zonedDateTime = ZonedDateTime.ofInstant(inst, ZoneUTC)
      zonedDateTime.withZoneSameLocal(zone).toInstant
    }

  /**
    * Helper method for converting the time zone of an ''Instant''. If the
    * option with the time zone is defined, the given conversion function is
    * invoked. Otherwise, the same ''Instant'' is returned.
    *
    * @param instant the ''Instant''
    * @param optZone an optional time zone
    * @param f       the conversion function
    * @return the resulting ''Instant''
    */
  private def instantTimeZoneConversion(instant: Instant, optZone: Option[ZoneId])
                                       (f: (Instant, ZoneId) => Instant): Instant =
    optZone map (f(instant, _)) getOrElse instant

/**
  * A [[FileSystemProtocolConverter]] implementation for the local file system.
  *
  * This class converts representations from the local file system to the model
  * used by sync processes and vice versa. This is more or less
  * straight-forward with one exception: In some constellations it is required
  * to do a conversion between different time zones. For instance, the
  * timestamps of files stored on a FAT32 file system are always in a local
  * time zone. This is a problem when they are compared with files from
  * another volume (e.g. NTFS) which uses UTC timestamps. To fix this, the
  * user has to define a time zone in which timestamps are to be interpreted.
  *
  * @param optTimeZone the optional time zone for timestamps
  */
private class LocalProtocolConverter(val optTimeZone: Option[ZoneId])
  extends FileSystemProtocolConverter[Path, LocalFsModel.LocalFile, LocalFsModel.LocalFolder]:

  import LocalProtocolConverter._

  override def elementIDFromString(strID: String): Path = Paths get strID

  override def toFsFile(fileElement: SyncTypes.FsFile, name: String, useID: Boolean): LocalFsModel.LocalFile =
    val path = if useID then Paths.get(fileElement.id) else null
    LocalFsModel.newFile(path = path, name = name,
      lastModifiedAt = Some(instantToTimeZone(fileElement.lastModified, optTimeZone)))

  override def toFsFolder(folderElement: SyncTypes.FsFolder, name: String): LocalFsModel.LocalFolder =
    LocalFsModel.newFolder(name = name)

  override def toFileElement(file: LocalFsModel.LocalFile, path: String, level: Int): SyncTypes.FsFile =
    SyncTypes.FsFile(id = file.id.toString, relativeUri = path + UriEncodingHelper.encode(file.name),
      lastModified = instantFromTimeZone(file.lastModifiedAt, optTimeZone), level = level, size = file.size)

  override def toFolderElement(folder: LocalFsModel.LocalFolder, path: String, level: Int): SyncTypes.FsFolder =
    SyncTypes.FsFolder(id = folder.id.toString, relativeUri = path + UriEncodingHelper.encode(folder.name),
      level = level)
