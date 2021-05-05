/*
 * Copyright 2018-2021 The Developers Team.
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

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.{KillSwitches, SharedKillSwitch}
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthTokenData
import com.github.sync.http.oauth._
import com.github.sync.http.{BasicAuthConfig, HttpBasicAuthActor, HttpConfig, HttpNoOpExtensionActor, HttpRequestActor, OAuthStorageConfig}

object HttpActorFactory {
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
  * A trait that knows how to create the HTTP request actor for sync processes
  * against an HTTP server.
  *
  * Depending on the authentication scheme used by the HTTP server, the actor
  * has to be created differently. This is abstracted via this trait.
  */
sealed trait HttpActorFactory {
  /**
    * A kill switch that can be used to cancel the current sync stream if a
    * fatal error related to HTTP requests is detected.
    */
  lazy val killSwitch: SharedKillSwitch = KillSwitches.shared(HttpActorFactory.KillSwitchName)

  /**
    * Returns a ''Props'' object for creating the basic HTTP request actor. The
    * concrete actor type also depends on the specific HTTP protocol; therefore
    * the creation ''Props'' have to be passed in.
    *
    * @return ''Props'' to create the HTTP request actor
    */
  def httpRequestActorProps: Props

  /**
    * Creates the actor for sending HTTP requests to the HTTP server.
    *
    * @param config         the HTTP configuration
    * @param system         the actor system
    * @param clientCount    the number of clients for the actor
    * @param name           the name of the actor
    * @param withKillSwitch flag whether the kill switch should be used by the
    *                       resulting actor
    * @return the actor for sending HTTP requests
    */
  def createHttpRequestActor(config: HttpConfig, system: ActorSystem, clientCount: Int, name: String,
                             withKillSwitch: Boolean = false): ActorRef = {
    val httpActor = system.actorOf(httpRequestActorProps, name)
    system.actorOf(authActorProps(config, system, clientCount, name, withKillSwitch, httpActor), name + "_auth")
  }

  /**
    * Creates a ''Props'' object for creating the actor that wraps the request
    * actor and handles authentication.
    *
    * @param config         the HTTP configuration
    * @param system         the actor system
    * @param clientCount    the number of clients for the actor
    * @param name           the (base) name of the actor
    * @param withKillSwitch flag whether the kill switch should be used by the
    *                       resulting actor
    * @param httpActor      the request actor to be wrapped
    * @return ''Props'' for the authentication actor
    */
  protected def authActorProps(config: HttpConfig, system: ActorSystem, clientCount: Int, name: String,
                               withKillSwitch: Boolean, httpActor: ActorRef): Props
}

/**
  * A factory for HTTP request actors using Basic Auth for authentication.
  *
  * @param httpRequestActorProps ''Props'' to create the request actor
  */
class BasicAuthHttpActorFactory(override val httpRequestActorProps: Props) extends HttpActorFactory {
  override protected def authActorProps(config: HttpConfig, system: ActorSystem, clientCount: Int, name: String,
                                        withKillSwitch: Boolean, httpActor: ActorRef): Props =
    HttpBasicAuthActor(httpActor, config.authConfig.asInstanceOf[BasicAuthConfig], clientCount)
}

/**
  * A factory for HTTP request actors using OAuth for authentication.
  *
  * @param httpRequestActorProps ''Props'' to create the request actor
  * @param storageConfig         the storage configuration for the IDP
  * @param oauthConfig           the OAuth configuration
  * @param clientSecret          the client secret for the IDP
  * @param initTokenData         initial token material
  */
class OAuthHttpActorFactory(override val httpRequestActorProps: Props,
                            storageConfig: OAuthStorageConfig,
                            oauthConfig: OAuthConfig,
                            clientSecret: Secret,
                            initTokenData: OAuthTokenData) extends HttpActorFactory {
  override protected def authActorProps(config: HttpConfig, system: ActorSystem, clientCount: Int, name: String,
                                        withKillSwitch: Boolean, httpActor: ActorRef): Props = {
    val idpActor = system.actorOf(HttpRequestActor(oauthConfig.tokenEndpoint), name + "_idp")
    val optKillSwitch = if (withKillSwitch) Some(killSwitch) else None
    OAuthTokenActor(httpActor, clientCount, idpActor, storageConfig, oauthConfig, clientSecret,
      initTokenData, OAuthStorageServiceImpl, OAuthTokenRetrieverServiceImpl, optKillSwitch)
  }
}

/**
  * A factory for HTTP request actors that does not support any authentication.
  *
  * This implementation returns ''Props'' for a ''HttpNoOpExtensionActor'',
  * just to wrap the passed in request actor.
  *
  * @param httpRequestActorProps ''Props'' to create the request actor
  */
class NoAuthHttpActorFactory(override val httpRequestActorProps: Props) extends HttpActorFactory {
  override protected def authActorProps(config: HttpConfig, system: ActorSystem, clientCount: Int,
                                        name: String, withKillSwitch: Boolean, httpActor: ActorRef): Props =
    Props(classOf[HttpNoOpExtensionActor], httpActor, clientCount)
}
