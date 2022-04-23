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

package com.github.sync.cli

import akka.NotUsed
import akka.actor.typed.scaladsl.adapter.*
import akka.actor.{ActorSystem, typed}
import akka.stream.*
import akka.stream.scaladsl.{Broadcast, FileIO, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import com.github.cloudfiles.core.http.factory.Spawner
import com.github.scli.HelpGenerator.ParameterFilter
import com.github.scli.ParameterManager.ProcessingContext
import com.github.scli.{HelpGenerator, ParameterExtractor}
import com.github.sync.SyncTypes.*
import com.github.sync.SyncTypes.SyncAction.*
import com.github.sync.cli.FilterManager.SyncFilterData
import com.github.sync.cli.SyncCliStructureConfig.{DestinationRoleType, SourceRoleType}
import com.github.sync.cli.SyncParameterManager.SyncConfig
import com.github.sync.cli.SyncSetup.{AuthSetupFunc, ProtocolFactorySetupFunc}
import com.github.sync.stream.*
import com.github.sync.log.{ElementSerializer, SerializerStreamHelper}
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.Configurator

import java.nio.file.{Path, StandardOpenOption}
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * Main object to start the sync process.
  */
object Sync:
  /** The help text for the switch to display help information. */
  private val HelpOptionHelp =
    """Displays a help screen for the Sync application. Note that the content of this help screen \
      |depends on the parameters passed on the command line. If no URLs for the structures to sync \
      |are provided, the usage message lists only the basic options that are supported by all kinds \
      |of structures. If a specific source or destination URL is available, the parameters \
      |supported by this structure type are listed as well.""".stripMargin

  /**
    * A class representing the result of a sync process.
    *
    * From the properties of this class client code can learn how many sync
    * operations have been executed during the sync process and how many have
    * been successful or failed.
    *
    * @param successfulOperations the number of successful operations
    * @param failedOperations     the number of failed operations
    */
  case class SyncResult(successfulOperations: Int, failedOperations: Int):
    /**
      * Returns the total number of operations that have been executed during
      * this sync process.
      *
      * @return the total number of sync operations
      */
    def totalOperations: Int = successfulOperations + failedOperations

  def main(args: Array[String]): Unit =
    val sync = new Sync
    sync.run(args)

  /**
    * Implements a sync process based on the parameters provided. Result is a
    * future with a tuple of Int values. The first element is the total number
    * of sync operations that have been executed; the second element is the
    * number of successful sync operations.
    *
    * @param config            the sync configuration
    * @param authSetupFunc     the function to setup authentication
    * @param protocolSetupFunc the function to setup the protocol factories
    * @param system            the actor system
    * @param ec                the execution context
    * @return a future with information about the result of the process
    */
  def syncProcess(config: SyncConfig)(authSetupFunc: AuthSetupFunc)(protocolSetupFunc: ProtocolFactorySetupFunc)
                 (implicit system: ActorSystem, ec: ExecutionContext): Future[SyncResult] =
    val spawner: Spawner = system
    for
      holder <- SyncProtocolHolder(config, spawner)(authSetupFunc)(protocolSetupFunc)(system.toTyped)
      result <- runSync(config, spawner, holder)
    yield result

  /**
    * Runs the stream that represents the sync process.
    *
    * @param config         the ''SyncConfig''
    * @param spawner        the object to spawn actors
    * @param protocolHolder the ''SyncProtocolHolder''
    * @param system         the actor system
    * @param ec             the execution context
    * @return a future with information about the result of the process
    */
  private def runSync(config: SyncConfig, spawner: Spawner, protocolHolder: SyncProtocolHolder)
                     (implicit system: ActorSystem, ec: ExecutionContext): Future[SyncResult] =
    Configurator.setRootLevel(config.logConfig.logLevel)

    protocolHolder.registerCloseHandler(for
      source <- createSyncSource(config, protocolHolder)
      decoratedSource <- decorateSource(source, config, protocolHolder)
      stage <- createApplyStage(config, spawner, protocolHolder)
      g <- createSyncStream(decoratedSource, stage, config.logConfig.logFilePath, config.logConfig.errorLogFilePath)
      res <- g.run()
    yield SyncResult(res.totalSinkMat, res.errorSinkMat))

  /**
    * Creates the source for the sync process based on the given configuration.
    * Per default, a source is returned that determines the delta of two folder
    * structures. If however a sync log is provided, a source reading this file
    * is returned.
    *
    * @param config         the sync configuration
    * @param protocolHolder the ''SyncProtocolHolder''
    * @param ec             the execution context
    * @param system         the actor system
    * @return the source for the sync process
    */
  private def createSyncSource(config: SyncConfig, protocolHolder: SyncProtocolHolder)
                              (implicit ec: ExecutionContext, system: ActorSystem):
  Future[Source[SyncOperation, Any]] = config.logConfig.syncLogPath match
    case Some(path) =>
      createSyncSourceFromLog(config, path)
    case None =>
      val srcSource = protocolHolder.createSourceElementSource()
      val dstSource = protocolHolder.createDestinationElementSource()
      Future.successful(createGraphForSyncSource(srcSource, dstSource,
        config.streamConfig.ignoreTimeDelta getOrElse 1))

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
        val syncStage = builder.add(new MirrorStage(ignoreTimeDelta))
        srcSource ~> syncStage.in0
        dstSource ~> syncStage.in1
        SourceShape(syncStage.out)
    })

  /**
    * Applies some further configuration options to the source of the sync
    * process, such as filtering.
    *
    * @param source         the original source
    * @param config         the sync configuration
    * @param protocolHolder the ''SyncProtocolHolder''
    * @return the decorated source
    */
  private def decorateSource(source: Source[SyncOperation, Any], config: SyncConfig,
                             protocolHolder: SyncProtocolHolder): Future[Source[SyncOperation, Any]] =
    val filteredSource = source.filter(createSyncFilter(config.filterData))
    Future.successful(filteredSource.via(protocolHolder.oAuthRefreshKillSwitch.flow))

  /**
    * Creates the source for the sync process if a sync log is provided. The
    * exact source to be used depends on the configuration of a processed log.
    *
    * @param config      the sync configuration
    * @param syncLogPath the path to the sync log
    * @param ec          the execution context
    * @param system      the actor system
    * @return the source to read from a sync log file
    */
  private def createSyncSourceFromLog(config: SyncConfig, syncLogPath: Path)
                                     (implicit ec: ExecutionContext, system: ActorSystem):
  Future[Source[SyncOperation, Any]] = config.logConfig.logFilePath match
    case Some(processedLog) =>
      SerializerStreamHelper.createSyncOperationSourceWithProcessedLog(syncLogPath, processedLog)
    case None =>
      Future.successful(SerializerStreamHelper.createSyncOperationSource(syncLogPath))

  /**
    * Creates the flow stage that applies sync operations based on the given
    * sync config. If the dry-run mode is enabled, a dummy flow is returned
    * that passes all operations through.
    *
    * @param config         the sync configuration
    * @param spawner        the object to spawn actors
    * @param protocolHolder the ''SyncProtocolHolder''
    * @param ec             the execution context
    * @param system         the actor system
    * @return a future with the flow to apply sync operations
    */
  private def createApplyStage(config: SyncConfig, spawner: Spawner, protocolHolder: SyncProtocolHolder)
                              (implicit ec: ExecutionContext, system: ActorSystem):
  Future[Flow[SyncOperation, SyncOperationResult, Any]] = Future {
    if config.streamConfig.dryRun then Flow[SyncOperation].map(op => SyncOperationResult(op, None))
    else
      val applyStage = protocolHolder.createApplyStage(config, spawner)
      config.streamConfig.opsPerUnit.fold(applyStage) { limit =>
        Throttle(applyStage, limit, config.streamConfig.throttleUnit)
      }
  }

  /**
    * Creates a ''RunnableGraph'' representing the stream for a sync process.
    * The source for the ''SyncOperation'' objects to be processed is passed
    * in. The stream has two sinks that also determine the materialized
    * values of the graph: one sink counts all successful sync operations,
    * the second sink counts the failed ones. If a log file path has been
    * provided, the successful sync operations are also written to this log
    * file.
    *
    * @param source       the source producing ''SyncOperation'' objects
    * @param flowProc     the flow that processes sync operations
    * @param logFile      an optional path to a log file to write
    * @param errorLogFile an optional path to a log file for errors
    * @param ec           the execution context
    * @return a future with the runnable graph
    */
  private def createSyncStream(source: Source[SyncOperation, Any],
                               flowProc: Flow[SyncOperation, SyncOperationResult, Any],
                               logFile: Option[Path],
                               errorLogFile: Option[Path])
                              (implicit ec: ExecutionContext):
  Future[RunnableGraph[Future[SyncStream.SyncStreamMat[Int, Int]]]] = Future {
    val sinkTotal = createCountSinkWithOptionalLogging(logFile, errorLog = false)
    val sinkError = createErrorSink(errorLogFile)
    val sinkSuccess = Flow[SyncOperationResult].filterNot { result =>
      result.optFailure.isDefined || result.op.action == ActionNoop
    }.toMat(sinkTotal)(Keep.right)

    val params = SyncStream.SyncStreamParams(source = source, processFlow = flowProc,
      sinkTotal = sinkSuccess, sinkError = sinkError)
    val decider: Supervision.Decider = ex => {
      ex.printStackTrace()
      Supervision.Resume
    }

    SyncStream.createSyncStream(params).withAttributes(ActorAttributes.supervisionStrategy(decider))
  }

  /**
    * Creates the ''Sink'' that receives all failed sync operation results. The
    * main functionality of this sink is to count these elements. If a path to
    * a log file is specified, the sink is decorated to write the failed
    * operations into this log file. In any case, failures are logged to the
    * console with ERROR level.
    *
    * @param errorLogFile optional path to an error log file
    * @param ec           the execution context
    * @return the ''Sink'' for failed operations
    */
  private def createErrorSink(errorLogFile: Option[Path])(implicit ec: ExecutionContext):
  Sink[SyncOperationResult, Future[Int]] =
    val countSink = createCountSinkWithOptionalLogging(errorLogFile, errorLog = true)
    val logger = LogManager.getLogger(classOf[Sync])
    val consoleLogSink = Sink.foreach[SyncOperationResult] { result =>
      logger.error(s"Failed to apply operation '${result.op}'.", result.optFailure.get)
    }

    SyncStream.combinedSink(countSink, consoleLogSink)

  /**
    * Creates a ''Sink'' that counts the elements received and optionally adds
    * logging if a log file is specified. This function is used to create the
    * sinks for all operations and the failed operations.
    *
    * @param logFile  the optional path to the log file
    * @param errorLog flag whether an error log sink should be created
    * @param ec       the execution context
    * @return the resulting sink
    */
  private def createCountSinkWithOptionalLogging(logFile: Option[Path], errorLog: Boolean)
                                                (implicit ec: ExecutionContext):
  Sink[SyncOperationResult, Future[Int]] =
    val sinkCount = SyncStream.createCountSink()
    logFile.fold(sinkCount)(path => SyncStream.sinkWithLogging(sinkCount, path, errorLog))

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
    if totalCount == successCount then
      s"Successfully completed all ($totalCount) sync operations."
    else
      s"$successCount operations from $totalCount were successful."

  /**
    * Starts a sync process with the given parameters and returns a message
    * about the result in a ''Future''. Note that the ''Future'' returned by
    * this function never fails; if the sync process fails, it is completed
    * with a corresponding error message.
    *
    * @param config            the sync configuration
    * @param authSetupFunc     the function to setup authentication
    * @param protocolSetupFunc the function to setup protocol factories
    * @param system            the actor system
    * @param ec                the execution context
    * @return a ''Future'' with a result message
    */
  private def syncWithResultMessage(config: SyncConfig)(authSetupFunc: AuthSetupFunc)
                                   (protocolSetupFunc: ProtocolFactorySetupFunc)
                                   (implicit system: ActorSystem, ec: ExecutionContext): Future[String] =
    syncProcess(config)(authSetupFunc)(protocolSetupFunc)
      .map(res => processedMessage(res.totalOperations, res.successfulOperations))

