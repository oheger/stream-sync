/*
 * Copyright 2018-2019 The Developers Team.
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
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.stream.scaladsl.{Broadcast, FileIO, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import akka.stream.{ActorMaterializer, ClosedShape, Graph, SourceShape}
import akka.util.Timeout
import com.github.sync.SyncTypes._
import com.github.sync._
import com.github.sync.local.{LocalFsConfig, LocalFsElementSource, LocalSyncOperationActor, LocalUriResolver}
import com.github.sync.log.ElementSerializer
import com.github.sync.webdav.{DavConfig, DavFsElementSource, DavOperationHandler, DavSourceFileProvider, HttpBasicAuthActor, HttpRequestActor}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Implementation of the ''SyncStreamFactory'' trait.
  */
object SyncStreamFactoryImpl extends SyncStreamFactory {
  /** URI prefix indicating a WebDav structure. */
  val PrefixWebDav = "dav:"

  /** The name of the actor for local sync operations. */
  val LocalSyncOpActorName = "localSyncOperationActor"

  /** Regular expression for parsing a WebDav URI. */
  private val RegDavUri = (PrefixWebDav + "(.+)").r

  /** The name of the blocking dispatcher. */
  private val BlockingDispatcherName = "blocking-dispatcher"

  override def additionalArguments(uri: String, structureType: StructureType)
                                  (implicit ec: ExecutionContext):
  Future[Iterable[SupportedArgument]] = {
    val args = uri match {
      case RegDavUri(_*) => DavConfig.supportedArgumentsFor(structureType)
      case _ => LocalFsConfig.supportedArgumentsFor(structureType)
    }
    Future.successful(args)
  }

  override def createSyncInputSource[T](uri: String, optTransformer: Option[ResultTransformer[T]],
                                        structureType: StructureType, startFolderUri: String = "")
                                       (implicit ec: ExecutionContext, system: ActorSystem,
                                        mat: ActorMaterializer, timeout: Timeout):
  ArgsFunc[Source[FsElement, Any]] = {
    val factory = createElementSourceFactory(optTransformer)
    uri match {
      case RegDavUri(davUri) =>
        args =>
          DavConfig(structureType, davUri, timeout, args) map (conf =>
            DavFsElementSource(conf, factory, createHttpRequestActor(conf, system, clientCount = 1,
              "httpRequestActorDest"), startFolderUri))
      case _ =>
        args =>
          LocalFsConfig(structureType, uri, args) map { config =>
            createLocalFsElementSource(config, factory, startFolderUri)
          }
    }
  }

  override def createSourceFileProvider(uri: String)(implicit ec: ExecutionContext,
                                                     system: ActorSystem,
                                                     mat: ActorMaterializer,
                                                     timeout: Timeout):
  ArgsFunc[SourceFileProvider] = uri match {
    case RegDavUri(davUri) =>
      args =>
        DavConfig(SourceStructureType, davUri, timeout, args) map { conf =>
          DavSourceFileProvider(conf, createHttpRequestActor(conf, system, clientCount = 1,
            "httpRequestActorSrc"))
        }
    case _ =>
      _ => Future.successful(new LocalUriResolver(Paths get uri))
  }

  override def createSourceComponents[T](uri: String, optTransformer: Option[ResultTransformer[T]])
                                        (implicit ec: ExecutionContext, system: ActorSystem, mat: ActorMaterializer,
                                         timeout: Timeout): ArgsFunc[SyncSourceComponents[FsElement]] = {
    val factory = createElementSourceFactory(optTransformer)
    uri match {
      case RegDavUri(davUri) =>
        args =>
          DavConfig(SourceStructureType, davUri, timeout, args) map { conf =>
            val requestActor = createHttpRequestActor(conf, system, clientCount = 2, "httpRequestActorSrc")
            val source = DavFsElementSource(conf, factory, requestActor)
            val fileProvider = DavSourceFileProvider(conf, requestActor)
            SyncSourceComponents(source, fileProvider)
          }
      case _ =>
        args =>
          LocalFsConfig(SourceStructureType, uri, args) map { config =>
            val source = createLocalFsElementSource(config, factory)
            val fileProvider = new LocalUriResolver(Paths get uri)
            SyncSourceComponents(source, fileProvider)
          }
    }
  }

