/*
 * Copyright 2018 The Developers Team.
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

import java.io.IOException
import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import akka.util.ByteString
import com.github.sync.{AsyncTestHelper, FileTestHelper, FsFile, WireMockSupport}
import com.github.sync.WireMockSupport._
import com.github.tomakehurst.wiremock.client.WireMock._
import org.mockito.Mockito
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object DavSourceFileProviderSpec {
  /** The root path of the simulated sync operation. */
  private val RootPath = "/stream-sync/test"
}

/**
  * Test class for ''DavSourceFileProvider''.
  */
class DavSourceFileProviderSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers with AsyncTestHelper with WireMockSupport
  with MockitoSugar {
  def this() = this(ActorSystem("DavSourceFileProviderSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /** The object to materialize streams. */
  implicit val mat: ActorMaterializer = ActorMaterializer()

  import DavSourceFileProviderSpec._
  import system.dispatcher

  /**
    * Returns a config for WebDav operations.
    *
    * @return the ''DavConfig''
    */
  private def createConfig(): DavConfig =
    DavConfig(serverUri(RootPath), UserId, Password, DavConfig.DefaultModifiedProperty, None)

  "A DavSourceFileProvider" should "provide a source for a requested existing file" in {
    stubFor(authorized(get(urlPathEqualTo(RootPath + "/my%20data/request.txt")))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBodyFile("response.txt")))
    val file = FsFile("/my data/request.txt", 2, Instant.now(), 42)
    val provider = DavSourceFileProvider(createConfig())
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)

    val response = futureResult(provider.fileSource(file).flatMap { src => src.runWith(sink) })
    val expected = FileTestHelper.TestData.replace("\r\n", " ")
    response.utf8String should be(expected)
  }

  it should "return a failed future for a failed request" in {
    val elemUri = "/test"
    stubFor(authorized(get(urlPathEqualTo(RootPath + elemUri)))
      .willReturn(aResponse().withStatus(StatusCodes.InternalServerError.intValue)))
    val file = FsFile(elemUri, 0, Instant.now(), 5)
    val provider = DavSourceFileProvider(createConfig())

    val ex = expectFailedFuture[IOException](provider fileSource file)
    ex.getMessage should include("500")
    ex.getMessage should include(RootPath + elemUri)
  }

  it should "shutdown the request queue when it is shutdown" in {
    val requestQueue = mock[RequestQueue]
    val provider = new DavSourceFileProvider(createConfig(), requestQueue)

    provider.shutdown()
    Mockito.verify(requestQueue).shutdown()
  }
}