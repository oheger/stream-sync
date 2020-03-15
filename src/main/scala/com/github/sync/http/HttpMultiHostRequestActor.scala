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

package com.github.sync.http

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.model.Uri
import com.github.sync.http.HttpRequestActor.SendRequest
import com.github.sync.util.LRUCache

object HttpMultiHostRequestActor {
  /**
    * Returns a ''Props'' object for creating a new instance of this actor.
    *
    * @param cacheSize   the size of the host cache
    * @param clientCount the initial number of clients of this actor
    * @return a ''Props'' for creating a new actor instance
    */
  def apply(cacheSize: Int, clientCount: Int): Props =
    Props(classOf[HttpMultiHostRequestActor], cacheSize, clientCount)
}

/**
  * An actor implementation that can send HTTP requests to multiple hosts.
  *
  * [[HttpRequestActor]] is configured with a single host, to which all
  * requests are sent. This actor creates such request actors dynamically
  * whenever a new host is encountered. This is needed by certain HTTP-based
  * protocols, for instance if download requests are served by a different
  * host than API requests.
  *
  * Request actors created by this actor are hold in a LRU cache with a
  * configurable capacity. If an actor is removed from this cache (because the
  * maximum capacity is reached), it is stopped.
  *
  * @param cacheSize   the size of the host cache
  * @param clientCount the initial number of clients of this actor
  */
class HttpMultiHostRequestActor(cacheSize: Int, override val clientCount: Int) extends Actor with HttpExtensionActor
  with ActorLogging {
  /**
    * Reference to the HTTP actor for executing requests. Note that this field
    * is not used by this implementation. There is not a single managed request
    * actor, but multiple ones.
    */
  override protected val httpActor: ActorRef = null

  /** A cache for the request actors for specific hosts. */
  private var requestActors = LRUCache[Uri.Authority, ActorRef](cacheSize)

  override protected def customReceive: Receive = {
    case req: SendRequest =>
      val requestActor = fetchRequestActorForUri(req.request.uri)
      requestActor forward req
  }

  /**
    * @inheritdoc This implementation stops this actor, which also causes the
    *             managed request actors (its children) to be stopped.
    */
  override protected def release(): Unit = {
    context stop self
  }

  /**
    * Returns a ''Props'' object for creating a request actor for the given
    * URI.
    *
    * @param uri the target URI
    * @return a ''Props'' for the request actor for this URI
    */
  private[http] def requestActorProps(uri: Uri): Props = HttpRequestActor(uri)

  /**
    * Creates a new request actor based on the given properties. (This method
    * is mainly used for testing purposes.)
    *
    * @param props the ''Props'' for the new request actor
    * @return the new request actor
    */
  private[http] def createRequestActor(props: Props): ActorRef = context.actorOf(props)

  /**
    * Obtains the actor to handle the request for the given URI. If an actor
    * for the current host is not yet contained in the cache, it is created
    * now. In any case, the LRU cache is updated accordingly.
    *
    * @param uri the URI of the current request
    * @return the actor to handle this request
    */
  private def fetchRequestActorForUri(uri: Uri): ActorRef = {
    val authority = uri.authority
    if (requestActors contains authority) {
      val actor = requestActors(authority)
      requestActors = requestActors.put(authority -> actor) // move to front
      actor
    } else {
      log.info("Creating request actor for {}.", authority)
      val actor = createRequestActor(requestActorProps(uri))
      val nextCache = requestActors.put(authority -> actor)
      val removedActorKeys = requestActors.keySet -- nextCache.keySet
      removedActorKeys.map(requestActors(_)).foreach(context.stop)
      requestActors = nextCache
      actor
    }
  }
}
