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

import akka.actor.{Actor, ActorRef, PoisonPill}
import akka.http.scaladsl.model.HttpRequest
import com.github.sync.http.HttpExtensionActor.{RegisterClient, Release}
import com.github.sync.http.HttpRequestActor.SendRequest

object HttpExtensionActor {

  /**
    * A message processed by [[HttpExtensionActor]] indicating that a client no
    * longer needs this actor instance. When the actor has been released by all
    * its clients, it is stopped, and also the wrapped HTTP actor.
    */
  case object Release

  /**
    * A message processed by [[HttpExtensionActor]] that tells the actor that
    * it is assigned to another client. This increases the internal client
    * count; so the actor will only be stopped when the corresponding number of
    * [[Release]] messages has been received.
    */
  case object RegisterClient

}

/**
  * A trait that provides functionality for actors that extend the mechanism to
  * send HTTP requests.
  *
  * This trait can be extended by actors that wrap an [[HttpRequestActor]] and
  * modify requests before they are actually executed by the request actor.
  * This is used for instance to implement different ways of authentication.
  * The idea is that the original request is received by the extension actor,
  * modified, and then passed to the wrapped ''HttpRequestActor''. The response
  * is then passed to the caller.
  *
  * Actors for sending HTTP requests can be shared between multiple components.
  * When a component no longer needs the actor, it can release it. After it has
  * been released by all components, it should be stopped together with the
  * underlying ''HttpRequestActor''. This functionality is provided by this
  * trait, too.
  */
trait HttpExtensionActor {
  this: Actor =>

  /** Reference to the HTTP actor for executing requests. */
  protected val httpActor: ActorRef

  /**
    * The (initial) number of clients of this actor. This is used when handling
    * ''Release'' messages. When all clients have sent a ''Release'' message
    * the actor can be stopped.
    */
  protected val clientCount: Int

  /**
    * A counter for additional clients that have been registered later. This is
    * used in cases when the exact number of clients is not known at creation
    * time of the actor. The counter can be increased dynamically by sending
    * the actor ''RegisterClient'' messages.
    */
  private var additionalClientCount = 0

  /** A counter for the Release message that have been received. */
  private var releaseCount = 0

  override final def receive: Receive = customReceive orElse commonReceive

  /**
    * Sends a modified request to the wrapped HTTP actor. A copy of the
    * original request message is created with an ''HttpRequest'' obtained by
    * invoking the given function.
    *
    * @param orgRequest the original request
    * @param caller     the caller
    * @param f          the function to modify the HTTP request
    */
  protected def modifyAndForward(orgRequest: SendRequest, caller: ActorRef = sender())
                                (f: HttpRequest => HttpRequest): Unit = {
    val modifiedRequest = f(orgRequest.request)
    httpActor.tell(orgRequest.copy(request = modifiedRequest), caller)
  }

  /**
    * Switches to another state represented by the given receive function. This
    * function makes sure that the default message handling implemented by this
    * trait is still active.
    *
    * @param receive the new ''Receive'' function
    */
  protected def become(receive: Receive): Unit = {
    context.become(receive orElse commonReceive)
  }

  /**
    * Handles the final [[Release]] message to this actor. This implementation
    * stops this actor and the underlying HTTP actor. It can be overridden if
    * some further clean-up actions are required. (But then the super method
    * should be called.)
    */
  protected def release(): Unit = {
    httpActor ! PoisonPill
    context stop self
  }

  /**
    * A receive function to be implemented by derived classes to handle
    * specific messages. Derived actors must place their message handling code
    * here. In the normal ''receive'' function the default message handling of
    * this trait is implemented.
    *
    * @return the custom ''Receive'' function.
    */
  protected def customReceive: Receive

  /**
    * A ''Receive'' function that handles the messages supported by this
    * trait.
    *
    * @return the handling function for common messages
    */
  private def commonReceive: Receive = {
    case Release =>
      releaseCount += 1
      if (releaseCount >= clientCount + additionalClientCount) {
        release()
      }

    case RegisterClient =>
      additionalClientCount += 1
  }
}

/**
  * A special implementation of an [[HttpExtensionActor]] which does not
  * implement any special functionality.
  *
  * Instances of this actor class can be useful if an undecorated HTTP actor is
  * used which needs to be shared between multiple components. The actor can
  * then be wrapped inside an instance of this class which handles the sharing
  * aspect.
  *
  * @param httpActor   the underlying ''HttpRequestActor''
  * @param clientCount the number of clients
  */
class HttpNoOpExtensionActor(override val httpActor: ActorRef,
                             override val clientCount: Int) extends Actor with HttpExtensionActor {
  override protected def customReceive: Receive = {
    case req: SendRequest =>
      modifyAndForward(req)(identity)
  }
}
