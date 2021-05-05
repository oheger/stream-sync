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

package com.github.sync.http

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.testkit.{ImplicitSender, TestKit}
import com.github.sync.http.HttpExtensionActor.Release
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

object HttpMultiHostRequestActorSpec {
  /** A data object passed to a request. */
  private val RequestData = new Object

  /** A default path for test requests. */
  private val RequestPath = "/test-path"

  /** The capacity of the host cache for the test actor. */
  private val CacheSize = 3

  /**
    * Generates a URI to the test host with the given index.
    *
    * @param idx  the index of the test host
    * @param path the path for the request
    * @return the URI for this test request
    */
  private def requestUri(idx: Int, path: String): Uri =
    Uri(s"http://test.host$idx.org$path")

  /**
    * Generates a test request to the given test host.
    *
    * @param idx  the index of the test host
    * @param path the path for the request
    * @return the request to this host
    */
  private def testRequest(idx: Int, path: String = RequestPath): HttpRequestActor.SendRequest = {
    val request = HttpRequest(uri = requestUri(idx, path))
    HttpRequestActor.SendRequest(request, RequestData)
  }

  /**
    * Generates a result for an expected request.
    *
    * @param request the expected request
    * @return the corresponding result
    */
  private def testResult(request: HttpRequestActor.SendRequest): HttpRequestActor.Result = {
    val response = HttpResponse(headers = List(Location(request.request.uri)))
    HttpRequestActor.Result(request, response)
  }

  /**
    * Creates a ''Props'' object for a stub request actor that can handle the
    * given set of requests.
    *
    * @param requests the requests supported by the actor
    * @return ''Props'' for creating this test actor
    */
  private def requestActorProps(requests: Set[HttpRequestActor.SendRequest]): Props =
    Props(classOf[StubRequestActor], requests)

  /**
    * Creates a ''Props'' object for a stub request actor that can handle only
    * the given single request.
    *
    * @param singleRequest the only supported request
    * @return ''Props'' for creating this test actor
    */
  private def requestActorProps(singleRequest: HttpRequestActor.SendRequest): Props =
    requestActorProps(Set(singleRequest))

  /**
    * A test actor that simulates a request actor.
    *
    * The actor is initialized with a set of expected requests. When one of
    * these requests is received it is answered with a corresponding response;
    * other requests are ignored. That way it can be tested whether the
    * requests are routed to the correct request actor.
    *
    * @param expRequests a set with expected requests
    */
  class StubRequestActor(expRequests: Set[HttpRequestActor.SendRequest]) extends Actor {
    override def receive: Receive = {
      case req: HttpRequestActor.SendRequest if expRequests.contains(req) =>
        sender() ! testResult(req)
    }
  }

}

/**
  * Test class for ''HttpMultiHostRequestActor''.
  */
class HttpMultiHostRequestActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("HttpMultiHostRequestActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import HttpMultiHostRequestActorSpec._

  /**
    * Creates a test actor instance that can delegate requests to the given
    * stub actors. This function creates an actor instance with an overridden
    * function for the creation of sub request actors.
    *
    * @param stubActorProps    a mapping from URIs to stub actor props
    * @param requestActorQueue a queue to query request actors created by the
    *                          test actor
    * @return the test actor instance
    */
  private def createMultiHostActor(stubActorProps: Map[Uri, Props],
                                   requestActorQueue: BlockingQueue[ActorRef] = new LinkedBlockingQueue): ActorRef =
    system.actorOf(Props(new HttpMultiHostRequestActor(CacheSize, 1) {
      override private[http] def requestActorProps(uri: Uri) = {
        val baseProps = super.requestActorProps(uri)
        baseProps should be(HttpRequestActor(uri))
        stubActorProps(uri)
      }

      /**
        * @inheritdoc This implementation stores the new actor reference in a
        *             queue, so that it can be obtained by test cases.
        */
      override private[http] def createRequestActor(props: Props): ActorRef = {
        val actor = super.createRequestActor(props)
        requestActorQueue offer actor
        actor
      }
    }))

  /**
    * Obtains a request actor from the given queue. Handles timeouts.
    *
    * @param queue the queue
    * @return the request actor
    */
  private def pollForRequestActor(queue: BlockingQueue[ActorRef]): ActorRef = {
    val actor = queue.poll(3, TimeUnit.SECONDS)
    actor should not be null
    actor
  }

  /**
    * Sends the given sequence of requests to the test actor and expects the
    * corresponding responses.
    *
    * @param multiActor the test actor
    * @param requests   the requests to be sent
    */
  private def executeRequests(multiActor: ActorRef, requests: Iterable[HttpRequestActor.SendRequest]): Unit = {
    requests foreach { req =>
      multiActor ! req
      expectMsg(testResult(req))
    }
  }

  "HttpMultiHostRequestActor" should "create correct Props" in {
    val ClientCount = 7
    val props = HttpMultiHostRequestActor(CacheSize, ClientCount)

    props.actorClass() should be(classOf[HttpMultiHostRequestActor])
    props.args should contain theSameElementsInOrderAs List(CacheSize, ClientCount)
  }

  it should "forward requests to sub request actors" in {
    val requests = (1 to CacheSize) map (idx => testRequest(idx))
    val subProps = requests.map(req => (req.request.uri, requestActorProps(req))).toMap
    val multiActor = createMultiHostActor(subProps)

    executeRequests(multiActor, requests)
  }

  it should "forward requests to the same host to the same request actor" in {
    val req1 = testRequest(1)
    val req2 = testRequest(1, "/anotherPath")
    val subProps = Map(req1.request.uri -> requestActorProps(Set(req1, req2)))
    val multiActor = createMultiHostActor(subProps)

    multiActor ! req1
    expectMsg(testResult(req1))
    multiActor ! req2
    expectMsg(testResult(req2))
  }

  it should "keep request actors in a LRU cache and stop old instances" in {
    val req1a = testRequest(1)
    val req1b = testRequest(1, "/alternative")
    val req2 = testRequest(2)
    val req3 = testRequest(3)
    val req4 = testRequest(4)
    val requests = List(req1a, req2, req1b, req3, req4)
    val subProps = Map(req1a.request.uri -> requestActorProps(Set(req1a, req1b)),
      req2.request.uri -> requestActorProps(req2),
      req3.request.uri -> requestActorProps(req3),
      req4.request.uri -> requestActorProps(req4))
    val requestActorQueue = new LinkedBlockingQueue[ActorRef]
    val multiActor = createMultiHostActor(subProps, requestActorQueue)

    executeRequests(multiActor, requests)
    val reqActor1 = pollForRequestActor(requestActorQueue) // first actor still alive
    reqActor1 ! req1a
    expectMsg(testResult(req1a))
    val reqActor2 = pollForRequestActor(requestActorQueue) // second one should be stopped
    watch(reqActor2)
    expectTerminated(reqActor2)
  }

  it should "handle the Release message" in {
    val requests = (1 to CacheSize) map (idx => testRequest(idx))
    val subProps = requests.map(req => (req.request.uri, requestActorProps(req))).toMap
    val requestActorQueue = new LinkedBlockingQueue[ActorRef]
    val multiActor = createMultiHostActor(subProps, requestActorQueue)
    executeRequests(multiActor, requests)
    val requestActors = requests map (_ => pollForRequestActor(requestActorQueue))

    multiActor ! Release
    requestActors foreach { actor =>
      watch(actor)
      expectTerminated(actor)
    }
    watch(multiActor)
    expectTerminated(multiActor)
  }
}