/**
  * The main class to execute sync processes.
  *
  * An instance of this class is created and invoked by the ''main()'' function
  * in the companion object. By extending [[CliActorSystemLifeCycle]], this class
  * has access to an actor system and can therefore initiate the sync process.
  */
class Sync extends CliActorSystemLifeCycle[SyncConfig] :
  override val name: String = "Sync"

  override protected def cliExtractor: ParameterExtractor.CliExtractor[Try[SyncConfig]] =
    SyncParameterManager.syncConfigExtractor()

  override protected def usageCaption(processingContext: ProcessingContext): String =
    "Usage: streamsync [options] " +
      HelpGenerator.generateInputParamsOverview(processingContext.parameterContext.modelContext).mkString(" ")

  override protected def helpOptionHelp: String = Sync.HelpOptionHelp

  override protected def optionsGroupFilter(context: ProcessingContext): ParameterFilter =
    val srcExt = SyncCliStructureConfig.structureTypeSelectorExtractor(SourceRoleType, "uri")
    val dstExt = SyncCliStructureConfig.structureTypeSelectorExtractor(DestinationRoleType, "uri")
    val contextFilter = HelpGenerator.contextGroupFilterForExtractors(context.parameterContext,
      List(srcExt, dstExt))
    HelpGenerator.andFilter(HelpGenerator.negate(HelpGenerator.InputParamsFilterFunc), contextFilter)

  /**
    * @inheritdoc This implementation starts the sync process using the actor
    *             system in implicit scope.
    */
  override protected[cli] def runApp(config: SyncConfig): Future[String] =
    implicit val typedActorSystem: typed.ActorSystem[_] = actorSystem.toTyped
    Sync.syncWithResultMessage(config)(SyncSetup.defaultAuthSetupFunc())(SyncSetup.defaultProtocolFactorySetupFunc)
