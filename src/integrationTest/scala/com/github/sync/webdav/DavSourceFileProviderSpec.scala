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

import java.time.Instant

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.{TestKit, TestProbe}
import akka.util.{ByteString, Timeout}
import com.github.sync.SyncTypes.FsFile
import com.github.sync.WireMockSupport._
import com.github.sync.crypt.Secret
import com.github.sync.http.{HttpBasicAuthActor, HttpExtensionActor, HttpRequestActor}
import com.github.sync.{AsyncTestHelper, FileTestHelper, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._

object DavSourceFileProviderSpec {
  /** The root path of the simulated sync operation. */
  private val RootPath = "/stream-sync/test"
}

/**
  * Test class for ''DavSourceFileProvider''.
  */
class DavSourceFileProviderSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers with AsyncTestHelper with WireMockSupport {
  def this() = this(ActorSystem("DavSourceFileProviderSpec"))

  override protected def afterAll(): Unit = {
    futureResult(Http().shutdownAllConnectionPools())
    TestKit shutdownActorSystem system
  }

  /** The object to materialize streams. */
  implicit val mat: ActorMaterializer = ActorMaterializer()

  import DavSourceFileProviderSpec._
  import system.dispatcher
  import WireMockSupport.BasicAuthFunc

  /**
    * Returns a config for WebDav operations.
    *
    * @return the ''DavConfig''
    */
  private def createConfig(): DavConfig =
    DavConfig(serverUri(RootPath), DavConfig.DefaultModifiedProperty, None,
      deleteBeforeOverride = false, modifiedProperties = List(DavConfig.DefaultModifiedProperty),
      Timeout(10.seconds), optBasicAuthConfig = Some(BasicAuthConfig(UserId, Secret(Password))),
      optOAuthConfig = None)

  /**
    * Creates an actor for sending HTTP requests.
    *
    * @param config the DAV config
    * @return the request actor
    */
  private def createRequestActor(config: DavConfig): ActorRef = {
    val httpActor = system.actorOf(HttpRequestActor(serverUri(""), 2))
    system.actorOf(HttpBasicAuthActor(httpActor, config.optBasicAuthConfig.get))
  }

  "A DavSourceFileProvider" should "provide a source for a requested existing file" in {
    stubFor(BasicAuthFunc(get(urlPathEqualTo(RootPath + "/my%20data/request.txt")))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBodyFile("response.txt")))
    val file = FsFile("/my data/request.txt", 2, Instant.now(), 42)
    val config = createConfig()
    val provider = DavSourceFileProvider(config, createRequestActor(config))
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)

    val response = futureResult(provider.fileSource(file.relativeUri).flatMap { src => src.runWith(sink) })
    val expected = FileTestHelper.TestDataSingleLine
    response.utf8String should be(expected)
  }

  it should "return a failed future for a failed request" in {
    val elemUri = "/test"
    stubFor(BasicAuthFunc(get(urlPathEqualTo(RootPath + elemUri)))
      .willReturn(aResponse().withStatus(StatusCodes.InternalServerError.intValue)))
    val file = FsFile(elemUri, 0, Instant.now(), 5)
    val config = createConfig()
    val provider = DavSourceFileProvider(config, createRequestActor(config))

    val ex = expectFailedFuture[HttpRequestActor.RequestException](provider fileSource file.relativeUri)
    ex.request.request.uri should be(Uri(RootPath + elemUri))
    ex.cause match {
      case fre: HttpRequestActor.FailedResponseException =>
        fre.response.status should be(StatusCodes.InternalServerError)
      case e => fail(e)
    }
  }

  it should "consume the response entity also in case of an error" in {
    val fileUri = "/data.txt"
    stubFor(get(anyUrl()).atPriority(PriorityDefault)
      .willReturn(aResponse().withStatus(StatusCodes.NotFound.intValue)
        .withBody("The file you are looking for was not found!")))
    stubFor(BasicAuthFunc(get(urlPathEqualTo(RootPath + fileUri)).atPriority(PrioritySpecific))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBodyFile("response.txt")))
    val file = FsFile(fileUri, 1, Instant.now(), 100)
    val config = createConfig()
    val provider = DavSourceFileProvider(config, createRequestActor(config))

    val testFuture = Future {
      (1 to 32).map(i => FsFile(s"/test$i.txt", 1, Instant.now(), 13))
        .foreach { f =>
          expectFailedFuture[HttpRequestActor.RequestException](provider fileSource f.relativeUri)
          val source = futureResult(provider fileSource file.relativeUri)
          futureResult(source.runWith(Sink.ignore))
        }
    }
    futureResult(testFuture)
  }

  it should "release the request actor when it is shutdown" in {
    val probeHttpActor = TestProbe()
    val provider = new DavSourceFileProvider(createConfig(), probeHttpActor.ref)

    provider.shutdown()
    probeHttpActor.expectMsg(HttpExtensionActor.Release)
  }
}
