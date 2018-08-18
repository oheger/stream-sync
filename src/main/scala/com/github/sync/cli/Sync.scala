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

import java.nio.file.Paths

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, ClosedShape, Supervision}
import akka.util.Timeout
import com.github.sync.local.{LocalFsElementSource, LocalSyncOperationActor}
import com.github.sync.{FolderSortStage, SyncOperation, SyncStage}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * Main object to start the sync process.
  *
  * This is currently a minimum implementation to be extended stepwise.
  */
object Sync {
  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      println("Usage: Sync <source> <dest>")
      System.exit(1)
    }
    val sourcePath = Paths get args(0)
    val destPath = Paths get args(1)

    implicit val system: ActorSystem = ActorSystem("stream-sync")
    val decider: Supervision.Decider = _ => Supervision.Resume
    implicit val materializer: ActorMaterializer =
      ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))
    implicit val writeTimeout: Timeout = Timeout(10.seconds)
    import system.dispatcher
    val operationActor = system.actorOf(Props(classOf[LocalSyncOperationActor],
      sourcePath, destPath, "blocking-dispatcher"))
    val srcSource = LocalFsElementSource(sourcePath).via(new FolderSortStage)
    val srcDest = LocalFsElementSource(destPath).via(new FolderSortStage)
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
          syncStage.out ~> broadcast ~> sinkTotal.in
          broadcast ~> writeStage ~> sinkSuccess.in
          ClosedShape
    })
    val (futTotal, futSuccess) = g.run()
    Future.sequence(List(futTotal, futSuccess)) onComplete { f =>
      f match {
        case Failure(exception) =>
          exception.printStackTrace()
          println("Sync failed.")

        case Success(value) =>
          val totalCount = value.head
          val successCount = value(1)
          if (totalCount == successCount)
            println(s"Successfully completed all ($totalCount) sync operations.")
          else
            println(s"$successCount operations from $totalCount were successful.")
      }
      system.terminate()
    }
  }
}
