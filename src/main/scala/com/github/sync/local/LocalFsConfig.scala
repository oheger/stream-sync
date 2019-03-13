/*
 * Copyright 2018-2019 The Developers Team.
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

import java.nio.file.{Path, Paths}
import java.time.ZoneId

import com.github.sync.SyncTypes.{StructureType, SupportedArgument}

import scala.concurrent.{ExecutionContext, Future}

object LocalFsConfig {
  /**
    * Property for the time zone to be applied to the last-modified timestamps
    * of files encountered on the local FS. This property is optional. If it is
    * not defined, timestamps are obtained directly from the file system
    * without modifications. This is appropriate if the file system stores them
    * in a defined way. If this is not the case (e.g. for a FAT32 file system
    * which stores them in a local time zone), the time zone must be specified
    * explicitly. Otherwise, the comparison of timestamps (which is one
    * criterion to decide whether a file has been changed) is going to fail.
    */
  val PropTimeZone = "time-zone"

  /**
    * Returns a ''Future'' with a new instance of ''LocalFsConfig'' that has
    * been initialized from the given parameters. Additional
    * (structure-specific) options are obtained from the passed in map. Note
    * that the resulting ''Future'' can fail if invalid options have been
    * provided at the command line.
    *
    * @param structType the structure type
    * @param rootPath   the root path of the folder structure
    * @param properties a map with additional command line options
    * @param ec         the execution context
    * @return a ''Future'' with the new configuration object
    */
  def apply(structType: StructureType, rootPath: String, properties: Map[String, String])
           (implicit ec: ExecutionContext): Future[LocalFsConfig] = Future {
    val zone = properties get structType.configPropertyName(PropTimeZone) map ZoneId.of
    LocalFsConfig(Paths get rootPath, zone)
  }

  /**
    * Returns a collection of ''SupportedArgument'' objects for the given
    * structure type. This information is used to obtain the corresponding
    * options from the command line.
    *
    * @param structType the structure type
    * @return a sequence with ''SupportedArgument'' objects
    */
  def supportedArgumentsFor(structType: StructureType): Iterable[SupportedArgument] =
    List(SupportedArgument(structType.configPropertyName(PropTimeZone), mandatory = false))
}

/**
  * A data class collecting all the configuration properties supported by a
  * sync process of a local file system.
  *
  * @param rootPath    the root path to be synced
  * @param optTimeZone an optional timezone that determines how the timestamps
  *                    of files are to be interpreted
  */
case class LocalFsConfig(rootPath: Path, optTimeZone: Option[ZoneId])
