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

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Terminated}
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.github.sync.webdav.HttpExtensionActor.Release
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object HttpBasicAuthActorSpec {
  /** A test user name. */
  val User = "testUser"

  /** A test password. */
  val Password = "testPassword"

  /** A test DAV configuration. */
  private val TestDavConfig = createConfig()

  /**
    * Creates a DAV configuration with test settings.
    *
    * @return the test DAV configuration
    */
  private def createConfig(): DavConfig =
    DavConfig(rootUri = "http://www.test.org", lastModifiedProperty = "test", lastModifiedNamespace = None,
      deleteBeforeOverride = false, modifiedProperties = List.empty, timeout = Timeout(1.minute),
      user = User, password = Password)
}

/**
  * Test class for ''HttpBasicAuthActor''. This class also tests functionality
  * of the extension trait.
  */
class HttpBasicAuthActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with FlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("HttpBasicAuthActorSpec"))

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit shutdownActorSystem system
  }

  import HttpBasicAuthActorSpec._

  /**
    * Checks that the given actor has been stopped.
    *
    * @param actor the actor
    */
  private def checkStopped(actor: ActorRef): Unit = {
    val watcher = TestProbe()
    watcher watch actor
    watcher.expectMsgType[Terminated]
  }

  "HttpBasicAuthActor" should "add an Authorization header to requests" in {
    val TestPath = Uri("/test")
    val request = HttpRequestActor.SendRequest(HttpRequest(uri = TestPath), "someData")
    val httpActor = system.actorOf(Props(classOf[StubHttpRequestActor], TestPath))
    val authActor = system.actorOf(HttpBasicAuthActor(httpActor, TestDavConfig))

    authActor ! request
    val result = expectMsgType[HttpRequestActor.Result]
    result.response.status should be(StatusCodes.OK)
    result.request should not be request
    result.request.data should be(request.data)
  }

  it should "stop itself and the underlying HTTP actor when receiving the last Release message" in {
    val probeHttp = TestProbe()
    val authActor = system.actorOf(HttpBasicAuthActor(probeHttp.ref, TestDavConfig))

    authActor ! HttpExtensionActor.Release
    checkStopped(probeHttp.ref)
    checkStopped(authActor)
  }

  it should "not stop itself before the last client sends a Release message" in {
    val probeHttp = TestProbe()
    val authActor = system.actorOf(HttpBasicAuthActor(probeHttp.ref, TestDavConfig, 3))
    authActor ! Release
    authActor ! Release

    authActor ! HttpRequestActor.SendRequest(HttpRequest(uri = "/foo"), "test")
    probeHttp.expectMsgType[HttpRequestActor.SendRequest]
    authActor ! Release
    checkStopped(probeHttp.ref)
  }
}

/**
  * A test actor simulating an HTTP actor. Incoming requests are validated.
  * If they match the expectations, a success response is returned.
  *
  * @param expPath the expected request URI path
  */
class StubHttpRequestActor(expPath: Uri) extends Actor {
  override def receive: Receive = {
    case req: HttpRequestActor.SendRequest if validateRequest(req) =>
      val result = HttpRequestActor.Result(req, HttpResponse())
      sender() ! result
  }

  /**
    * Checks whether an incoming request matches the expected criteria.
    *
    * @param req the request to check
    * @return a flag whether the request matches
    */
  private def validateRequest(req: HttpRequestActor.SendRequest): Boolean = {
    if (req.request.uri == expPath) {
      req.request.header[Authorization] match {
        case Some(Authorization(BasicHttpCredentials(HttpBasicAuthActorSpec.User,
        HttpBasicAuthActorSpec.Password))) =>
          true
        case _ => false
      }
    } else false
  }
}
