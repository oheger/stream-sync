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

package com.github.sync

import java.nio.file.Path

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, RunnableGraph, Source}
import akka.util.Timeout
import com.github.sync.SyncTypes.{FsElement, ResultTransformer, StructureType, SupportedArgument, SyncOperation}
import com.github.sync.impl.SyncStreamFactoryImpl

import scala.concurrent.{ExecutionContext, Future}

/**
  * A special test implementation of ''SyncStreamFactory'' that simply
  * delegates to another factory instance.
  *
  * This is useful in tests to only override specific methods of the factory;
  * as the default implementation is an object, this cannot be done otherwise
  * in an easy way.
  *
  * @param delegate the instance to delegate method calls to
  */
class DelegateSyncStreamFactory(delegate: SyncStreamFactory = SyncStreamFactoryImpl)
  extends SyncStreamFactory {
  override def additionalArguments(uri: String, structureType: StructureType)
                                  (implicit ec: ExecutionContext):
  Future[Iterable[SupportedArgument]] = delegate.additionalArguments(uri, structureType)

  override def createSyncInputSource(uri: String, optTransformer: Option[ResultTransformer],
                                     structureType: StructureType)
                                    (implicit ec: ExecutionContext, system: ActorSystem,
                                     mat: ActorMaterializer): ArgsFunc[Source[FsElement, Any]] =
    delegate.createSyncInputSource(uri, optTransformer, structureType)

  override def createSourceFileProvider(uri: String)(implicit ec: ExecutionContext,
                                                     system: ActorSystem, mat: ActorMaterializer)
  : ArgsFunc[SourceFileProvider] =
    delegate.createSourceFileProvider(uri)

  override def createSyncSource(uriSrc: String, optSrcTransformer: Option[ResultTransformer], uriDst: String,
                                optDstTransformer: Option[ResultTransformer], additionalArgs: StructureArgs,
                                ignoreTimeDelta: Int)
                               (implicit ec: ExecutionContext, system: ActorSystem,
                                mat: ActorMaterializer): Future[Source[SyncOperation, NotUsed]] =
    delegate.createSyncSource(uriSrc, optSrcTransformer, uriDst, optDstTransformer, additionalArgs,
      ignoreTimeDelta)

  override def createApplyStage(uriDst: String, fileProvider: SourceFileProvider)
                               (implicit system: ActorSystem, mat: ActorMaterializer,
                                ec: ExecutionContext, timeout: Timeout):
  ArgsFunc[Flow[SyncOperation, SyncOperation, NotUsed]] =
    delegate.createApplyStage(uriDst, fileProvider)

  override def createSyncStream(source: Source[SyncOperation, Any], flowProc: Flow[SyncOperation,
    SyncOperation, Any], logFile: Option[Path])(opFilter: SyncOperation => Boolean)
                               (implicit ec: ExecutionContext):
  Future[RunnableGraph[Future[(Int, Int)]]] =
    delegate.createSyncStream(source, flowProc, logFile)(opFilter)
}
