/*
 * Copyright 2018-2020 The Developers Team.
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

import java.nio.file.{Path, StandardOpenOption}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.{Broadcast, FileIO, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes._
import com.github.sync.cli.FilterManager.SyncFilterData
import com.github.sync.cli.SyncComponentsFactory.{ApplyStageData, DestinationComponentsFactory, SourceComponentsFactory}
import com.github.sync.cli.SyncParameterManager.{CryptMode, SyncConfig}
import com.github.sync.crypt.CryptService.IterateSourceFunc
import com.github.sync.crypt.{CryptService, CryptStage}
import com.github.sync.impl._
import com.github.sync.log.{ElementSerializer, SerializerStreamHelper}
import com.github.sync.util.LRUCache

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Main object to start the sync process.
  */
object Sync {

  /**
    * A class representing the result of a sync process.
    *
    * From the properties of this class client code can learn how many sync
    * operations have been executed during the sync process and how many have
    * been successful.
    *
    * @param totalOperations      the total number of sync operations
    * @param successfulOperations the number of successful operations
    */
  case class SyncResult(totalOperations: Int, successfulOperations: Int)

  def main(args: Array[String]): Unit = {
    val sync = new Sync
    sync.run(args)
  }

  /**
    * Implements a sync process based on the parameters provided. Result is a
    * future with a tuple of Int values. The first element is the total number
    * of sync operations that have been executed; the second element is the
    * number of successful sync operations.
    *
    * @param factory the factory for the sync stream
    * @param args    the array with command line arguments
    * @param system  the actor system
    * @param mat     the object to materialize streams
    * @param ec      the execution context
    * @return a future with information about the result of the process
    */
  def syncProcess(factory: SyncComponentsFactory, args: Array[String])
                 (implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext):
  Future[SyncResult] = {
    implicit val consoleReader: ConsoleReader = DefaultConsoleReader

    for {argsMap <- ParameterManager.parseParameters(args)
         (argsMap1, config) <- SyncParameterManager.extractSyncConfig(argsMap)
         (argsMap2, filterData) <- FilterManager.parseFilters(argsMap1)
         (argsMap3, srcFactory) <- factory.createSourceComponentsFactory(
           config.syncUris._1, config.timeout, argsMap2)
         (argsMap4, dstFactory) <- factory.createDestinationComponentsFactory(
           config.syncUris._2, config.timeout, argsMap3)
         _ <- ParameterManager.checkParametersConsumed(argsMap4)
         result <- runSync(config, filterData, srcFactory, dstFactory)
         } yield result
  }

  /**
    * Runs the stream that represents the sync process.
    *
    * @param config     the ''SyncConfig''
    * @param filterData data about the current filter definition
    * @param srcFactory the factory for creating source components
    * @param dstFactory the factory for creating destination components
    * @param system     the actor system
    * @param mat        the object to materialize streams
    * @return a future with information about the result of the process
    */
  private def runSync(config: SyncConfig, filterData: SyncFilterData,
                      srcFactory: SourceComponentsFactory, dstFactory: DestinationComponentsFactory)
                     (implicit system: ActorSystem, mat: ActorMaterializer): Future[SyncResult] = {
    import system.dispatcher
    for {
      source <- createSyncSource(config, srcFactory, dstFactory)
      decoratedSource <- decorateSource(source, config, filterData)
      stage <- createApplyStage(config, srcFactory, dstFactory)
      g <- createSyncStream(decoratedSource, stage, config.logFilePath)
      res <- g.run()
    } yield SyncResult(res._1, res._2)
  }

  /**
    * Creates the source for the sync process based on the given configuration.
    * Per default, a source is returned that determines the delta of two folder
    * structures. If however a sync log is provided, a source reading this file
    * is returned.
    *
    * @param config     the sync configuration
    * @param srcFactory the factory for creating source components
    * @param dstFactory the factory for creating destination components
    * @param ec         the execution context
    * @param mat        the object to materialize streams
    * @return the source for the sync process
    */
  private def createSyncSource(config: SyncConfig, srcFactory: SourceComponentsFactory,
                               dstFactory: DestinationComponentsFactory)
                              (implicit ec: ExecutionContext, mat: ActorMaterializer):
  Future[Source[SyncOperation, Any]] = config.syncLogPath match {
    case Some(path) =>
      createSyncSourceFromLog(config, path)
    case None =>
      val srcSource = srcFactory.createSource(createElementSourceFactory(createResultTransformer(config.srcPassword,
        config.srcCryptMode, config.cryptCacheSize)))
      val dstSource = dstFactory.createDestinationSource(createElementSourceFactory(
        createResultTransformer(config.dstPassword, config.dstCryptMode, config.cryptCacheSize)))
      Future.successful(createGraphForSyncSource(srcSource, dstSource, config.ignoreTimeDelta getOrElse 1))
  }

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
    * Applies some further configuration options to the source of the sync
    * process, such as filtering or throttling.
    *
    * @param source     the original source
    * @param config     the sync configuration
    * @param filterData data about the current filter definition
    * @return the decorated source
    */
  private def decorateSource(source: Source[SyncOperation, Any], config: SyncConfig, filterData: SyncFilterData):
  Future[Source[SyncOperation, Any]] = {
    val filteredSource = source.filter(createSyncFilter(filterData))
    val throttledSource = config.opsPerSecond match {
      case Some(value) =>
        filteredSource.throttle(value, 1.second)
      case None => filteredSource
    }
    Future.successful(throttledSource)
  }

