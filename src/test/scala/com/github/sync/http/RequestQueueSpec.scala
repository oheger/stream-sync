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

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.scaladsl.Flow
import akka.testkit.TestKit
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.util.Try

object RequestQueueSpec {
  /** A host name to be used by tests. */
  private val Host = "localhost"
}

/**
  * Test class for ''RequestQueue'' (mainly the companion object).
  */
class RequestQueueSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike with
  BeforeAndAfterAll with Matchers with MockitoSugar {

  def this() = this(ActorSystem("RequestQueueSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import RequestQueueSpec._

  "The RequestQueue object" should "extract the port of an HTTPS URI" in {
    val uri = Uri("https://secure.webdav.org")

    RequestQueue.extractPort(uri) should be(443)
  }

  it should "extract the port of an HTTP URI" in {
    val uri = Uri("http://simple.webdav.org")

    RequestQueue.extractPort(uri) should be(80)
  }

  it should "extract the port from an URI if it is provided" in {
    val port = 8080
    val uri = Uri(s"https://special.webdav.org:$port/test")

    RequestQueue.extractPort(uri) should be(port)
  }

  /**
    * Generates a flow to send HTTP requests. This flow is not really used by
    * the tests (as no requests are sent), but just to have an object.
    *
    * @return the request flow
    */
  private def createRequestFlow(): Flow[(HttpRequest, Any), (Try[HttpResponse], Any),
    Http.HostConnectionPool] = {
    Http().cachedHostConnectionPool[Any](Host)
  }

  it should "create an HTTP request flow to a host" in {
    val httpExt = mock[HttpExt]
    val flow = createRequestFlow()
    val uri = Uri(authority = Uri.Authority(Uri.Host(Host)), scheme = "http",
      path = Uri.Path("/somePath"))
    when(httpExt.cachedHostConnectionPool[Any](Host, 80)).thenReturn(flow)

    RequestQueue.createPoolClientFlow(uri, httpExt) should be(flow)
  }

  it should "create an HTTPS request flow to a host" in {
    val httpExt = mock[HttpExt]
    val flow = createRequestFlow()
    val uri = Uri(authority = Uri.Authority(Uri.Host(Host)), scheme = "https",
      path = Uri.Path("/securePath"))
    when(httpExt.cachedHostConnectionPoolHttps[Any](Host, 443)).thenReturn(flow)

    RequestQueue.createPoolClientFlow(uri, httpExt) should be(flow)
  }

  it should "create an HTTP request flow with a non-standard port to a host" in {
    val Port = 8888
    val httpExt = mock[HttpExt]
    val flow = createRequestFlow()
    val uri = Uri(authority = Uri.Authority(Uri.Host(Host), port = Port), scheme = "http")
    when(httpExt.cachedHostConnectionPool[Any](Host, Port)).thenReturn(flow)

    RequestQueue.createPoolClientFlow(uri, httpExt) should be(flow)
  }
}
