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

import akka.stream.scaladsl.Source
import com.github.sync.{SourceFileProvider, SyncTypes}
import com.github.sync.cli.SyncComponentsFactory.{DestinationComponentsFactory, SourceComponentsFactory}
import com.github.sync.webdav.DavConfig

/**
  * A special factory implementation for source components if the source
  * structure is a WebDav server.
  *
  * @param config the configuration of the WebDav server
  */
private class DavComponentsSourceFactory(val config: DavConfig) extends SourceComponentsFactory {
  override def createSource(sourceFactory: SyncTypes.ElementSourceFactory): Source[SyncTypes.FsElement, Any] = ???

  override def createSourceFileProvider(): SourceFileProvider = ???
}

/**
  * A special factory implementation for destination components if the
  * destination structure is a WebDav server.
  *
  * @param config the configuration of the WebDav server
  */
private class DavComponentsDestinationFactory(val config: DavConfig) extends DestinationComponentsFactory {
  override def createDestinationSource(sourceFactory: SyncTypes.ElementSourceFactory): Source[SyncTypes.FsElement, Any] = ???

  override def createPartialSource(sourceFactory: SyncTypes.ElementSourceFactory, startFolderUri: String): Source[SyncTypes.FsElement, Any] = ???

  override def createApplyStage(targetUri: String, fileProvider: SourceFileProvider): SyncComponentsFactory.ApplyStageData = ???
}
