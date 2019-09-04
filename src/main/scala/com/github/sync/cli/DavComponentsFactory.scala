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
import com.github.sync.webdav._

object DavComponentsFactory {
  /** The name of the HTTP request actor for the source structure. */
  val RequestActorSourceName = "httpRequestActorSrc"

  /**
    * The name of the HTTP request actor for the destination structure. Note
    * that multiple actors may be active concurrently to resolve encrypted
    * paths. Therefore, the name is only a prefix.
    */
  val RequestActorDestinationName = "httpRequestActorDest"

  /** The name of the HTTP request actor for executing sync operations. */
  val RequestActorSyncName = "httpRequestActorSync"
}

/**
  * A base trait for factories that create components for accessing a WebDav
  * server.
  *
  * This trait defines common functionality that is used both by the source and
  * destination factory.
  */
trait DavComponentsFactory {

  /**
    * Creates the actor for executing HTTP requests.
    *
    * @param conf        the DAV configuration
    * @param system      the actor system
    * @param clientCount the number of clients for the actor
    * @param name        the name of the request actor
    * @return the actor for HTTP requests
    */
  protected def createHttpRequestActor(conf: DavConfig, system: ActorSystem, clientCount: Int, name: String)
  : ActorRef = {
    val httpActor = system.actorOf(HttpRequestActor(conf.rootUri), name)
    system.actorOf(HttpBasicAuthActor(httpActor, conf, clientCount), name + "_auth")
  }
}

/**
  * A special factory implementation for source components if the source
  * structure is a WebDav server.
  *
  * @param config the configuration of the WebDav server
  * @param system the actor system
  */
private class DavComponentsSourceFactory(val config: DavConfig)
                                        (implicit system: ActorSystem, mat: ActorMaterializer)
  extends SourceComponentsFactory with DavComponentsFactory {

  import DavComponentsFactory._

  /**
    * The actor serving HTTP requests. Note this must be lazy, so that it is
    * only created when this factory is actually accessed. Otherwise, it will
    * not be cleaned up correctly.
    */
  private lazy val requestActor = createHttpRequestActor(config, system, clientCount = 0, RequestActorSourceName)

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
  * @param config the configuration of the WebDav server
  */
private class DavComponentsDestinationFactory(val config: DavConfig)
                                             (implicit system: ActorSystem, mat: ActorMaterializer)
  extends DestinationComponentsFactory with DavComponentsFactory {

  import DavComponentsFactory._

  /** A counter for generating names for the destination request actor. */
  private val httpRequestActorDestCount = new AtomicInteger

  override def createDestinationSource(sourceFactory: ElementSourceFactory): Source[FsElement, Any] =
    createDavSource(sourceFactory)

  override def createPartialSource(sourceFactory: ElementSourceFactory, startFolderUri: String):
  Source[FsElement, Any] =
    createDavSource(sourceFactory, startFolderUri)

  override def createApplyStage(targetUri: String, fileProvider: SourceFileProvider): ApplyStageData = {
    val requestActor = createHttpRequestActor(config, system, 1, RequestActorSyncName)
    val opFlow = DavOperationHandler.webDavProcessingFlow(config, fileProvider, requestActor)
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
    val requestActor = createHttpRequestActor(config, system, clientCount = 1, httpRequestActorDestName)
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