  override def createApplyStage(uriDst: String, fileProvider: SourceFileProvider)
                               (implicit system: ActorSystem, mat: ActorMaterializer,
                                ec: ExecutionContext, timeout: Timeout):
  ArgsFunc[Flow[SyncOperation, SyncOperation, NotUsed]] = uriDst match {
    case RegDavUri(davUri) =>
      args =>
        DavConfig(DestinationStructureType, davUri, timeout, args) map { conf =>
          val requestActor = createHttpRequestActor(conf, system, 1, "httpRequestActorSync")
          cleanUpFileProvider(DavOperationHandler.webDavProcessingFlow(conf, fileProvider, requestActor),
            fileProvider)
        }
    case _ =>
      args =>
        LocalFsConfig(DestinationStructureType, uriDst, args) map { conf =>
          createLocalFsApplyStage(conf, fileProvider)
        }
  }

  override def createSyncSource[T2](srcSource: Source[FsElement, Any],
                                    uriDst: String, optDstTransformer: Option[ResultTransformer[T2]],
                                    additionalArgs: StructureArgs, ignoreTimeDelta: Int)
                                   (implicit ec: ExecutionContext, system: ActorSystem,
                                    mat: ActorMaterializer, timeout: Timeout):
  Future[Source[SyncOperation, NotUsed]] =
    createSyncInputSource(uriDst, optDstTransformer, DestinationStructureType).apply(additionalArgs)
      .map(dstSource => createGraphForSyncSource(srcSource, dstSource, ignoreTimeDelta))

  override def createSyncStream(source: Source[SyncOperation, Any],
                                flowProc: Flow[SyncOperation, SyncOperation, Any],
                                logFile: Option[Path])
                               (implicit ec: ExecutionContext):
  Future[RunnableGraph[Future[(Int, Int)]]] = Future {
    val sinkCount = Sink.fold[Int, SyncOperation](0) { (c, _) => c + 1 }
    val sinkLogFile = createLogSink(logFile)
    RunnableGraph.fromGraph(GraphDSL.create(sinkCount, sinkCount, sinkLogFile)(combineMat) {
      implicit builder =>
        (sinkTotal, sinkSuccess, sinkLog) =>
          import GraphDSL.Implicits._
          val broadcastSink = builder.add(Broadcast[SyncOperation](2))
          val broadcastSuccess = builder.add(Broadcast[SyncOperation](2))
          source ~> broadcastSink ~> sinkTotal.in
          broadcastSink ~> flowProc ~> broadcastSuccess ~> sinkSuccess.in
          broadcastSuccess ~> sinkLog
          ClosedShape
    })
  }

  /**
    * Creates a factory for creating an element source. This factory is needed
    * for creating the concrete sources of the sync process.
    *
    * @param optTransformer an optional result transformer
    * @param ec             the execution context
    * @return the ''ElementSourceFactory''
    */
  private def createElementSourceFactory[T](optTransformer: Option[ResultTransformer[T]])
                                           (implicit ec: ExecutionContext): ElementSourceFactory =
    new ElementSourceFactory {
      override def createElementSource[F, S](initState: S, initFolder: SyncFolderData[F],
                                             optCompletionFunc: Option[CompletionFunc[S]])
                                            (iterateFunc: IterateFunc[F, S]):
      Graph[SourceShape[FsElement], NotUsed] =
        new ElementSource[F, S, T](initState, initFolder, optCompleteFunc = optCompletionFunc,
          optTransformFunc = optTransformer)(iterateFunc)
    }

