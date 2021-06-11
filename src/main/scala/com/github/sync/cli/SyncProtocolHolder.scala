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

package com.github.sync.cli

import com.github.sync.protocol.SyncProtocol

import scala.concurrent.{ExecutionContext, Future}

/**
  * A class that holds the [[SyncProtocol]] objects used by the current sync
  * process.
  *
  * There is a factory function to create an instance with protocol objects
  * created from the configurations for the source and destination structures.
  * From this instance then the components required for the sync process can be
  * obtained, such as sources or handlers.
  *
  * There is also support for cleaning up resources when a sync process
  * completes.
  *
  * @param srcProtocol the protocol for the source structure
  * @param dstProtocol the protocol for the destination structure
  */
class SyncProtocolHolder(srcProtocol: SyncProtocol, dstProtocol: SyncProtocol) {
  /**
    * Registers a handler at the given ''Future'' that closes the managed
    * protocols when the future completes (either successfully or with a
    * failure). This function should be called to register this handler on the
    * main future of the sync process to make sure that the protocols are
    * released properly at the end of the process.
    *
    * @param future the ''Future'' to register the handler
    * @param ec     the execution context
    * @tparam A the result type of the future
    * @return the ''Future'' with the handler registered
    */
  def registerCloseHandler[A](future: Future[A])(implicit ec: ExecutionContext): Future[A] =
    future.andThen {
      case _ =>
        srcProtocol.close()
        dstProtocol.close()
    }
}
