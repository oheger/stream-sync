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

import java.nio.file.{Path, Paths}

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, ClosedShape, Supervision}
import akka.util.Timeout
import com.github.sync.cli.FilterManager.SyncFilterData
import com.github.sync.impl.{FolderSortStage, SyncStage}
import com.github.sync.local.{LocalFsElementSource, LocalSyncOperationActor}
import com.github.sync.SyncOperation

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Main object to start the sync process.
  *
  * This is currently a minimum implementation to be extended stepwise.
  */
object Sync {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("stream-sync")
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val ec: ExecutionContext = system.dispatcher

    val futSync = for {argsMap <- ParameterManager.parseParameters(args)
                       syncUriData <- ParameterManager.extractSyncUris(argsMap)
                       filterData <- FilterManager.parseFilters(syncUriData._1)
                       _ <- ParameterManager.checkParametersConsumed(filterData._1)
                       msg <- runSync(Paths get syncUriData._2._1, Paths get syncUriData._2._2,
                         createSyncFilter(filterData._2))
    } yield msg
    futSync onComplete {
      case Success(msg) =>
        println(msg)
      case Failure(exception) =>
        exception.printStackTrace()
        println("Sync process failed!")
    }
    futSync onComplete (_ => system.terminate())
  }

  /**
    * Runs the stream that represents the sync process.
    *
    * @param srcPath    the path to the source structure
    * @param dstPath    the path to the destination structure
    * @param syncFilter a filter for sync operations
    * @param system     the actor system
    * @return a future with a message about the outcome
    */
  private def runSync(srcPath: Path, dstPath: Path,
                      syncFilter: Flow[SyncOperation, SyncOperation, Any])
                     (implicit system: ActorSystem): Future[String] = {
    val decider: Supervision.Decider = _ => Supervision.Resume
    implicit val materializer: ActorMaterializer =
      ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))
    implicit val writeTimeout: Timeout = Timeout(10.seconds)
    import system.dispatcher
    val operationActor = system.actorOf(Props(classOf[LocalSyncOperationActor],
      srcPath, dstPath, "blocking-dispatcher"))
    val srcSource = LocalFsElementSource(srcPath).via(new FolderSortStage)
    val srcDest = LocalFsElementSource(dstPath).via(new FolderSortStage)
    val writeStage = Flow[SyncOperation].mapAsync(1) { op =>
      val futWrite = operationActor ? op
      futWrite.mapTo[SyncOperation]
    }
    val sinkCount = Sink.fold[Int, Any](0) { (c, _) => c + 1 }

    val g = RunnableGraph.fromGraph(GraphDSL.create(sinkCount, sinkCount)((_, _)) {
      implicit builder =>
        (sinkTotal, sinkSuccess) =>
          import GraphDSL.Implicits._
          val syncStage = builder.add(new SyncStage)
          val broadcast = builder.add(Broadcast[SyncOperation](2))
          srcSource ~> syncStage.in0
          srcDest ~> syncStage.in1
          syncStage.out ~> syncFilter ~> broadcast ~> sinkTotal.in
          broadcast ~> writeStage ~> sinkSuccess.in
          ClosedShape
    })
    val (futTotal, futSuccess) = g.run()
    for {totalCount <- futTotal
         successCount <- futSuccess
    } yield processedMessage(totalCount, successCount)
  }

  /**
    * Generates a ''Flow'' that filters out undesired sync operations based on
    * the filter parameters provided in the command line.
    *
    * @param filterData data about filter conditions
    * @return the flow to filter undesired sync operations
    */
  private def createSyncFilter(filterData: SyncFilterData):
  Flow[SyncOperation, SyncOperation, NotUsed] =
    Flow[SyncOperation].filter(op => FilterManager.applyFilter(op, filterData))

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
