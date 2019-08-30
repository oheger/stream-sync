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

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes.{FsElement, ResultTransformer, SyncOperation}
import com.github.sync.local.LocalFsConfig

/**
  * A special factory implementation for source components if the source
  * structure is a local file system.
  *
  * @param config the file system configuration
  */
private class LocalFsSourceComponentsFactory(val config: LocalFsConfig) extends SourceComponentsFactory {
  override def createSource[T](optSrcTransformer: Option[ResultTransformer[T]]):
  Source[FsElement, Any] = ???

  override def createSourceFileProvider(): SourceFileProvider = ???
}

/**
  * A special factory implementation for destination components if the
  * destination structure is a local file system.
  *
  * @param config the file system configuration
  */
private class LocalFsDestinationComponentsFactory(val config: LocalFsConfig) extends DestinationComponentsFactory {
  override def createDestinationSource[T](optDstTransformer: Option[ResultTransformer[T]]):
  Source[FsElement, Any] = ???

  override def createPartialSource(startFolderUri: String): Source[FsElement, Any] = ???

  override def createApplyStage(fileProvider: SourceFileProvider, noop: Boolean):
  Flow[SyncOperation, SyncOperation, NotUsed] = ???
}