/*
 * Copyright 2018-2020 The Developers Team.
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

import java.nio.file.Path
import java.time.ZoneId

/**
  * A data class collecting all the configuration properties supported by a
  * sync process of a local file system.
  *
  * @param rootPath    the root path to be synced
  * @param optTimeZone an optional timezone that determines how the timestamps
  *                    of files are to be interpreted
  */
case class LocalFsConfig(rootPath: Path, optTimeZone: Option[ZoneId])
