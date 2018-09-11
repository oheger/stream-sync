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

package com.github.sync.impl

import java.nio.file.{Path, Paths, StandardOpenOption}

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.stream.ClosedShape
import akka.stream.scaladsl.{Broadcast, FileIO, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import akka.util.Timeout
import com.github.sync.local.{LocalFsElementSource, LocalSyncOperationActor, LocalUriResolver}
import com.github.sync.log.ElementSerializer
import com.github.sync.{FsElement, SourceFileProvider, SyncOperation, SyncStreamFactory}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Implementation of the ''SyncStreamFactory'' trait.
  */
object SyncStreamFactoryImpl extends SyncStreamFactory {
  override def createSyncInputSource(uri: String)(implicit ec: ExecutionContext):
  Future[Source[FsElement, Any]] = Future {
    LocalFsElementSource(Paths get uri).via(new FolderSortStage)
  }

  override def createSourceFileProvider(uri: String)(implicit ec: ExecutionContext):
  Future[SourceFileProvider] = Future {
    new LocalUriResolver(Paths get uri)
  }

  override def createApplyStage(uriDst: String, fileProvider: SourceFileProvider)
                               (implicit system: ActorSystem, ec: ExecutionContext,
                                timeout: Timeout):
  Future[Flow[SyncOperation, SyncOperation, NotUsed]] = Future {
    val operationActor = system.actorOf(Props(classOf[LocalSyncOperationActor],
      fileProvider, Paths get uriDst, "blocking-dispatcher"))
    Flow[SyncOperation].mapAsync(1) { op =>
      val futWrite = operationActor ? op
      futWrite.mapTo[SyncOperation]
    }
  }

  override def createSyncStream(uriSrc: String, uriDst: String,
                                flowProc: Flow[SyncOperation, SyncOperation, Any],
                                logFile: Option[Path])
                               (opFilter: SyncOperation => Boolean)
                               (implicit system: ActorSystem,
                                ec: ExecutionContext):
  Future[RunnableGraph[Future[(Int, Int)]]] = for {
    srcSource <- createSyncInputSource(uriSrc)
    dstSource <- createSyncInputSource(uriDst)
  } yield createSyncRunnableGraph(srcSource, dstSource, flowProc, logFile)(opFilter)

  /**
    * Creates a ''RunnableGraph'' representing the stream for a sync process
    * based on the parameters provided.
    *
    * @param srcSource the source for the source structure
    * @param dstSource the source for the destination structure
    * @param flowProc  the flow that processes sync operations
    * @param logFile   an optional path to a log file to be written
    * @param opFilter  a filter on sync operations
    * @param system    the actor system
    * @param ec        the execution context
    * @return the runnable graph
    */
  private def createSyncRunnableGraph(srcSource: Source[FsElement, Any],
                                      dstSource: Source[FsElement, Any],
                                      flowProc: Flow[SyncOperation, SyncOperation, Any],
                                      logFile: Option[Path])
                                     (opFilter: SyncOperation => Boolean)
                                     (implicit system: ActorSystem,
                                      ec: ExecutionContext):
  RunnableGraph[Future[(Int, Int)]] = {
    val sinkCount = Sink.fold[Int, SyncOperation](0) { (c, _) => c + 1 }
    val sinkLogFile = createLogSink(logFile)
    RunnableGraph.fromGraph(GraphDSL.create(sinkCount, sinkCount, sinkLogFile)(combineMat) {
      implicit builder =>
        (sinkTotal, sinkSuccess, sinkLog) =>
          import GraphDSL.Implicits._
          val syncFilter = Flow[SyncOperation].filter(opFilter)
          val syncStage = builder.add(new SyncStage)
          val broadcast = builder.add(Broadcast[SyncOperation](3))
          srcSource ~> syncStage.in0
          dstSource ~> syncStage.in1
          syncStage.out ~> syncFilter ~> broadcast ~> sinkTotal.in
          broadcast ~> flowProc ~> sinkSuccess.in
          broadcast ~> sinkLog
          ClosedShape
    })
  }

  /**
    * Generates the sink to write sync operations to a log file if a log file
    * path is specified. Otherwise, a dummy sink that ignores all data is
    * returned.
    *
    * @param logFile the option with the path to the log file
    * @return a sink to write a log file
    */
  private def createLogSink(logFile: Option[Path]): Sink[SyncOperation, Future[Any]] =
    logFile.map(createLogFileSink).getOrElse(Sink.ignore)

  /**
    * Generates the sink to write sync operations to a log file.
    *
    * @param logFile the path to the log file
    * @return the sink to write the log file
    */
  private def createLogFileSink(logFile: Path): Sink[SyncOperation, Future[Any]] = {
    val sink = FileIO.toPath(logFile, options = Set(StandardOpenOption.WRITE,
      StandardOpenOption.CREATE, StandardOpenOption.APPEND))
    val serialize = Flow[SyncOperation].map(ElementSerializer.serializeOperation)
    serialize.toMat(sink)(Keep.right)
  }

  /**
    * A function to combine the materialized values of the runnable graph for
    * the sync operation. The sinks used by the graph produce 3 future results.
    * This function converts this to a single future for a tuple of the
    * relevant values.
    *
    * @param futTotal   the future with the total number of operations
    * @param futSuccess the future with the number of successful operations
    * @param futLog     the future with the result of the log sink
    * @param ec         the execution context
    * @return a future with the results of the count sinks
    */
  private def combineMat(futTotal: Future[Int], futSuccess: Future[Int], futLog: Future[Any])
                        (implicit ec: ExecutionContext):
  Future[(Int, Int)] = for {
    total <- futTotal
    success <- futSuccess
    _ <- futLog
  } yield (total, success)
}
