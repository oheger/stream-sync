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

import java.util.concurrent.atomic.AtomicInteger

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{Flow, Source}
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes.{ElementSourceFactory, FsElement, SyncOperation}
import com.github.sync.cli.SyncComponentsFactory.{ApplyStageData, DestinationComponentsFactory, SourceComponentsFactory}
import com.github.sync.http.{HttpConfig, HttpExtensionActor}

import scala.concurrent.ExecutionContext

/**
  * A base class for a ''SourceComponentsFactory'' for an HTTP-based protocol.
  *
  * This class takes care of the management of an HTTP request actor. It
  * provides this actor a it subclasses, which can use it to create the correct
  * components.
  *
  * @param config           the configuration
  * @param httpActorFactory the HTTP actor factory
  * @param ec               the execution context
  * @param system           the actor system
  * @tparam C the type of the configuration
  */
private abstract class HttpComponentsSourceFactory[C <: HttpConfig](config: C, httpActorFactory: HttpActorFactory)
                                                                   (implicit ec: ExecutionContext, system: ActorSystem)
  extends SourceComponentsFactory {

  import HttpActorFactory._

  /**
    * The actor serving HTTP requests. Note this must be lazy, so that it is
    * only created when this factory is actually accessed. Otherwise, it will
    * not be cleaned up correctly.
    */
  private lazy val requestActor =
    httpActorFactory.createHttpRequestActor(config, system, clientCount = 0, RequestActorSourceName)

  override def createSource(sourceFactory: ElementSourceFactory): Source[FsElement, Any] =
    doCreateSource(sourceFactory, useRequestActor())

  override def createSourceFileProvider(): SourceFileProvider =
    doCreateSourceFileProvider(useRequestActor())

  /**
    * Creates the correct source for the supported protocol. This method is
    * called by ''createSource()'' with the HTTP actor managed by this class.
    *
    * @param sourceFactory    the element source factory
    * @param httpRequestActor the HTTP request actor
    * @return the source for iterating over the HTTP folder structure
    */
  protected def doCreateSource(sourceFactory: ElementSourceFactory, httpRequestActor: ActorRef):
  Source[FsElement, Any]

  /**
    * Creates the correct ''SourceFileProvider'' for the supported protocol.
    * This method is called by ''createSourceFileProvider()'' with the HTTP
    * actor managed by this class.
    *
    * @param httpRequestActor the HTTP request actor
    * @return the ''SourceFileProvider''
    */
  protected def doCreateSourceFileProvider(httpRequestActor: ActorRef): SourceFileProvider

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
  * A base class for a ''DestinationComponentsFactory'' for an HTTP-based
  * protocol.
  *
  * This class is analogous to [[HttpComponentsSourceFactory]] for the
  * destination structure.
  *
  * @param config           the configuration
  * @param httpActorFactory the HTTP actor factory
  * @param ec               the execution context
  * @param system           the actor system
  * @tparam C the type of the configuration
  */
private abstract class HttpComponentsDestinationFactory[C <: HttpConfig](config: C, httpActorFactory: HttpActorFactory)
                                                                        (implicit ec: ExecutionContext,
                                                                         system: ActorSystem)
  extends DestinationComponentsFactory {

  import HttpActorFactory._

  /** A counter for generating names for the destination request actor. */
  private val httpRequestActorDestCount = new AtomicInteger

  override def createDestinationSource(sourceFactory: ElementSourceFactory): Source[FsElement, Any] =
    createElementSource(sourceFactory, "")

  override def createPartialSource(sourceFactory: ElementSourceFactory, startFolderUri: String):
  Source[FsElement, Any] =
    createElementSource(sourceFactory, startFolderUri)

  override def createApplyStage(targetUri: String, fileProvider: SourceFileProvider): ApplyStageData = {
    val requestActor = httpActorFactory.createHttpRequestActor(config, system, 1, RequestActorSyncName,
      withKillSwitch = true)
    val opFlow = createApplyFlow(fileProvider, requestActor)
      .via(httpActorFactory.killSwitch.flow)
    ApplyStageData(opFlow)
  }

  /**
    * Creates a ''Source'' object for iterating over the configured HTTP
    * structure. This method is called whenever a source needs to be created;
    * the HTTP actor to be used is passed in.
    *
    * @param sourceFactory  the factory to create the source
    * @param startFolderUri the URI of the start folder
    * @param requestActor   the HTTP request actor
    * @return the element source for the destination structure
    */
  protected def doCreateSource(sourceFactory: ElementSourceFactory, startFolderUri: String,
                               requestActor: ActorRef): Source[FsElement, Any]

  /**
    * Creates the flow that applies the sync operations to the destination
    * structure. The request actor to be used is passed as parameter.
    *
    * @param fileProvider the source file provider
    * @param requestActor the HTTP request actor
    * @return the flow to apply sync operations
    */
  protected def createApplyFlow(fileProvider: SourceFileProvider, requestActor: ActorRef):
  Flow[SyncOperation, SyncOperation, NotUsed]

  /**
    * Convenience function to create an element source for the destination
    * structure. The HTTP actor required for the source is created
    * automatically. A start folder can be specified.
    *
    * @param sourceFactory  the factory to create the source
    * @param startFolderUri the URI of the start folder
    * @return the element source for the destination structure
    */
  private def createElementSource(sourceFactory: ElementSourceFactory, startFolderUri: String):
  Source[FsElement, Any] = {
    val requestActor = httpActorFactory.createHttpRequestActor(config, system, clientCount = 1,
      httpRequestActorDestName)
    doCreateSource(sourceFactory, startFolderUri, requestActor)
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