  /**
    * Returns a ''ResultTransformer'' for an element source based on the given
    * parameters. The transformer makes sure that the results produced by an
    * element source are compatible with the parameters passed in.
    *
    * @param optCryptPwd    the optional encryption password
    * @param cryptMode      the crypt mode
    * @param cryptCacheSize size of the cache for encrypted names
    * @param ec             the execution context
    * @param mat            the object to materialize streams
    * @return the ''ResultTransformer'' for these parameters
    */
  private[cli] def createResultTransformer(optCryptPwd: Option[String], cryptMode: CryptMode.Value,
                                           cryptCacheSize: Int)
                                          (implicit ec: ExecutionContext, mat: ActorMaterializer):
  Option[ResultTransformer[LRUCache[String, String]]] =
    optCryptPwd.map { pwd =>
      val optNameKey = if (cryptMode == CryptMode.FilesAndNames) Some(CryptStage.keyFromString(pwd)) else None
      CryptService.cryptTransformer(optNameKey, cryptCacheSize)
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
    * Creates the source for the sync process if a sync log is provided. The
    * exact source to be used depends on the configuration of a processed log.
    *
    * @param config      the sync configuration
    * @param syncLogPath the path to the sync log
    * @param ec          the execution context
    * @param mat         the object to materialize streams
    * @return the source to read from a sync log file
    */
  private def createSyncSourceFromLog(config: SyncConfig, syncLogPath: Path)
                                     (implicit ec: ExecutionContext, mat: ActorMaterializer):
  Future[Source[SyncOperation, Any]] = config.logFilePath match {
    case Some(processedLog) =>
      SerializerStreamHelper.createSyncOperationSourceWithProcessedLog(syncLogPath, processedLog)
    case None =>
      Future.successful(SerializerStreamHelper.createSyncOperationSource(syncLogPath))
  }

  /**
    * Creates the flow stage that applies sync operations based on the given
    * sync config. If the apply mode does not require any actions, a dummy flow
    * is returned that passes all operations through.
    *
    * @param config     the sync configuration
    * @param srcFactory the factory for creating source components
    * @param dstFactory the factory for creating destination components
    * @param ec         the execution context
    * @param mat        the object to materialize streams
    * @return a future with the flow to apply sync operations
    */
  private def createApplyStage(config: SyncConfig, srcFactory: SourceComponentsFactory,
                               dstFactory: DestinationComponentsFactory)
                              (implicit ec: ExecutionContext,
                               mat: ActorMaterializer):
  Future[Flow[SyncOperation, SyncOperation, NotUsed]] = Future {
    config.applyMode match {
      case SyncParameterManager.ApplyModeTarget(targetUri) =>
        val sourceFileProvider = decorateSourceFileProvider(srcFactory.createSourceFileProvider(), config)
        val stageData = dstFactory.createApplyStage(targetUri, sourceFileProvider)
        addCleanUp(decorateApplyStage(config, dstFactory, stageData.stage), stageData, sourceFileProvider)

      case SyncParameterManager.ApplyModeNone =>
        Flow[SyncOperation].map(identity)
    }
  }

  /**
    * Decorates the apply stage to fit into the parametrized sync stream. If
    * necessary, special preparation stages are added before the apply stage
    * to transform the operations to be processed accordingly.
    *
    * @param config     the sync configuration
    * @param dstFactory the factory for creating destination components
    * @param stage      the apply stage to be decorated
    * @param ec         the execution context
    * @param mat        the object to materialize streams
    * @return the decorated apply stage
    */
  private def decorateApplyStage(config: SyncConfig, dstFactory: DestinationComponentsFactory,
                                 stage: Flow[SyncOperation, SyncOperation, NotUsed])
                                (implicit ec: ExecutionContext, mat: ActorMaterializer):
  Flow[SyncOperation, SyncOperation, NotUsed] =
    if (config.dstPassword.isEmpty || config.dstCryptMode != CryptMode.FilesAndNames) stage
    else {
      val sourceFactory = createElementSourceFactory(None)
      val srcFunc: IterateSourceFunc = startFolderUri => {
        Future.successful(dstFactory.createPartialSource(sourceFactory, startFolderUri))
      }
      val cryptFunc = CryptService.mapOperationFunc(CryptStage.keyFromString(config.dstPassword.get), srcFunc)
      val cryptStage = new StatefulStage[SyncOperation, SyncOperation,
        LRUCache[String, String]](LRUCache[String, String](config.cryptCacheSize))(cryptFunc)
      Flow[SyncOperation].via(cryptStage).via(stage)
    }

  /**
    * Appends a [[CleanupStage]] to the given flow for the apply stage. This
    * ensures that all resources used by the apply stage are released when the
    * stream completes. The source file provider is shutdown as well.
    *
    * @param stage              the (decorated) apply stage
    * @param stageData          the data object about the apply stage
    * @param sourceFileProvider the source file provider
    * @return the apply stage with the cleanup stage appended
    */
  private def addCleanUp(stage: Flow[SyncOperation, SyncOperation, NotUsed], stageData: ApplyStageData,
                         sourceFileProvider: SourceFileProvider): Flow[SyncOperation, SyncOperation, NotUsed] = {
    val cleanUp = () => {
      stageData.cleanUp()
      sourceFileProvider.shutdown()
    }
    stage.via(new CleanupStage[SyncOperation](cleanUp))
  }

  /**
    * Decorates the given ''SourceFileProvider'' if necessary to support
    * advanced features like encryption.
    *
    * @param provider the original ''SourceFileProvider''
    * @param config   the sync configuration
    * @param ec       the execution context
    * @return the decorated ''SourceFileProvider''
    */
  private def decorateSourceFileProvider(provider: SourceFileProvider, config: SyncConfig)
                                        (implicit ec: ExecutionContext): SourceFileProvider =
    if (config.srcPassword.nonEmpty || config.dstPassword.nonEmpty)
      CryptAwareSourceFileProvider(provider, config.srcPassword, config.dstPassword)
    else provider

  /**
    * Creates a ''RunnableGraph'' representing the stream for a sync process.
    * The source for the ''SyncOperation'' objects to be processed is passed
    * in. The stream has three sinks that also determine the materialized
    * values of the graph: one sink counts all sync operations that need to be
    * executed; the second sink counts the sync operations that have been
    * processed successfully by the processing flow; the third sink is used to
    * write a log file, which contains all successfully executed sync
    * operations (it is active only if a path to a log file is provided).
    *
    * @param source   the source producing ''SyncOperation'' objects
    * @param flowProc the flow that processes sync operations
    * @param logFile  an optional path to a log file to write
    * @param ec       the execution context
    * @return a future with the runnable graph
    */
  def createSyncStream(source: Source[SyncOperation, Any],
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

  /**
    * Generates a predicate that filters out undesired sync operations based on
    * the filter parameters provided in the command line.
    *
    * @param filterData data about filter conditions
    * @return the predicate to filter undesired sync operations
    */
  private def createSyncFilter(filterData: SyncFilterData): SyncOperation => Boolean =
    op => FilterManager.applyFilter(op, filterData)

  /**
    * Generates a message about te outcome of the sync operation.
    *
    * @param totalCount   the total number of sync operations
    * @param successCount the number of successful sync operations
    * @return a message about the outcome of the sync operation
    */
  private def processedMessage(totalCount: Int, successCount: Int): String =
    if (totalCount == successCount)
      s"Successfully completed all ($totalCount) sync operations."
    else
      s"$successCount operations from $totalCount were successful."

  /**
    * Starts a sync process with the given parameters and returns a message
    * about the result in a ''Future''. Note that the ''Future'' returned by
    * this function never fails; if the sync process fails, it is completed
    * with a corresponding error message.
    *
    * @param factory the factory for creating stream components
    * @param args    the array with command line arguments
    * @param system  the actor system
    * @param mat     the object to materialize streams
    * @param ec      the execution context
    * @return a ''Future'' with a result message
    */
  private def syncWithResultMessage(factory: SyncComponentsFactory, args: Array[String])
                                   (implicit system: ActorSystem, mat: ActorMaterializer, ec: ExecutionContext):
  Future[String] =
    syncProcess(factory, args)
      .map(res => processedMessage(res.totalOperations, res.successfulOperations))
}

/**
  * The main class to execute sync processes.
  *
  * An instance of this class is created and invoked by the ''main()'' function
  * in the companion object. By extending [[ActorSystemLifeCycle]], this class
  * has access to an actor system and can therefore initiate the sync process.
  */
class Sync extends ActorSystemLifeCycle {
  override val name: String = "Sync"

  /**
    * @inheritdoc This implementation starts the sync process using the actor
    *             system in implicit scope.
    */
  override protected def runApp(args: Array[String]): Future[String] = {
    val factory = new SyncComponentsFactory
    Sync.syncWithResultMessage(factory, args)
  }

  /**
    * @inheritdoc This implementation creates a special materializer that can
    *             resume the stream on errors.
    */
  override def createStreamMat(): ActorMaterializer = {
    val decider: Supervision.Decider = _ => Supervision.Resume
    ActorMaterializer(ActorMaterializerSettings(actorSystem).withSupervisionStrategy(decider))
  }
}
