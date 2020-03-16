/*
 * Copyright 2018-2020 The Developers Team.
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
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{Flow, Source}
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes.{ElementSourceFactory, FsElement, SyncOperation}
import com.github.sync.onedrive.{OneDriveConfig, OneDriveFsElementSource, OneDriveOperationHandler, OneDriveSourceFileProvider}

import scala.concurrent.ExecutionContext

/**
  * A special factory implementation for source components if the source is a
  * OneDrive server.
  *
  * @param config           the configuration
  * @param httpActorFactory the HTTP actor factory
  * @param ec               the execution context
  * @param system           the actor system
  */
private class OneDriveComponentsSourceFactory(val config: OneDriveConfig, val httpActorFactory: HttpActorFactory)
                                             (implicit ec: ExecutionContext, system: ActorSystem)
  extends HttpComponentsSourceFactory[OneDriveConfig](config, httpActorFactory) {
  override protected def doCreateSource(sourceFactory: ElementSourceFactory, httpRequestActor: ActorRef):
  Source[FsElement, Any] = OneDriveFsElementSource(config, sourceFactory, httpRequestActor)

  override protected def doCreateSourceFileProvider(httpRequestActor: ActorRef): SourceFileProvider =
    new OneDriveSourceFileProvider(config, httpRequestActor)
}

/**
  * A special factory implementation for destination components if the
  * destination is a OneDrive server.
  *
  * @param config           the configuration
  * @param httpActorFactory the HTTP actor factory
  * @param ec               the execution context
  * @param system           the actor system
  */
private class OneDriveComponentsDestinationFactory(val config: OneDriveConfig, val httpActorFactory: HttpActorFactory)
                                                  (implicit ec: ExecutionContext, system: ActorSystem)
  extends HttpComponentsDestinationFactory[OneDriveConfig](config, httpActorFactory) {
  override protected def doCreateSource(sourceFactory: ElementSourceFactory, startFolderUri: String,
                                        requestActor: ActorRef): Source[FsElement, Any] =
    OneDriveFsElementSource(config, sourceFactory, requestActor, startFolderUri)

  override protected def createApplyFlow(fileProvider: SourceFileProvider, requestActor: ActorRef):
  Flow[SyncOperation, SyncOperation, NotUsed] =
    OneDriveOperationHandler(config, fileProvider, requestActor)
}
