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
import com.github.sync.SyncTypes._
import com.github.sync.cli.SyncComponentsFactory.SourceComponentsFactory
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

  override def createSyncInputSource[T](uri: String, optTransformer: Option[ResultTransformer[T]],
                                        structureType: StructureType, startFolderUri: String = "")
                                       (implicit ec: ExecutionContext, system: ActorSystem,
                                        mat: ActorMaterializer, timeout: Timeout):
  ArgsFunc[Source[FsElement, Any]] =
    delegate.createSyncInputSource(uri, optTransformer, structureType, startFolderUri)

  override def createSourceFileProvider(uri: String)(implicit ec: ExecutionContext,
                                                     system: ActorSystem, mat: ActorMaterializer, timeout: Timeout)
  : ArgsFunc[SourceFileProvider] =
    delegate.createSourceFileProvider(uri)

  override def createSyncSource[T2](sourceSrc: Source[FsElement, Any],
                                    uriDst: String, optDstTransformer: Option[ResultTransformer[T2]],
                                    additionalArgs: StructureArgs, ignoreTimeDelta: Int)
                                   (implicit ec: ExecutionContext, system: ActorSystem,
                                    mat: ActorMaterializer, timeout: Timeout):
  Future[Source[SyncOperation, NotUsed]] =
    delegate.createSyncSource(sourceSrc, uriDst, optDstTransformer, additionalArgs,
      ignoreTimeDelta)

  override def createSourceComponents[T](uri: String, optSrcTransformer: Option[ResultTransformer[T]])
                                        (implicit ec: ExecutionContext, system: ActorSystem, mat: ActorMaterializer,
                                         timeout: Timeout): ArgsFunc[SyncTypes.SyncSourceComponents[FsElement]] =
    delegate.createSourceComponents(uri, optSrcTransformer)

  override def createApplyStage(uriDst: String, fileProvider: SourceFileProvider, noop: Boolean)
                               (implicit system: ActorSystem, mat: ActorMaterializer,
                                ec: ExecutionContext, timeout: Timeout):
  ArgsFunc[Flow[SyncOperation, SyncOperation, NotUsed]] =
    delegate.createApplyStage(uriDst, fileProvider, noop)

  override def createSyncStream(source: Source[SyncOperation, Any], flowProc: Flow[SyncOperation,
    SyncOperation, Any], logFile: Option[Path])
                               (implicit ec: ExecutionContext):
  Future[RunnableGraph[Future[(Int, Int)]]] =
    delegate.createSyncStream(source, flowProc, logFile)
}

/**
  * A special test implementation of ''SourceComponentsFactory'' that
  * simply delegates to another factory instance.
  *
  * This is useful in tests to only override specific methods of the factory
  * while keeping the default behavior of other methods.
  *
  * @param delegate the instance to delegate method calls to
  */
class DelegateSourceComponentsFactory(delegate: SourceComponentsFactory) extends SourceComponentsFactory {
  override def createSource(sourceFactory: SyncTypes.ElementSourceFactory): Source[FsElement, Any] =
    delegate.createSource(sourceFactory)

  override def createSourceFileProvider(): SourceFileProvider =
    delegate.createSourceFileProvider()
}
