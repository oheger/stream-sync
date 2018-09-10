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

package com.github.sync.cli

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.{FileIO, Flow, Sink}
import akka.util.Timeout
import com.github.sync.cli.FilterManager.SyncFilterData
import com.github.sync.cli.ParameterManager.SyncConfig
import com.github.sync.impl.SyncStreamFactoryImpl
import com.github.sync.log.ElementSerializer
import com.github.sync.{SyncOperation, SyncStreamFactory}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

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
    val futSync = syncProcess(args)
    futSync onComplete {
      case Success(result) =>
        println(processedMessage(result.totalOperations, result.successfulOperations))
      case Failure(exception) =>
        exception.printStackTrace()
        println("Sync process failed!")
    }
    futSync onComplete (_ => system.terminate())
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
         filterData <- FilterManager.parseFilters(argsMap1)
         _ <- ParameterManager.checkParametersConsumed(filterData._1)
         result <- runSync(config, filterData._2)
    } yield result
  }

  /**
    * Runs the stream that represents the sync process.
    *
    * @param config     the ''SyncConfig''
    * @param filterData data about the current filter definition
    * @param system     the actor system
    * @param mat        the object to materialize streams
    * @param factory    the factory for the sync stream
    * @return a future with information about the result of the process
    */
  private def runSync(config: SyncConfig,
                      filterData: SyncFilterData)
                     (implicit system: ActorSystem, mat: ActorMaterializer,
                      factory: SyncStreamFactory): Future[SyncResult] = {
    import system.dispatcher
    implicit val timeout: Timeout = config.timeout
    val sinkCount = Sink.fold[Int, SyncOperation](0) { (c, _) => c + 1 }
    val filter = createSyncFilter(filterData)
    config.applyMode match {
      case ParameterManager.ApplyModeTarget(targetUri) =>
        (for {
          provider <- factory.createSourceFileProvider(config.syncUris._1)
          stage <- factory.createApplyStage(targetUri, provider)
          g <- factory.createSyncStream(config.syncUris._1, config.syncUris._2, sinkCount,
            stage, sinkCount)(filter)
        } yield g.run()) flatMap (t => mapToSyncResult(t._1, t._2)(SyncResult))

      case ParameterManager.ApplyModeLog(logFilePath) =>
        val sinkLog = FileIO.toPath(logFilePath)
        val stage = Flow[SyncOperation].map(ElementSerializer.serializeOperation)
        factory.createSyncStream(config.syncUris._1, config.syncUris._2, sinkCount,
          stage, sinkLog)(filter)
          .map(_.run())
          .flatMap(t => mapToSyncResult(t._1, t._2) { (c, _) => SyncResult(c, c) })
    }
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
    * Maps the future results from the sinks of a sync stream to a
    * corresponding ''SyncResult'' object using the provided mapping function.
    *
    * @param futSink1 future result of sink 1
    * @param futSink2 future result of sink 2
    * @param f        the mapping function
    * @param ec       the execution context
    * @tparam A type of the result of sink 1
    * @tparam B type of the result of sink 2
    * @return a future with the resulting ''SyncResult''
    */
  private def mapToSyncResult[A, B](futSink1: Future[A], futSink2: Future[B])
                                   (f: (A, B) => SyncResult)
                                   (implicit ec: ExecutionContext): Future[SyncResult] =
    for {
      sink1 <- futSink1
      sink2 <- futSink2
    } yield f(sink1, sink2)

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
}
