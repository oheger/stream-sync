/*
 * Copyright 2018 The Developers Team.
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

package com.github.sync.local

import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.time.{Instant, ZoneId, ZonedDateTime}

/**
  * An object providing functionality related to file times and related
  * conversions.
  *
  * With the functions offered by this service the last modified timestamps of
  * files can be read and updated. In some constellations it is also required
  * to do a conversion between different time zones. For instance, the
  * timestamps of files stored on a FAT32 file system are always in a local
  * time zone. This is a problem when they are compared with files from
  * another volume (e.g. NTFS) which uses UTC timestamps. To fix this, the
  * user has to define a time zone in which timestamps are to be interpreted.
  */
object FileTimeUtils {
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
  def instantFromTimeZone(instant: Instant, optZone: Option[ZoneId]): Instant =
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
  def instantToTimeZone(instant: Instant, optZone: Option[ZoneId]): Instant =
    instantTimeZoneConversion(instant, optZone) { (inst, zone) =>
      val zonedDateTime = ZonedDateTime.ofInstant(inst, ZoneUTC)
      zonedDateTime.withZoneSameLocal(zone).toInstant
    }

  /**
    * Reads the last-modified time from the given file. If this fails, an
    * ''IOException'' is thrown.
    *
    * @param file the file
    * @return the last-modified time of this file
    */
  def getLastModifiedTime(file: Path): Instant =
    Files.getLastModifiedTime(file).toInstant

  /**
    * Reads the last-modified time from the given file and optionally converts
    * it into the given time zone. This function combines the functions
    * ''getLastModifiedTime()'' and ''instantFromTimeZone()''.
    *
    * @param file    the file
    * @param optZone an optional source time zone
    * @return the adjusted last-modified time of this file
    */
  def getLastModifiedTimeInTimeZone(file: Path, optZone: Option[ZoneId]): Instant =
    instantFromTimeZone(getLastModifiedTime(file), optZone)

  /**
    * Writes the last-modified time for the given file. If this fails, an
    * ''IOException'' is thrown.
    *
    * @param file the file
    * @param time the new last-modified time for this file
    */
  def setLastModifiedTime(file: Path, time: Instant): Unit = {
    Files.setLastModifiedTime(file, FileTime from time)
  }

  /**
    * Writes the last-modified time for the given file and optionally adjusts
    * it to the given time zone. This function combines the functions
    * ''instantToTimeZone()'' and ''setLastModifiedTime()''.
    *
    * @param file    the file
    * @param time    the new last-modified time of this file
    * @param optZone an optional target time zone
    */
  def setLastModifiedTimeInTimeZone(file: Path, time: Instant, optZone: Option[ZoneId]): Unit = {
    setLastModifiedTime(file, instantToTimeZone(time, optZone))
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
}
