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

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes.{ElementSourceFactory, FsElement}
import com.github.sync.cli.SyncComponentsFactory.{ApplyStageData, DestinationComponentsFactory, SourceComponentsFactory}
import com.github.sync.http.HttpExtensionActor
import com.github.sync.webdav._

/**
  * A special factory implementation for source components if the source
  * structure is a WebDav server.
  *
  * @param config           the configuration of the WebDav server
  * @param httpActorFactory the factory for creating the HTTP actor
  * @param system           the actor system
  * @param mat              the object to materialize streams
  */
private class DavComponentsSourceFactory(val config: DavConfig, val httpActorFactory: HttpActorFactory)
                                        (implicit system: ActorSystem, mat: ActorMaterializer)
  extends SourceComponentsFactory {

  import HttpActorFactory._

  /**
    * The actor serving HTTP requests. Note this must be lazy, so that it is
    * only created when this factory is actually accessed. Otherwise, it will
    * not be cleaned up correctly.
    */
  private lazy val requestActor =
    httpActorFactory.createHttpRequestActor(config, system, clientCount = 0, RequestActorSourceName)

  override def createSource(sourceFactory: ElementSourceFactory): Source[FsElement, Any] = {
    DavFsElementSource(config, sourceFactory, useRequestActor())
  }

  override def createSourceFileProvider(): SourceFileProvider =
    DavSourceFileProvider(config, useRequestActor())

  /**
    * Increases the client count of the request actor and returns its
    * reference. The number of the clients depends on the structure of the
    * sync stream. It can even be 0 (if the source is a log file source, and
    * the apply mode NONE is used). Therefore, the actor is created lazily,
    * and each time it is assigned to a component, a register message is sent.
    *
    * @return the request actor
    */
  private def useRequestActor(): ActorRef = {
    requestActor ! HttpExtensionActor.RegisterClient
    requestActor
  }
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
                                             (implicit system: ActorSystem, mat: ActorMaterializer)
  extends DestinationComponentsFactory {

  import HttpActorFactory._

  /** A counter for generating names for the destination request actor. */
  private val httpRequestActorDestCount = new AtomicInteger

  override def createDestinationSource(sourceFactory: ElementSourceFactory): Source[FsElement, Any] =
    createDavSource(sourceFactory)

  override def createPartialSource(sourceFactory: ElementSourceFactory, startFolderUri: String):
  Source[FsElement, Any] =
    createDavSource(sourceFactory, startFolderUri)

  override def createApplyStage(targetUri: String, fileProvider: SourceFileProvider): ApplyStageData = {
    val requestActor = httpActorFactory.createHttpRequestActor(config, system, 1, RequestActorSyncName,
      withKillSwitch = true)
    val opFlow = DavOperationHandler(config, fileProvider, requestActor)
      .via(httpActorFactory.killSwitch.flow)
    ApplyStageData(opFlow)
  }

  /**
    * Convenience function to create an element for the destination structure.
    * The HTTP actor required for the source is created automatically.
    * Optionally, a start folder can be specified.
    *
    * @param sourceFactory  the factory to create the source
    * @param startFolderUri the URI of the start folder
    * @return the element source for the destination structure
    */
  private def createDavSource(sourceFactory: ElementSourceFactory, startFolderUri: String = ""):
  Source[FsElement, Any] = {
    val requestActor = httpActorFactory.createHttpRequestActor(config, system, clientCount = 1,
      httpRequestActorDestName)
    DavFsElementSource(config, sourceFactory, requestActor, startFolderUri)
  }

  /**
    * Generates the name of an HTTP request actor for the destination
    * structure. There can be multiple actors for this purpose; therefore, a
    * counter is used to ensure that names are unique.
    *
    * @return the name for the actor
    */
  private def httpRequestActorDestName: String =
    RequestActorDestinationName + httpRequestActorDestCount.incrementAndGet()
}
