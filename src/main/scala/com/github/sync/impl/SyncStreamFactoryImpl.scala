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
import akka.stream.{ActorMaterializer, ClosedShape, SourceShape}
import akka.stream.scaladsl.{Broadcast, FileIO, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import akka.util.Timeout
import com.github.sync.local.{LocalFsElementSource, LocalSyncOperationActor, LocalUriResolver}
import com.github.sync.log.ElementSerializer
import com.github.sync._
import com.github.sync.webdav.{DavConfig, DavFsElementSource}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Implementation of the ''SyncStreamFactory'' trait.
  */
object SyncStreamFactoryImpl extends SyncStreamFactory {
  /** URI prefix indicating a WebDav structure. */
  val PrefixWebDav = "dav:"

  /** Regular expression for parsing a WebDav URI. */
  private val RegDavUri = (PrefixWebDav + "(.+)").r

  /** The name of the blocking dispatcher. */
  private val BlockingDispatcherName = "blocking-dispatcher"

  override def additionalArguments(uri: String, structureType: StructureType)
                                  (implicit ec: ExecutionContext):
  Future[Iterable[SupportedArgument]] = {
    val args = uri match {
      case RegDavUri(_*) => DavConfig.supportedArgumentsFor(structureType)
      case _ => List.empty
    }
    Future.successful(args)
  }

  override def createSyncInputSource(uri: String, structureType: StructureType)
                                    (implicit ec: ExecutionContext, system: ActorSystem,
                                     mat: ActorMaterializer):
  ArgsFunc[Source[FsElement, Any]] = uri match {
    case RegDavUri(davUri) =>
      args =>
        DavConfig(structureType, davUri, args) map (conf => DavFsElementSource(conf))
    case _ =>
      _ => Future.successful(LocalFsElementSource(Paths get uri).via(new FolderSortStage))
  }

  override def createSourceFileProvider(uri: String)(implicit ec: ExecutionContext,
                                                     system: ActorSystem,
                                                     mat: ActorMaterializer):
  ArgsFunc[SourceFileProvider] = _ => Future {
    new LocalUriResolver(Paths get uri)
  }

  override def createApplyStage(uriDst: String, fileProvider: SourceFileProvider)
                               (implicit system: ActorSystem, ec: ExecutionContext,
                                timeout: Timeout):
  ArgsFunc[Flow[SyncOperation, SyncOperation, NotUsed]] = _ => Future {
    val operationActor = system.actorOf(Props(classOf[LocalSyncOperationActor],
      fileProvider, Paths get uriDst, BlockingDispatcherName))
    Flow[SyncOperation].mapAsync(1) { op =>
      val futWrite = operationActor ? op
      futWrite.mapTo[SyncOperation]
    } via new CleanupStage[SyncOperation](() => fileProvider.shutdown())
  }

  override def createSyncSource(uriSrc: String, uriDst: String, additionalArgs: StructureArgs)
                               (implicit ec: ExecutionContext, system: ActorSystem,
                                mat: ActorMaterializer):
  Future[Source[SyncOperation, NotUsed]] = for {
    srcSource <- createSyncInputSource(uriSrc, SourceStructureType).apply(additionalArgs)
    dstSource <- createSyncInputSource(uriDst, DestinationStructureType).apply(additionalArgs)
  } yield createGraphForSyncSource(srcSource, dstSource)

  override def createSyncStream(source: Source[SyncOperation, Any],
                                flowProc: Flow[SyncOperation, SyncOperation, Any],
                                logFile: Option[Path])
                               (opFilter: SyncOperation => Boolean)
                               (implicit ec: ExecutionContext):
  Future[RunnableGraph[Future[(Int, Int)]]] = Future {
    val sinkCount = Sink.fold[Int, SyncOperation](0) { (c, _) => c + 1 }
    val sinkLogFile = createLogSink(logFile)
    RunnableGraph.fromGraph(GraphDSL.create(sinkCount, sinkCount, sinkLogFile)(combineMat) {
      implicit builder =>
        (sinkTotal, sinkSuccess, sinkLog) =>
          import GraphDSL.Implicits._
          val syncFilter = Flow[SyncOperation].filter(opFilter)
          val broadcast = builder.add(Broadcast[SyncOperation](3))
          source ~> syncFilter ~> broadcast ~> sinkTotal.in
          broadcast ~> flowProc ~> sinkSuccess.in
          broadcast ~> sinkLog
          ClosedShape
    })
  }

  /**
    * Creates a ''Source'' that produces ''SyncOperation'' objects to sync the
    * input sources provided.
    *
    * @param srcSource the source for the source structure
    * @param dstSource the source for the destination structure
    * @return the sync source
    */
  private def createGraphForSyncSource(srcSource: Source[FsElement, Any],
                                       dstSource: Source[FsElement, Any]):
  Source[SyncOperation, NotUsed] =
    Source.fromGraph(GraphDSL.create() {
      implicit builder =>
        import GraphDSL.Implicits._
        val syncStage = builder.add(new SyncStage)
        srcSource ~> syncStage.in0
        dstSource ~> syncStage.in1
        SourceShape(syncStage.out)
    })

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
