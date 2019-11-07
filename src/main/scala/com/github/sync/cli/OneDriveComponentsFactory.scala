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

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.github.sync.cli.SyncComponentsFactory.DestinationComponentsFactory
import com.github.sync.onedrive.{OneDriveConfig, OneDriveFsElementSource, OneDriveSourceFileProvider}
import com.github.sync.{SourceFileProvider, SyncTypes}

import scala.concurrent.ExecutionContext

/**
  * A special factory implementation for source components if the source is a
  * OneDrive server.
  *
  * @param config           the configuration
  * @param httpActorFactory the HTTP actor factory
  * @param ec               the execution context
  * @param system           the actor system
  * @param mat              the object to materialize streams
  */
private class OneDriveComponentsSourceFactory(val config: OneDriveConfig, val httpActorFactory: HttpActorFactory)
                                             (implicit ec: ExecutionContext, system: ActorSystem,
                                              mat: ActorMaterializer)
  extends HttpComponentsSourceFactory[OneDriveConfig](config, httpActorFactory) {
  override protected def doCreateSource(sourceFactory: SyncTypes.ElementSourceFactory, httpRequestActor: ActorRef):
  Source[SyncTypes.FsElement, Any] = OneDriveFsElementSource(config, sourceFactory, httpRequestActor)

  override protected def doCreateSourceFileProvider(httpRequestActor: ActorRef): SourceFileProvider =
    new OneDriveSourceFileProvider(config, httpRequestActor)
}

private class OneDriveComponentsDestinationFactory(val config: OneDriveConfig, val httpActorFactory: HttpActorFactory)
                                                  (implicit system: ActorSystem, mat: ActorMaterializer)
  extends DestinationComponentsFactory {
  /**
    * Creates the ''Source''for iterating over the destination structure of the sync
    * process.
    *
    * @param sourceFactory the factory for creating an element source
    * @return the source for iterating the destination structure
    */
  override def createDestinationSource(sourceFactory: SyncTypes.ElementSourceFactory): Source[SyncTypes.FsElement, Any] = ???

  /**
    * Creates a ''Source'' for iterating over the destination structure
    * starting with a given folder. This is needed for some use cases to
    * resolve files in the destination structure; e.g. if folder names are
    * encrypted.
    *
    * @param sourceFactory  the factory for creating an element source
    * @param startFolderUri the URI of the start folder of the iteration
    * @return the source for a partial iteration
    */
  override def createPartialSource(sourceFactory: SyncTypes.ElementSourceFactory, startFolderUri: String): Source[SyncTypes.FsElement, Any] = ???

  /**
    * Creates the flow stage that interprets sync operations and applies them
    * to the destination structure and returns a data object with this flow
    * and a function for cleaning up resources.
    *
    * @param targetUri    the target URI of the destination structure
    * @param fileProvider the provider for files from the source structure
    * @return the data object about the stage to process sync operations
    */
  override def createApplyStage(targetUri: String, fileProvider: SourceFileProvider): SyncComponentsFactory.ApplyStageData = ???
}