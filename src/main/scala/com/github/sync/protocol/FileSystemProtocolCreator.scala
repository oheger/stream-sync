/*
 * Copyright 2018-2024 The Developers Team.
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

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.Timeout

/**
  * A trait defining a number of creation functions to create the single
  * components required by a [[FileSystemSyncProtocol]].
  *
  * An object implementing this trait is associated with a
  * [[FileSystemSyncProtocolFactory]]. The factory delegates to it to create
  * the single components of the protocol and then takes care that they are
  * assembled correctly.
  *
  * @tparam ID     the type of IDs used by the file system
  * @tparam FILE   the type of files used by the file system
  * @tparam FOLDER the type of folders used by the file system
  * @tparam C      the type of the protocol-specific configuration
  */
trait FileSystemProtocolCreator[ID, FILE <: Model.File[ID], FOLDER <: Model.Folder[ID], C]:
  /**
    * Creates the ''FileSystem'' this protocol is based upon.
    *
    * @param uri     the protocol-specific root URI
    * @param config  the configuration of the protocol
    * @param timeout the timeout for file system operations
    * @return the ''FileSystem''
    */
  def createFileSystem(uri: String, config: C, timeout: Timeout):
  ExtensibleFileSystem[ID, FILE, FOLDER, Model.FolderContent[ID, FILE, FOLDER]]

  /**
    * Creates actor for sending HTTP requests. This actor is then passed to the
    * ''FileSystem'' for executing requests; so it has to be compatible with
    * the requirements of this file system. The configuration for this actor is
    * passed to this function, as it has been derived from global settings of
    * the sync process.
    *
    * @param spawner      the object to create new actors
    * @param factory      the ''HttpRequestSenderFactory''
    * @param uri          the protocol-specific root URI
    * @param config       the configuration of the protocol
    * @param senderConfig the configuration for the sender actor
    * @return the newly created actor reference
    */
  def createHttpSender(spawner: Spawner, factory: HttpRequestSenderFactory, uri: String, config: C,
                       senderConfig: HttpRequestSenderConfig): ActorRef[HttpRequestSender.HttpCommand]

  /**
    * Creates the ''FileSystemProtocolConverter'' to be used by this protocol.
    * This converter translates between the model used by sync processes and
    * the representations used by the underlying ''FileSystem''.
    *
    * @param config the configuration of the protocol
    * @return the ''FileSystemProtocolConverter'' for this protocol
    */
  def createConverter(config: C): FileSystemProtocolConverter[ID, FILE, FOLDER]
