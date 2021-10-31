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

package com.github.sync.protocol

import com.github.sync.protocol.config.StructureCryptConfig

/**
  * A trait that abstracts the creation of a [[SyncProtocol]].
  *
  * The trait defines a single factory function that expects some configuration
  * data and creates a specific [[SyncProtocol]].
  */
trait SyncProtocolFactory:
  /**
    * Creates a concrete ''SyncProtocol'' instance based on the parameters
    * provided.
    *
    * @param uri         the protocol-specific URI
    * @param cryptConfig configuration related to encryption
    * @return the resulting ''SyncProtocol''
    */
  def createProtocol(uri: String, cryptConfig: StructureCryptConfig): SyncProtocol