  /**
    * Creates the element source for the local file system.
    *
    * @param config         the configuration for the local file system
    * @param factory        the ''ElementSourceFactory''
    * @param startFolderUri the URI of the start folder for the iteration
    * @param ec             the execution context
    * @return the element source for the local file system
    */
  private def createLocalFsElementSource(config: LocalFsConfig, factory: ElementSourceFactory,
                                         startFolderUri: String = "")
                                        (implicit ec: ExecutionContext): Source[FsElement, NotUsed] =
    LocalFsElementSource(config, startDirectory = startFolderUri)(factory).via(new FolderSortStage)

  /**
    * Creates the apply stage to change a local file system.
    *
    * @param config       the configuration for the local FS stage
    * @param fileProvider the ''SourceFileProvider''
    * @param system       the actor system
    * @param timeout      a timeout
    * @return the apply stage for a local file system
    */
  private def createLocalFsApplyStage(config: LocalFsConfig, fileProvider: SourceFileProvider)
                                     (implicit system: ActorSystem, timeout: Timeout):
  Flow[SyncOperation, SyncOperation, NotUsed] = {
    val operationActor = system.actorOf(Props(classOf[LocalSyncOperationActor],
      fileProvider, config, BlockingDispatcherName), LocalSyncOpActorName)
    stopActor(cleanUpFileProvider(Flow[SyncOperation].mapAsync(1) { op =>
      val futWrite = operationActor ? op
      futWrite.mapTo[SyncOperation]
    }, fileProvider), operationActor)
  }

  /**
    * Creates the actor for executing HTTP requests.
    *
    * @param conf        the DAV configuration
    * @param system      the actor system
    * @param clientCount the number of clients for the actor
    * @param name        the name of the request actor
    * @return the actor for HTTP requests
    */
  private def createHttpRequestActor(conf: DavConfig, system: ActorSystem, clientCount: Int, name: String)
  : ActorRef = {
    val httpActor = system.actorOf(HttpRequestActor(conf.rootUri), name)
    system.actorOf(HttpBasicAuthActor(httpActor, conf, clientCount), name + "_auth")
  }

  /**
    * Appends a clean-up stage to the given stage to make sure that the file
    * provider specified is always gracefully shutdown.
    *
    * @param stage        the stage to be extended
    * @param fileProvider the ''SourceFileProvider'' to shutdown
    * @return the enhanced stage
    */
  private def cleanUpFileProvider(stage: Flow[SyncOperation, SyncOperation, NotUsed],
                                  fileProvider: SourceFileProvider):
  Flow[SyncOperation, SyncOperation, NotUsed] =
    stage via new CleanupStage[SyncOperation](() => fileProvider.shutdown())

  /**
    * Appends a clean-up stage to the given stage that stops the given actor.
    * This is used to make sure that all actors used during the sync process
    * are stopped afterwards.
    *
    * @param stage the stage to be extended
    * @param actor the actor to be stopped
    * @return the enhanced stage
    */
  private def stopActor(stage: Flow[SyncOperation, SyncOperation, NotUsed], actor: ActorRef):
  Flow[SyncOperation, SyncOperation, NotUsed] =
    stage via new CleanupStage[SyncOperation](() => actor ! PoisonPill)

  /**
    * Creates a ''Source'' that produces ''SyncOperation'' objects to sync the
    * input sources provided.
    *
    * @param srcSource       the source for the source structure
    * @param dstSource       the source for the destination structure
    * @param ignoreTimeDelta the time delta between two files to ignore
    * @return the sync source
    */
  private def createGraphForSyncSource(srcSource: Source[FsElement, Any],
                                       dstSource: Source[FsElement, Any],
                                       ignoreTimeDelta: Int): Source[SyncOperation, NotUsed] =
    Source.fromGraph(GraphDSL.create() {
      implicit builder =>
        import GraphDSL.Implicits._
        val syncStage = builder.add(new SyncStage(ignoreTimeDelta))
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
