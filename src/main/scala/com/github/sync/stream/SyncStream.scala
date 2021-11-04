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

package com.github.sync.stream

import akka.stream.ClosedShape
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink, Source}
import com.github.sync.SyncTypes.{SyncOperation, SyncOperationResult}
import com.github.sync.cli.Sync.combineMat

import scala.concurrent.{ExecutionContext, Future}

/**
  * A module providing logic to construct streams for running sync processes of
  * different types.
  */
object SyncStream:
  /**
    * A data class representing the materialized result of a sync stream.
    *
    * A sync stream has two sinks; one receiving all sync operations and one
    * receiving only the failed operations. These sinks can be configured, and
    * this class holds the materialized results of them.
    *
    * @param totalSinkMat the materialized value of the sink receiving all sync
    *                     operations
    * @param errorSinkMat the materialized value of the sink receiving the
    *                     failed sync operations
    * @tparam TOTAL the type of the value produced by the total sink
    * @tparam ERROR the type of the value produced by the error sink
    */
  case class SyncStreamMat[TOTAL, ERROR](totalSinkMat: TOTAL,
                                         errorSinkMat: ERROR)

  /**
    * A data class that holds the parameters of a sync stream.
    *
    * The stream consists of
    *  - a (typically complex) source generating the sync operations to be
    *    applied to the sync structures;
    *  - a flow stage that processes the sync operations;
    *  - a sink receiving the results of all sync operations;
    *  - a sink receiving the failed sync operations only.
    *    All these components can be specified via an instance of this class.
    *
    * @param source      the source of the sync stream
    * @param processFlow the flow stage processing sync operations
    * @param sinkTotal   the sink receiving all operation results
    * @param sinkError   the sink receiving the failed operation results
    * @tparam TOTAL the type of the value produced by the total sink
    * @tparam ERROR the type of the value produced by the error sink
    */
  case class SyncStreamParams[TOTAL, ERROR](source: Source[SyncOperation, Any],
                                            processFlow: Flow[SyncOperation, SyncOperationResult, Any],
                                            sinkTotal: Sink[SyncOperationResult, Future[TOTAL]],
                                            sinkError: Sink[SyncOperationResult, Future[ERROR]] = Sink.ignore)

  /**
    * Returns a ''RunnableGraph'' representing the sync stream for the
    * parameters provided.
    *
    * @param params the parameters of the sync stream
    * @param ec     the execution context
    * @tparam TOTAL the type of the value produced by the total sink
    * @tparam ERROR the type of the value produced by the error sink
    * @return the graph for the sync stream
    */
  def createSyncStream[TOTAL, ERROR](params: SyncStreamParams[TOTAL, ERROR])
                                    (implicit ec: ExecutionContext):
  RunnableGraph[Future[SyncStreamMat[TOTAL, ERROR]]] =
    val filterError = Flow[SyncOperationResult].filter(_.optFailure.isDefined)
    RunnableGraph.fromGraph(GraphDSL.createGraph(params.sinkTotal, params.sinkError)(createMat) {
      implicit builder =>
        (sinkTotal, sinkError) =>
          import GraphDSL.Implicits._
          val broadcastSink = builder.add(Broadcast[SyncOperationResult](2))
          params.source ~> params.processFlow ~> broadcastSink ~> sinkTotal.in
          broadcastSink ~> filterError ~> sinkError.in
          ClosedShape
    })

  /**
    * Constructs the materialized value of the sync stream from the results of
    * the two sinks. The values of the sinks are obtained and stored in a
    * [[SyncStreamMat]] object.
    *
    * @param matTotal the materialized value of the total sink
    * @param matError the materialized value of the error sink
    * @param ec       the execution context
    * @tparam TOTAL the type of the total sink
    * @tparam ERROR the type of the error sink
    * @return a ''SyncStreamMat'' object with the combined result
    */
  private def createMat[TOTAL, ERROR](matTotal: Future[TOTAL], matError: Future[ERROR])
                                     (implicit ec: ExecutionContext): Future[SyncStreamMat[TOTAL, ERROR]] =
    for
      total <- matTotal
      error <- matError
    yield SyncStreamMat(total, error)
