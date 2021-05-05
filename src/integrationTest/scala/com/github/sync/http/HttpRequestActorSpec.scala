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

import akka.actor.{ActorSystem, Props, Terminated}
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.pattern.ask
import akka.stream.scaladsl.Sink
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.{ByteString, Timeout}
import com.github.sync.http.HttpRequestActor.SendRequest
import com.github.sync.{AsyncTestHelper, FileTestHelper, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.http.Fault
import org.mockito.Mockito
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

object HttpRequestActorSpec {
  /** A data object passed with the request. */
  private val RequestData = new Object

  /** A test request path. */
  private val Path = "/foo"

  /** A timeout for querying the actor under test. */
  private implicit val RequestTimeout: Timeout = Timeout(3.seconds)
}

/**
  * Integration test class for ''HttpRequestActor''.
  */
class HttpRequestActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with WireMockSupport with AsyncTestHelper
  with MockitoSugar {
  def this() = this(ActorSystem("HttpRequestActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import HttpRequestActorSpec._

  "HttpRequestActor" should "send a HTTP request" in {
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.Accepted.intValue)
        .withBodyFile("response.txt")))
    val actor = system.actorOf(HttpRequestActor(serverUri("")))
    val request = SendRequest(HttpRequest(uri = Path), RequestData)

    actor ! request
    val result = expectMsgType[HttpRequestActor.Result]
    result.request should be(request)
    result.response.status should be(StatusCodes.Accepted)

    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    val content = futureResult(result.response.entity.dataBytes.runWith(sink))
    content.utf8String should be(FileTestHelper.TestDataSingleLine)
  }

  it should "return a failed future for a non-success response" in {
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.BadRequest.intValue)
        .withBodyFile("response.txt")))
    val actor = system.actorOf(HttpRequestActor(serverUri("")))
    val request = SendRequest(HttpRequest(uri = Path), RequestData)

    val futResponse = actor ? request
    val ex = expectFailedFuture[HttpRequestActor.RequestException](futResponse)
    ex.request should be(request)
    ex.cause match {
      case resp: HttpRequestActor.FailedResponseException =>
        resp.response.status should be(StatusCodes.BadRequest)
      case t => fail(t)
    }
  }

  it should "discard the entity bytes if failure responses are received" in {
    val ErrorPath = "/error"
    stubFor(get(urlPathEqualTo(ErrorPath))
      .willReturn(aResponse()
        .withStatus(StatusCodes.BadRequest.intValue)
        .withBodyFile("response.txt")))
    stubFor(get(urlPathEqualTo(Path))
      .willReturn(aResponse()
        .withStatus(StatusCodes.OK.intValue)))
    val actor = system.actorOf(HttpRequestActor(serverUri(""), 64))
    val errRequest = SendRequest(HttpRequest(uri = ErrorPath), RequestData)
    (1 to 32) foreach { _ => actor ? errRequest }

    val request = SendRequest(HttpRequest(uri = Path), RequestData)
    actor ! request
    expectMsgType[HttpRequestActor.Result]
  }

  it should "handle an exception from the server" in {
    stubFor(get(anyUrl())
      .willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE)))
    val actor = system.actorOf(HttpRequestActor(serverUri("")))
    val request = SendRequest(HttpRequest(uri = Path), RequestData)

    val futResponse = actor ? request
    val ex = expectFailedFuture[HttpRequestActor.RequestException](futResponse)
    ex.request should be(request)
  }

  it should "shutdown the request queue when it is stopped" in {
    val queue = mock[RequestQueue]
    val actor = system.actorOf(Props(new HttpRequestActor(serverUri(""), 2) {
      override private[http] def createRequestQueue(): RequestQueue = queue
    }))

    system stop actor
    val probe = TestProbe()
    probe watch actor
    probe.expectMsgType[Terminated]
    Mockito.verify(queue).shutdown()
  }
}
