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

package com.github.sync.impl

import akka.NotUsed
import akka.stream.scaladsl.Flow
import com.github.sync.SyncTypes.SyncOperation
import com.github.sync.http.SyncOperationRequestActor.SyncOperationResult

import scala.concurrent.ExecutionContext

/**
  * A module that produces a flow stage in the sync stream that applies sync
  * operations against the destination structure based on a
  * [[ProtocolOperationHandler]].
  *
  * The stage expects ''SyncOperation'' objects as input. I passes these
  * objects to its associated [[ProtocolOperationHandler]]. It then returns a
  * [[SyncOperationResult]] with the result of the operation.
  *
  * This stage also implements error handling. If the execution of the sync
  * operation fails, the failed future is mapped to a result that contains the
  * original exception.
  */
object ProtocolOperationHandlerStage {
  /**
    * Returns a flow stage that processes [[SyncOperation]] objects using the
    * specified ''ProtocolOperationHandler''.
    *
    * @param handler the ''ProtocolOperationHandler''
    * @param ec      the execution context
    * @return the flow stage
    */
  def apply(handler: ProtocolOperationHandler)
           (implicit ec: ExecutionContext): Flow[SyncOperation, SyncOperationResult, NotUsed] =
    Flow[SyncOperation].mapAsync(1) { op =>
      handler.execute(op) map { _ => SyncOperationResult(op, None) } recover {
        case e => SyncOperationResult(op, Some(e))
      }
    }
}
