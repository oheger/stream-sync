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

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.{ActorMaterializer, KillSwitches, SharedKillSwitch}
import akka.stream.scaladsl.Source
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes.{ElementSourceFactory, FsElement}
import com.github.sync.cli.SyncComponentsFactory.{ApplyStageData, DestinationComponentsFactory, SourceComponentsFactory}
import com.github.sync.crypt.Secret
import com.github.sync.http.{HttpBasicAuthActor, HttpExtensionActor, HttpRequestActor}
import com.github.sync.webdav._
import com.github.sync.http.oauth.{OAuthConfig, OAuthStorageServiceImpl, OAuthTokenActor, OAuthTokenData, OAuthTokenRetrieverServiceImpl}

object DavHttpActorFactory {
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

  /** The name of the kill switch to cancel the stream. */
  val KillSwitchName = "httpFatalErrorKillSwitch"
}

/**
  * A trait that knows how to create the HTTP request actor for Dav operations.
  *
  * Depending on the authentication scheme used for the Dav server, the actor
  * has to be created differently. This is abstracted via this trait.
  */
sealed trait DavHttpActorFactory {
  /**
    * A kill switch that can be used to cancel the current sync stream if a
    * fatal error related to HTTP requests is detected.
    */
  lazy val killSwitch: SharedKillSwitch = KillSwitches.shared(DavHttpActorFactory.KillSwitchName)

  /**
    * Creates the actor for sending HTTP requests to the Dav server.
    *
    * @param config         the DAV configuration
    * @param system         the actor system
    * @param clientCount    the number of clients for the actor
    * @param name           the name of the actor
    * @param withKillSwitch flag whether the kill switch should be used by the
    *                       resulting actor
    * @return the actor for sending HTTP requests
    */
  def createHttpRequestActor(config: DavConfig, system: ActorSystem, clientCount: Int, name: String,
                             withKillSwitch: Boolean = false): ActorRef = {
    val httpActor = system.actorOf(HttpRequestActor(config.rootUri), name)
    system.actorOf(authActorProps(config, system, clientCount, name, withKillSwitch, httpActor), name + "_auth")
  }

  /**
    * Creates a ''Props'' object for creating the actor that wraps the request
    * actor and handles authentication.
    *
    * @param config         the DAV configuration
    * @param system         the actor system
    * @param clientCount    the number of clients for the actor
    * @param name           the (base) name of the actor
    * @param withKillSwitch flag whether the kill switch should be used by the
    *                       resulting actor
    * @param httpActor      the request actor to be wrapped
    * @return ''Props'' for the authentication actor
    */
  protected def authActorProps(config: DavConfig, system: ActorSystem, clientCount: Int, name: String,
                               withKillSwitch: Boolean, httpActor: ActorRef): Props
}

/**
  * A factory for HTTP request actors using Basic Auth for authentication.
  */
object BasicAuthHttpActorFactory extends DavHttpActorFactory {
  override protected def authActorProps(config: DavConfig, system: ActorSystem, clientCount: Int, name: String,
                                        withKillSwitch: Boolean, httpActor: ActorRef): Props =
    HttpBasicAuthActor(httpActor, config.optBasicAuthConfig.get, clientCount)
}

/**
  * A factory for HTTP request actors using OAuth for authentication.
  *
  * @param storageConfig the storage configuration for the IDP
  * @param oauthConfig   the OAuth configuration
  * @param clientSecret  the client secret for the IDP
  * @param initTokenData initial token material
  */
class OAuthHttpActorFactory(storageConfig: OAuthStorageConfig,
                            oauthConfig: OAuthConfig,
                            clientSecret: Secret,
                            initTokenData: OAuthTokenData) extends DavHttpActorFactory {
  override protected def authActorProps(config: DavConfig, system: ActorSystem, clientCount: Int, name: String,
                                        withKillSwitch: Boolean, httpActor: ActorRef): Props = {
    val idpActor = system.actorOf(HttpRequestActor(oauthConfig.tokenEndpoint), name + "_idp")
    val optKillSwitch = if (withKillSwitch) Some(killSwitch) else None
    OAuthTokenActor(httpActor, clientCount, idpActor, storageConfig, oauthConfig, clientSecret,
      initTokenData, OAuthStorageServiceImpl, OAuthTokenRetrieverServiceImpl, optKillSwitch)
  }
}

/**
  * A special factory implementation for source components if the source
  * structure is a WebDav server.
  *
  * @param config           the configuration of the WebDav server
  * @param httpActorFactory the factory for creating the HTTP actor
  * @param system           the actor system
  * @param mat              the object to materialize streams
  */
private class DavComponentsSourceFactory(val config: DavConfig, httpActorFactory: DavHttpActorFactory)
                                        (implicit system: ActorSystem, mat: ActorMaterializer)
  extends SourceComponentsFactory {

  import DavHttpActorFactory._

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
private class DavComponentsDestinationFactory(val config: DavConfig, httpActorFactory: DavHttpActorFactory)
                                             (implicit system: ActorSystem, mat: ActorMaterializer)
  extends DestinationComponentsFactory {

  import DavHttpActorFactory._

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
    val opFlow = DavOperationHandler.webDavProcessingFlow(config, fileProvider, requestActor)
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
