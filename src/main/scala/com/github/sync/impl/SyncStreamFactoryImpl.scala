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

import java.nio.file.Paths

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.stream.ClosedShape
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink, Source}
import akka.util.Timeout
import com.github.sync.local.{LocalFsElementSource, LocalSyncOperationActor, LocalUriResolver}
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

  override def createSyncStream[RAW, PROC, RES](uriSrc: String, uriDst: String,
                                                sinkRaw: Sink[SyncOperation, Future[RAW]],
                                                flowProc: Flow[SyncOperation, PROC, Any],
                                                sinkProc: Sink[PROC, Future[RES]])
                                               (opFilter: SyncOperation => Boolean)
                                               (implicit system: ActorSystem,
                                                ec: ExecutionContext):
  Future[RunnableGraph[(Future[RAW], Future[RES])]] = for {
    srcSource <- createSyncInputSource(uriSrc)
    dstSource <- createSyncInputSource(uriDst)
  } yield createSyncRunnableGraph(srcSource, dstSource, sinkRaw, flowProc, sinkProc)(opFilter)

  /**
    * Creates a ''RunnableGraph'' representing the stream for a sync process
    * based on the parameters provided.
    *
    * @param srcSource the source for the source structure
    * @param dstSource the source for the destination structure
    * @param sinkRaw   the sink for the raw result
    * @param flowProc  the flow that processes sync operations
    * @param sinkProc  the sink for the processed operations
    * @param opFilter  a filter on sync operations
    * @param system    the actor system
    * @param ec        the execution context
    * @tparam RAW  type of the raw sink
    * @tparam PROC type of processed sync operations
    * @tparam RES  type of the result sink
    * @return the runnable graph
    */
  private def createSyncRunnableGraph[RAW, PROC, RES](srcSource: Source[FsElement, Any],
                                                      dstSource: Source[FsElement, Any],
                                                      sinkRaw: Sink[SyncOperation, Future[RAW]],
                                                      flowProc: Flow[SyncOperation, PROC, Any],
                                                      sinkProc: Sink[PROC, Future[RES]])
                                                     (opFilter: SyncOperation => Boolean)
                                                     (implicit system: ActorSystem,
                                                      ec: ExecutionContext):
  RunnableGraph[(Future[RAW], Future[RES])] =
    RunnableGraph.fromGraph(GraphDSL.create(sinkRaw, sinkProc)((_, _)) {
      implicit builder =>
        (sinkTotal, sinkSuccess) =>
          import GraphDSL.Implicits._
          val syncFilter = Flow[SyncOperation].filter(opFilter)
          val syncStage = builder.add(new SyncStage)
          val broadcast = builder.add(Broadcast[SyncOperation](2))
          srcSource ~> syncStage.in0
          dstSource ~> syncStage.in1
          syncStage.out ~> syncFilter ~> broadcast ~> sinkTotal.in
          broadcast ~> flowProc ~> sinkSuccess.in
          ClosedShape
    })
}
