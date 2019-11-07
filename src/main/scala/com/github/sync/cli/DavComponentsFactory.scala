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
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import com.github.sync.SyncTypes.{ElementSourceFactory, FsElement}
import com.github.sync.webdav._
import com.github.sync.{SourceFileProvider, SyncTypes}

import scala.concurrent.ExecutionContext

/**
  * A special factory implementation for source components if the source
  * structure is a WebDav server.
  *
  * @param config           the configuration of the WebDav server
  * @param httpActorFactory the factory for creating the HTTP actor
  * @param ec               the execution context
  * @param system           the actor system
  * @param mat              the object to materialize streams
  */
private class DavComponentsSourceFactory(val config: DavConfig, val httpActorFactory: HttpActorFactory)
                                        (implicit ec: ExecutionContext, system: ActorSystem, mat: ActorMaterializer)
  extends HttpComponentsSourceFactory[DavConfig](config, httpActorFactory) {
  override protected def doCreateSource(sourceFactory: ElementSourceFactory, httpRequestActor: ActorRef):
  Source[FsElement, Any] = DavFsElementSource(config, sourceFactory, httpRequestActor)

  override protected def doCreateSourceFileProvider(httpRequestActor: ActorRef): SourceFileProvider =
    DavSourceFileProvider(config, httpRequestActor)
}

/**
  * A special factory implementation for destination components if the
  * destination structure is a WebDav server.
  *
  * @param config           the configuration of the WebDav server
  * @param httpActorFactory the factory for creating the HTTP actor
  * @param system           the actor system
  * @param mat              the object to materialize streams
  */
private class DavComponentsDestinationFactory(val config: DavConfig, val httpActorFactory: HttpActorFactory)
                                             (implicit ec: ExecutionContext, system: ActorSystem,
                                              mat: ActorMaterializer)
  extends HttpComponentsDestinationFactory[DavConfig](config, httpActorFactory) {
  override protected def doCreateSource(sourceFactory: ElementSourceFactory, startFolderUri: String,
                                        requestActor: ActorRef): Source[FsElement, Any] =
    DavFsElementSource(config, sourceFactory, requestActor, startFolderUri)

  override protected def createApplyFlow(fileProvider: SourceFileProvider, requestActor: ActorRef):
  Flow[SyncTypes.SyncOperation, SyncTypes.SyncOperation, NotUsed] =
    DavOperationHandler(config, fileProvider, requestActor)
}
