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
import akka.http.scaladsl.model.StatusCodes
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.testkit.TestKit
import akka.util.ByteString
import com.github.sync.SyncTypes._
import com.github.sync.http.HttpRequestActor
import com.github.sync.util.UriEncodingHelper
import com.github.sync.{AsyncTestHelper, FileTestHelper, SourceFileProvider, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock._
import org.mockito.Mockito
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

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
  import OneDriveStubbingSupport._
  import WireMockSupport._

  /**
    * Creates a test OneDrive configuration.
    *
    * @return the test configuration
    */
  private def createConfig(): OneDriveConfig =
    createOneDriveConfig(SyncPath)

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

  /**
    * Checks a file upload operation with different parameters.
    *
    * @param uploadChunkSize the upload chunk size
    * @param groupSize       the group size in the stream
    * @param action          the action
    */
  private def checkUpload(uploadChunkSize: Int, groupSize: Int, action: SyncAction): Unit = {
    val Content = FileTestHelper.TestData * 8
    val SourceUri = "/source/file/data.txt"
    val FileUri = "/upload/file/data.txt"
    val UploadUri = "/1234567890/foo/bar.xyz"
    val ModifiedTime = "2019-11-09T19:14:35Z"
    val file = FsFile("/some/file.txt", 3, Instant.parse(ModifiedTime), 42)
    val op = SyncOperation(file, action, 0, SourceUri, FileUri)
    val config = createConfig().copy(uploadChunkSize = uploadChunkSize)
    val UploadSessionRequest =
      s"""
         |{
         |  "item": {
         |    "@microsoft.graph.conflictBehavior": "replace",
         |    "fileSystemInfo": {
         |      "lastModifiedDateTime": "$ModifiedTime"
         |    }
         |  }
         |}
         |""".stripMargin
    val UploadSessionResponse =
      s"""
         |{
         |  "uploadUrl": "${serverUri(UploadUri)}",
         |  "expirationDateTime": "2015-01-29T09:21:55.523Z"
         |}
         |""".stripMargin
    val fileProvider = mock[SourceFileProvider]
    val fileSource = Source(Content.grouped(groupSize).toList).map(ByteString(_))
    Mockito.when(fileProvider.fileSource(SourceUri)).thenReturn(Future.successful(fileSource))
    Mockito.when(fileProvider.fileSize(file.size)).thenReturn(Content.length)
    stubSuccess(NoAuthFunc)
    stubFor(post(urlPathEqualTo(path(mapElementUri(config, FileUri, prefix = PrefixItems)) + ":/createUploadSession"))
      .withHeader("Content-Type", equalTo("application/json"))
      .withHeader("Accept", equalTo("application/json"))
      .withRequestBody(equalToJson(UploadSessionRequest))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withHeader("Content-Type", ContentType)
        .withBody(UploadSessionResponse)))
    val syncRes = runSync(List(op), config, fileProvider)
    syncRes should contain only op

    import collection.JavaConverters._
    getAllServeEvents.asScala foreach { event =>
      println(event.getRequest)
    }
    Content.grouped(uploadChunkSize)
      .zipWithIndex
      .foreach { t =>
        val startRange = t._2 * uploadChunkSize
        val endRange = math.min(startRange + uploadChunkSize - 1, Content.length - 1)
        val rangeHeader = s"bytes $startRange-$endRange/${Content.length}"
        println("Verify request for " + rangeHeader)
        verify(putRequestedFor(urlPathEqualTo(UploadUri))
          .withHeader("Content-Range", equalTo(rangeHeader))
          .withRequestBody(equalTo(t._1)))
      }
  }

  it should "upload a new file" in {
    checkUpload(16384, 1024, ActionCreate)
  }

  it should "upload a modified file" in {
    checkUpload(16384, 2048, ActionOverride)
  }

  it should "upload a file with multiple chunks if the group size fits into the chunk size" in {
    checkUpload(2048, 1024, ActionCreate)
  }

  it should "upload a file with multiple chunks if the group size does not fit into the chunk size" in {
    checkUpload(3333, 1024, ActionOverride)
  }
}
