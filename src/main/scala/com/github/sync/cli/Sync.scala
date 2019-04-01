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

package com.github.sync.cli

import java.nio.file.Path

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream._
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import com.github.sync.{SourceFileProvider, SyncStreamFactory}
import com.github.sync.SyncTypes.{DestinationStructureType, ResultTransformer, SourceStructureType, SyncOperation}
import com.github.sync.cli.FilterManager.SyncFilterData
import com.github.sync.cli.ParameterManager.SyncConfig
import com.github.sync.crypt.CryptService
import com.github.sync.impl.{CryptAwareSourceFileProvider, SyncStreamFactoryImpl}
import com.github.sync.log.SerializerStreamHelper

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Main object to start the sync process.
  *
  * This is currently a minimum implementation to be extended stepwise.
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
    implicit val system: ActorSystem = ActorSystem("stream-sync")
    implicit val ec: ExecutionContext = system.dispatcher
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val futResult = for {
      msg <- syncWithResultMessage(args)
      _ <- Http().shutdownAllConnectionPools()
      _ <- system.terminate()
    } yield msg

    val resultMsg = Await.result(futResult, 365.days)
    println(resultMsg)
  }

  /**
    * Implements a sync process based on the parameters provided. Result is a
    * future with a tuple of Int values. The first element is the total number
    * of sync operations that have been executed; the second element is the
    * number of successful sync operations.
    *
    * @param args    the array with command line arguments
    * @param system  the actor system
    * @param factory the factory for the sync stream
    * @return a future with information about the result of the process
    */
  def syncProcess(args: Array[String])(implicit system: ActorSystem, factory: SyncStreamFactory):
  Future[SyncResult] = {
    val decider: Supervision.Decider = _ => Supervision.Resume
    implicit val materializer: ActorMaterializer =
      ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))
    implicit val ec: ExecutionContext = system.dispatcher

    for {argsMap <- ParameterManager.parseParameters(args)
         (argsMap1, config) <- ParameterManager.extractSyncConfig(argsMap)
         (argsMap2, filterData) <- FilterManager.parseFilters(argsMap1)
         srcArgs <- factory.additionalArguments(config.syncUris._1, SourceStructureType)
         dstArgs <- factory.additionalArguments(config.syncUris._2, DestinationStructureType)
         (argsMap3, addArgs) <- ParameterManager.extractSupportedArguments(argsMap2,
           srcArgs ++ dstArgs)
         _ <- ParameterManager.checkParametersConsumed(argsMap3)
         result <- runSync(config, filterData, addArgs)
    } yield result
  }

  /**
    * Runs the stream that represents the sync process.
    *
    * @param config         the ''SyncConfig''
    * @param filterData     data about the current filter definition
    * @param additionalArgs a map with additional arguments
    * @param system         the actor system
    * @param mat            the object to materialize streams
    * @param factory        the factory for the sync stream
    * @return a future with information about the result of the process
    */
  private def runSync(config: SyncConfig, filterData: SyncFilterData,
                      additionalArgs: Map[String, String])
                     (implicit system: ActorSystem, mat: ActorMaterializer,
                      factory: SyncStreamFactory): Future[SyncResult] = {
    import system.dispatcher
    val filter = createSyncFilter(filterData)
    for {
      source <- createSyncSource(config, additionalArgs)
      stage <- createApplyStage(config, additionalArgs)
      g <- factory.createSyncStream(source, stage, config.logFilePath)(filter)
      res <- g.run()
    } yield SyncResult(res._1, res._2)
  }

  /**
    * Creates the source for the sync process based on the given configuration.
    * Per default, a source is returned that determines the delta of two folder
    * structures. If however a sync log is provided, a source reading this file
    * is returned.
    *
    * @param config         the sync configuration
    * @param additionalArgs the map with additional arguments
    * @param ec             the execution context
    * @param mat            the object to materialize streams
    * @param factory        the factory for the sync stream
    * @return the source for the sync process
    */
  private def createSyncSource(config: SyncConfig, additionalArgs: Map[String, String])
                              (implicit ec: ExecutionContext, system: ActorSystem,
                               mat: ActorMaterializer, factory: SyncStreamFactory):
  Future[Source[SyncOperation, Any]] = config.syncLogPath match {
    case Some(path) =>
      createSyncSourceFromLog(config, path)
    case None =>
      factory.createSyncSource(config.syncUris._1, createResultTransformer(config.srcPassword),
        config.syncUris._2, createResultTransformer(config.dstPassword), additionalArgs,
        config.ignoreTimeDelta getOrElse 1)
  }

  /**
    * Returns a ''ResultTransformer'' for an element source based on the given
    * parameters. The transformer makes sure that the results produced by an
    * element source are compatible with the parameters passed in.
    *
    * @param optCryptPwd the optional encryption password
    * @return the ''ResultTransformer'' for these parameters
    */
  private def createResultTransformer(optCryptPwd: Option[String]): Option[ResultTransformer] =
    optCryptPwd.map(_ => CryptService.cryptTransformer())

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
    * @param config         the sync configuration
    * @param additionalArgs the map with additional arguments
    * @param ec             the execution context
    * @param system         the actor system
    * @param mat            the object to materialize streams
    * @param factory        the factory for the sync stream
    * @return a future with the flow to apply sync operations
    */
  private def createApplyStage(config: SyncConfig, additionalArgs: Map[String, String])
                              (implicit ec: ExecutionContext, system: ActorSystem,
                               mat: ActorMaterializer, factory: SyncStreamFactory):
  Future[Flow[SyncOperation, SyncOperation, NotUsed]] = {
    implicit val timeout: Timeout = config.timeout
    config.applyMode match {
      case ParameterManager.ApplyModeTarget(targetUri) =>
        for {
          provider <- createSourceFileProvider(config, additionalArgs)
          stage <- factory.createApplyStage(targetUri, provider).apply(additionalArgs)
        } yield stage

      case ParameterManager.ApplyModeNone =>
        Future.successful(Flow[SyncOperation].map(identity))
    }
  }

  /**
    * Creates the source file provider. A bsic provider can be obtained from
    * the factory. Then support for encryption might need to be added if an
    * encryption password has been provided.
    *
    * @param config         the sync configuration
    * @param additionalArgs the map with additional arguments
    * @param ec             the execution context
    * @param system         the actor system
    * @param mat            the object to materialize streams
    * @param factory        the factory for the sync stream
    * @return
    */
  private def createSourceFileProvider(config: SyncConfig, additionalArgs: Map[String, String])
                                      (implicit ec: ExecutionContext, system: ActorSystem, mat: ActorMaterializer,
                                       factory: SyncStreamFactory): Future[SourceFileProvider] =
    factory.createSourceFileProvider(config.syncUris._1).apply(additionalArgs) map { provider =>
      if (config.srcPassword.nonEmpty || config.dstPassword.nonEmpty)
        CryptAwareSourceFileProvider(provider, config.srcPassword, config.dstPassword)
      else provider
    }

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
    * Returns an error message from the given exception.
    *
    * @param ex the exception
    * @return the error message derived from this exception
    */
  private def errorMessage(ex: Throwable): String =
    s"[${ex.getClass.getSimpleName}]: ${ex.getMessage}"

  /**
    * Starts a sync process with the given parameters and returns a message
    * about the result in a ''Future''. Note that the ''Future'' returned by
    * this function never fails; if the sync process fails, it is completed
    * with a corresponding error message.
    *
    * @param args    the array with command line arguments
    * @param system  the actor system
    * @param factory the factory for creating stream components
    * @param ec      the execution context
    * @return a ''Future'' with a result message
    */
  private def syncWithResultMessage(args: Array[String])
                                   (implicit system: ActorSystem, factory: SyncStreamFactory,
                                    ec: ExecutionContext): Future[String] =
    syncProcess(args)
      .map(res => processedMessage(res.totalOperations, res.successfulOperations))
      .recover {
        case ex => errorMessage(ex)
      }
}
