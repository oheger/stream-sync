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

package com.github.sync.protocol.config

/**
  * A data class that collects the options related to encryption for a
  * structure that takes part in a sync process.
  *
  * @param password       the password to be used for encryption; if not set,
  *                       encryption is disabled, and all other properties are
  *                       ignored
  * @param cryptNames     flag whether element names should be encrypted; if
  *                       '''false''', only the content of files is encrypted
  * @param cryptCacheSize the size of the cache for encrypted paths
  */
case class StructureCryptConfig(password: Option[String],
                                cryptNames: Boolean,
                                cryptCacheSize: Int)
