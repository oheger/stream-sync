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

package com.github.sync.onedrive

import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.testkit.TestKit
import com.github.sync.SyncTypes.{ActionCreate, ActionRemove, FsFile, FsFolder, SyncOperation}
import com.github.sync.http.{HttpBasicAuthActor, HttpRequestActor}
import com.github.sync.util.UriEncodingHelper
import com.github.sync.{AsyncTestHelper, SourceFileProvider, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

object OneDriveOperationHandlerSpec {
  /** The path to be synced. */
  private val SyncPath = "/sync/data"
}

/**
  * Test class for ''OneDriveOperationHandler''.
  */
class OneDriveOperationHandlerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with WireMockSupport with OneDriveStubbingSupport
  with AsyncTestHelper with MockitoSugar {
  def this() = this(ActorSystem("OneDriveOperationHandlerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  override protected def afterEach(): Unit = {
    futureResult(Http().shutdownAllConnectionPools())
    super.afterEach()
  }

  import OneDriveOperationHandlerSpec._
  import WireMockSupport._

  /**
    * Creates a test OneDrive configuration.
    *
    * @return the test configuration
    */
  private def createConfig(): OneDriveConfig =
    createOneDriveConfig(SyncPath).copy(uploadChunkSize = 1024)

  /**
    * Executes a stream with the given sync operations and returns the result.
    *
    * @param operations   the sync operations to be executed
    * @param config       the DAV configuration
    * @param fileProvider the source file provider
    * @return the resulting list of sync operations
    */
  private def runSync(operations: List[SyncOperation],
                      config: OneDriveConfig,
                      fileProvider: SourceFileProvider = mock[SourceFileProvider]): List[SyncOperation] = {
    val decider: Supervision.Decider = ex => {
      ex.printStackTrace()
      Supervision.Resume
    }
    implicit val mat: ActorMaterializer =
      ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))
    val requestActor = system.actorOf(HttpRequestActor(config.rootUri))
    val source = Source(operations)
    val sink = Sink.fold[List[SyncOperation], SyncOperation](List.empty) { (lst, op) =>
      op :: lst
    }
    val futResult = source
      .via(OneDriveOperationHandler(config, fileProvider, requestActor))
      .runWith(sink)
    futureResult(futResult).reverse
  }

  "OneDriveOperationHandler" should "delete a file" in {
    val file = FsFile("/someFile.txt", 1, Instant.now(), 1000)
    val dstUri = "/dest/delete/file.txt"
    val op = SyncOperation(file, ActionRemove, 0, null, dstUri)
    stubSuccess(NoAuthFunc)
    val config = createConfig()

    val syncRes = runSync(List(op), config)
    syncRes should contain only op
    verify(deleteRequestedFor(urlPathEqualTo(path(mapElementUri(config, dstUri)))))
  }

  it should "delete a folder" in {
    val folder = FsFolder("/delFolder", 1)
    val dstUri = "/dest/delete/folder"
    val op = SyncOperation(folder, ActionRemove, 0, null, dstUri)
    stubSuccess(NoAuthFunc)
    val config = createConfig()

    val syncRes = runSync(List(op), config)
    syncRes should contain only op
    verify(deleteRequestedFor(urlPathEqualTo(path(mapElementUri(config, dstUri)))))
  }

  it should "create a new folder" in {
    val newName = "new folder"
    val folder = FsFolder("/some/path/" + newName, 3)
    val parentUri = "/the/parent/folder"
    val dstUri = parentUri + "/" + UriEncodingHelper.encode(newName)
    val op = SyncOperation(folder, ActionCreate, 0, null, dstUri)
    val expRequest =
      s"""
         |{
         |  "name": "$newName",
         |  "folder": {},
         |  "@microsoft.graph.conflictBehavior": "fail"
         |}
         |""".stripMargin
    stubSuccess(NoAuthFunc)
    val config = createConfig()

    val syncRes = runSync(List(op), config)
    syncRes should contain only op
    verify(postRequestedFor(urlPathEqualTo(path(mapFolderUri(config, parentUri))))
      .withRequestBody(equalToJson(expRequest)))
  }
}
