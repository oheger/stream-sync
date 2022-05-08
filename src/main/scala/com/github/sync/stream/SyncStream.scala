/*
 * Copyright 2018-2022 The Developers Team.
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

import akka.stream.scaladsl.{Broadcast, FileIO, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import akka.stream.{ClosedShape, IOResult, SharedKillSwitch, SinkShape, SourceShape}
import akka.{Done, NotUsed}
import com.github.sync.SyncTypes.{FsElement, SyncOperation, SyncOperationResult}
import com.github.sync.log.ElementSerializer

import java.nio.file.{Path, StandardOpenOption}
import scala.concurrent.{ExecutionContext, Future}

/**
  * A module providing logic to construct streams for running sync processes of
  * different types.
  */
object SyncStream:
  /**
    * Type alias for a function that filters for sync operations.
    */
  type OperationFilter = SyncOperation => Boolean

  /** Constant for an ''OperationFilter'' that accepts all operations. */
  final val AcceptAllOperations: OperationFilter = _ => true

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
    * A data class that holds the parameters common to all kinds of sync
    * streams. Parameters for specific stream types include an instance of
    * this class. It defines the following properties:
    *  - a flow stage that processes the sync operations;
    *  - a sink receiving the results of all sync operations;
    *  - a sink receiving the failed sync operations only;
    *  - a filter to be applied to sync operations;
    *  - an optional kill switch to cancel the stream from the outside.
    *
    * @param processFlow     the flow stage processing sync operations
    * @param sinkTotal       the sink receiving all operation results
    * @param sinkError       the sink receiving the failed operation results
    * @param operationFilter the filter for sync operations
    * @param optKillSwitch   an optional kill switch to cancel the stream
    * @tparam TOTAL the type of the value produced by the total sink
    * @tparam ERROR the type of the value produced by the error sink
    */
  case class BaseStreamParams[TOTAL, ERROR](processFlow: Flow[SyncOperation, SyncOperationResult, Any],
                                            sinkTotal: Sink[SyncOperationResult, Future[TOTAL]],
                                            sinkError: Sink[SyncOperationResult, Future[ERROR]] = Sink.ignore,
                                            operationFilter: OperationFilter = AcceptAllOperations,
                                            optKillSwitch: Option[SharedKillSwitch] = None)

  /**
    * A data class that holds the parameters of a mirror stream. Such a stream
    * makes a destination structure an exact mirror of a source structure.
    *
    * In addition to the [[BaseStreamParams]], this class defines the
    * (typically complex) source generating the sync operations to be applied
    * to the sync structures. In practice, this can either be a source that
    * compares two folder structures or a source that reads sync operations
    * from a log file.
    *
    * @param baseParams the object with basic stream parameters
    * @tparam TOTAL the type of the value produced by the total sink
    * @tparam ERROR the type of the value produced by the error sink
    */
  case class MirrorStreamParams[TOTAL, ERROR](baseParams: BaseStreamParams[TOTAL, ERROR],
                                              source: Source[SyncOperation, Any]):
    export baseParams.*

  /**
    * Creates a source for a mirror stream that mirrors a source folder
    * structure to a destination structure.
    *
    * @param srcSource       yields the elements of the source structure of
    *                        the mirror stream
    * @param srcDestination  yields the elements of the destination
    *                        structure of the mirror stream
    * @param ignoreTimeDelta a time difference in seconds that is to be
    *                        ignored when comparing two files
    * @return the source for the mirror stream
    */
  def createMirrorSource(srcSource: Source[FsElement, Any], srcDestination: Source[FsElement, Any],
                         ignoreTimeDelta: IgnoreTimeDelta = IgnoreTimeDelta.Zero): Source[SyncOperation, NotUsed] =
    Source.fromGraph(GraphDSL.create() {
      implicit builder =>
        import GraphDSL.Implicits.*
        val syncStage = builder.add(new MirrorStage(ignoreTimeDelta))
        srcSource ~> syncStage.in0
        srcDestination ~> syncStage.in1
        SourceShape(syncStage.out)
    })

  /**
    * Returns a ''RunnableGraph'' representing the mirror stream for the
    * parameters provided.
    *
    * @param params the parameters of the mirror stream
    * @param ec     the execution context
    * @tparam TOTAL the type of the value produced by the total sink
    * @tparam ERROR the type of the value produced by the error sink
    * @return the graph for the mirror stream
    */
  def createMirrorStream[TOTAL, ERROR](params: MirrorStreamParams[TOTAL, ERROR])
                                      (implicit ec: ExecutionContext):
  RunnableGraph[Future[SyncStreamMat[TOTAL, ERROR]]] =
    val filterOperations = Flow[SyncOperation].filter(params.operationFilter)
    val filterError = Flow[SyncOperationResult].filter(_.optFailure.isDefined)
    val sourceKS = params.optKillSwitch.fold(params.source) { ks =>
      params.source.via(ks.flow)
    }

    RunnableGraph.fromGraph(GraphDSL.createGraph(params.sinkTotal, params.sinkError)(createStreamMat) {
      implicit builder =>
        (sinkTotal, sinkError) =>
          import GraphDSL.Implicits.*
          val broadcastSink = builder.add(Broadcast[SyncOperationResult](2))
          sourceKS ~> filterOperations ~> params.processFlow ~> broadcastSink ~> sinkTotal.in
          broadcastSink ~> filterError ~> sinkError.in
          ClosedShape
    })

  /**
    * Creates a ''Sink'' that logs the received [[SyncOperation]]s to a file.
    * Optionally, the sink can be configured to log only failed operations,
    * together with the exceptions causing the failures. In this mode, the sink
    * acts as an error log.
    *
    * @param logFile  the path to the log file
    * @param errorLog flag whether only failures should be logged
    * @return the ''Sink'' that writes the log file
    */
  def createLogSink(logFile: Path, errorLog: Boolean = false): Sink[SyncOperationResult, Future[IOResult]] =
    val sink = FileIO.toPath(logFile, options = Set(StandardOpenOption.WRITE,
      StandardOpenOption.CREATE, StandardOpenOption.APPEND))
    val serialize = if errorLog then
      Flow[SyncOperationResult].filter(_.optFailure.isDefined)
        .map(result => ElementSerializer.serializeFailedOperation(result.op, result.optFailure.get))
    else
      Flow[SyncOperationResult].map(result => ElementSerializer.serialize(result.op))
    serialize.toMat(sink)(Keep.right)

  /**
    * Creates a combined ''Sink'' from the given sink that also logs all
    * received [[SyncOperationResult]]s to a file.
    *
    * @param sink     the sink to be decorated
    * @param logFile  the path to the log file
    * @param errorLog flag whether only failures should be logged
    * @tparam MAT the type of the materialized value of the original sink
    * @return the combined sink that performs logging
    */
  def sinkWithLogging[MAT](sink: Sink[SyncOperationResult, Future[MAT]], logFile: Path, errorLog: Boolean = false)
                          (implicit ec: ExecutionContext): Sink[SyncOperationResult, Future[MAT]] =
    combinedSink(sink, createLogSink(logFile, errorLog))

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
          import GraphDSL.Implicits.*
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
