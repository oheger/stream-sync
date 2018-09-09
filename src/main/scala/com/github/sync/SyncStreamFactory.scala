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

package com.github.sync

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, RunnableGraph, Sink, Source}
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}

/**
  * A trait that allows creating a sync stream with specific options.
  *
  * This trait defines operations to construct specific components of a stream
  * to sync two structures. Depending on the arguments passed to the sync CLI
  * (or the configuration of the sync process) slight variations of a sync
  * stream can be created. The resulting stream can then be run to actually
  * execute the sync process.
  */
trait SyncStreamFactory {
  /**
    * Creates a source for a sync stream based on the URI specified. The
    * concrete source implementation returned by an implementation depends on
    * the URI provided, as different protocols are supported.
    *
    * @param uri the URI for the sync source in question
    * @param ec  the execution context
    * @return a future with the newly created sync source
    */
  def createSyncInputSource(uri: String)(implicit ec: ExecutionContext):
  Future[Source[FsElement, Any]]

  /**
    * Creates a ''SourceFileProvider'' based on the URI provided. This is
    * needed to apply sync operations against destination structures.
    *
    * @param uri the URI of the source structure
    * @param ec  the execution context
    * @return a future with the ''SourceFileProvider''
    */
  def createSourceFileProvider(uri: String)(implicit ec: ExecutionContext):
  Future[SourceFileProvider]

  /**
    * Creates the flow stage that interprets sync operations and applies them
    * to the destination structure.
    *
    * @param uriDst       the URI to the destination structure
    * @param fileProvider the provider for files from the source structure
    * @param system       the actor system
    * @param ec           the execution context
    * @param timeout      a timeout when applying a sync operation
    * @return a future with the stage to process sync operations
    */
  def createApplyStage(uriDst: String, fileProvider: SourceFileProvider)
                      (implicit system: ActorSystem, ec: ExecutionContext, timeout: Timeout):
  Future[Flow[SyncOperation, SyncOperation, NotUsed]]

  /**
    * Creates a ''RunnableGraph'' representing the stream for a sync process.
    *
    * @param uriSrc   the URI for the source structure
    * @param uriDst   the URI for the destination structure
    * @param sinkRaw  the sink for the raw result
    * @param flowProc the flow that processes sync operations
    * @param sinkProc the sink for the processed operations
    * @param opFilter a filter on sync operations
    * @param system   the actor system
    * @param ec       the execution context
    * @tparam RAW  type of the raw sink
    * @tparam PROC type of processed sync operations
    * @tparam RES  type of the result sink
    * @return a future with the runnable graph
    */
  def createSyncStream[RAW, PROC, RES](uriSrc: String, uriDst: String,
                                       sinkRaw: Sink[SyncOperation, Future[RAW]],
                                       flowProc: Flow[SyncOperation, PROC, Any],
                                       sinkProc: Sink[PROC, Future[RES]])
                                      (opFilter: SyncOperation => Boolean)
                                      (implicit system: ActorSystem,
                                       ec: ExecutionContext):
  Future[RunnableGraph[(Future[RAW], Future[RES])]]
}
