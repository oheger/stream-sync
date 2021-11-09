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

import akka.Done
import akka.stream.{ClosedShape, IOResult, SinkShape}
import akka.stream.scaladsl.{Broadcast, FileIO, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import com.github.sync.SyncTypes.{SyncOperation, SyncOperationResult}
import com.github.sync.log.ElementSerializer

import java.nio.file.{Path, StandardOpenOption}
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
    RunnableGraph.fromGraph(GraphDSL.createGraph(params.sinkTotal, params.sinkError)(createStreamMat) {
      implicit builder =>
        (sinkTotal, sinkError) =>
          import GraphDSL.Implicits._
          val broadcastSink = builder.add(Broadcast[SyncOperationResult](2))
          params.source ~> params.processFlow ~> broadcastSink ~> sinkTotal.in
          broadcastSink ~> filterError ~> sinkError.in
          ClosedShape
    })

  /**
    * Creates a ''Sink'' that logs the received [[SyncOperation]]s to a file.
    *
    * @param logFile the path to the log file
    * @return the ''Sink'' that writes the log file
    */
  def createLogSink(logFile: Path): Sink[SyncOperationResult, Future[IOResult]] =
    val sink = FileIO.toPath(logFile, options = Set(StandardOpenOption.WRITE,
      StandardOpenOption.CREATE, StandardOpenOption.APPEND))
    val serialize = Flow[SyncOperationResult].map(ElementSerializer.serializeOperationResult)
    serialize.toMat(sink)(Keep.right)

  /**
    * Creates a combined ''Sink'' from the given sink that also logs all
    * received [[SyncOperationResult]]s to a file.
    *
    * @param sink    the sink to be decorated
    * @param logFile the path to the log file
    * @tparam MAT the type of the materialized value of the original sink
    * @return the combined sink that performs logging
    */
  def sinkWithLogging[MAT](sink: Sink[SyncOperationResult, Future[MAT]], logFile: Path)
                          (implicit ec: ExecutionContext): Sink[SyncOperationResult, Future[MAT]] =
    combinedSink(sink, createLogSink(logFile))

  /**
    * Creates a ''Sink'' that counts the received results for sync
    * operations. This can be useful to gather statistics about the
    * operations that were executed.
    *
    * @return the ''Sink'' that counts the received elements
    */
  def createCountSink(): Sink[SyncOperationResult, Future[Int]] =
    Sink.fold[Int, SyncOperationResult](0) { (cnt, _) => cnt + 1 }

  /**
    * Creates a combined ''Sink'' for [[SyncOperationResult]]s that produces
    * the same materialized result as the original sink. For the second sink,
    * the result is obtained - thus making sure that it is completed -, but it
    * is dropped and not further evaluated. The main idea behind this function
    * is to enable some kind of logging for sink operations, in addition to a
    * sink that actually produces a value.
    *
    * @param orgSink   the original ''Sink'' that produces a value
    * @param otherSink another ''Sink'' to be combined
    * @param ec        the execution context
    * @tparam MAT the type of the materialized result of the first sink
    * @tparam T   the type of the other sink
    * @return the combined sink
    */
  def combinedSink[MAT, T](orgSink: Sink[SyncOperationResult, Future[MAT]],
                           otherSink: Sink[SyncOperationResult, Future[T]])
                          (implicit ec: ExecutionContext): Sink[SyncOperationResult, Future[MAT]] =
    Sink.fromGraph(GraphDSL.createGraph(otherSink, orgSink)(createCombinedSinkMat) {
      implicit builder =>
        (ignoreSink, resultSink) =>
          import GraphDSL.Implicits._
          val broadcast = builder.add(Broadcast[SyncOperationResult](2))
          broadcast ~> ignoreSink.in
          broadcast ~> resultSink.in
          SinkShape(broadcast.in)
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
  private def createStreamMat[TOTAL, ERROR](matTotal: Future[TOTAL], matError: Future[ERROR])
                                           (implicit ec: ExecutionContext): Future[SyncStreamMat[TOTAL, ERROR]] =
    for
      total <- matTotal
      error <- matError
    yield SyncStreamMat(total, error)

  /**
    * Constructs the materialized value of a sink that has been combined with a
    * another sink whose materialized value is to be ignred. Although only the
    * value of the original sink is relevant, it is necessary to wait for the
    * completion of the other sink, too.
    *
    * @param matIgnore the materialized value of the sink to be ignored
    * @param matOrg    the materialized value of the original sink
    * @param ec        the execution context
    * @tparam MAT the type of the original materialized value
    * @tparam T   the type of the materialized value to be ignored
    * @return the combined materialized value
    */
  private def createCombinedSinkMat[MAT, T](matIgnore: Future[T], matOrg: Future[MAT])
                                           (implicit ec: ExecutionContext): Future[MAT] =
    for
      _ <- matIgnore
      result <- matOrg
    yield result
