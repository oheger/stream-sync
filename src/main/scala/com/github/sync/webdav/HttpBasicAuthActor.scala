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

package com.github.sync.webdav

import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import com.github.sync.http.HttpRequestActor.SendRequest

object HttpBasicAuthActor {
  /**
    * Returns a ''Props'' object for creating new actor instances.
    *
    * @param httpActor   the underlying ''HttpRequestActor''
    * @param config      the basic auth configuration
    * @param clientCount the number of clients
    * @return the ''Props'' for creating a new actor instance
    */
  def apply(httpActor: ActorRef, config: BasicAuthConfig, clientCount: Int = 1): Props =
    Props(classOf[HttpBasicAuthActor], httpActor, config, clientCount)
}

/**
  * An actor class that adds basic authentication to HTTP requests.
  *
  * Based on the given configuration, a proper ''Authorization'' header is
  * added to HTTP requests sent to this actor.
  *
  * @param httpActor   the underlying ''HttpRequestActor''
  * @param config      the basic auth configuration
  * @param clientCount the number of clients
  */
class HttpBasicAuthActor(override val httpActor: ActorRef,
                         config: BasicAuthConfig,
                         override val clientCount: Int) extends Actor with HttpExtensionActor {
  /** The authorization header to be added to requests. */
  private val authHeader = createAuthHeader(config)

  override def customReceive: Receive = {
    case req: SendRequest =>
      modifyAndForward(req) { request =>
        request.copy(headers = req.request.headers :+ authHeader)
      }
  }

  /**
    * Generates an ''Authorization'' header based on the given configuration.
    *
    * @param config the configuration for basic auth
    * @return a header to authenticate against this WebDav server
    */
  private def createAuthHeader(config: BasicAuthConfig): Authorization =
    Authorization(BasicHttpCredentials(config.user, config.password.secret))
}
