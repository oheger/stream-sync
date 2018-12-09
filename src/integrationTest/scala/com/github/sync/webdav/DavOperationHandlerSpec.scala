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

import java.nio.file.Paths
import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.testkit.TestKit
import akka.util.ByteString
import com.github.sync.WireMockSupport.{Password, PriorityDefault, PrioritySpecific, UserId}
import com.github.sync._
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.UrlPathPattern
import org.mockito.Mockito
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}

import scala.concurrent.Future

object DavOperationHandlerSpec {
  /** The root path for sync operations. */
  private val RootPath = "/target/sync/test"

  /**
    * Creates a test folder instance.
    *
    * @param name the folder name
    * @return the test folder instance
    */
  private def createFolder(name: String): FsFolder =
    FsFolder(name, 1)

  /**
    * Creates a test file instance.
    *
    * @param parent   the folder the file is located in
    * @param name     the file name
    * @param modified the modified date for the file
    * @param size     optional file size
    * @return the test file instance
    */
  private def createFile(parent: FsFolder, name: String,
                         modified: Instant = Instant.now(), size: Option[Long] = None): FsFile = {
    val uri = parent.relativeUri + name
    FsFile(uri, parent.level + 1, modified, size getOrElse uri.length)
  }
}

/**
  * Test class for ''DavOperationHandler''.
  */
class DavOperationHandlerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with WireMockSupport
  with AsyncTestHelper
  with MockitoSugar with FileTestHelper {
  def this() = this(ActorSystem("DavOperationHandlerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  override protected def afterEach(): Unit = {
    futureResult(Http().shutdownAllConnectionPools())
    super.afterEach()
  }

  import DavOperationHandlerSpec._

  /**
    * Creates the configuration object for the simulated WebDav server.
    *
    * @return the test WebDav server configuration
    */
  private def createDavConfig(): DavConfig =
    DavConfig(serverUri(RootPath), UserId, Password, DavConfig.DefaultModifiedProperty, None,
      deleteBeforeOverride = false)

  /**
    * Convenience function to define the URI of a stub or verification based on
    * an element.
    *
    * @param elem the element
    * @return the pattern for URI matching
    */
  private def elemUri(elem: FsElement): UrlPathPattern =
    urlPathEqualTo(RootPath + elem.relativeUri)

  /**
    * Creates a mock file provider that returns the given content for the file
    * specified.
    *
    * @param file        the file
    * @param fileContent the content for this file
    * @return
    */
  private def createFileProvider(file: FsFile, fileContent: Array[Byte]): SourceFileProvider = {
    val fileProvider = mock[SourceFileProvider]
    Mockito.when(fileProvider.fileSource(file))
      .thenReturn(Future.successful(Source.single(ByteString(fileContent))))
    fileProvider
  }

  /**
    * Executes a stream with the given sync operations and returns the result.
    *
    * @param operations   the sync operations to be executed
    * @param fileProvider the source file provider
    * @return the resulting list of sync operations
    */
  private def runSync(operations: List[SyncOperation],
                      fileProvider: SourceFileProvider = mock[SourceFileProvider]):
  List[SyncOperation] = {
    val decider: Supervision.Decider = ex => {
      ex.printStackTrace()
      Supervision.Resume
    }
    implicit val mat: ActorMaterializer =
      ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))
    val source = Source(operations)
    val sink = Sink.fold[List[SyncOperation], SyncOperation](List.empty) { (lst, op) =>
      op :: lst
    }
    val futResult = source
      .via(DavOperationHandler.webDavProcessingFlow(createDavConfig(), fileProvider))
      .runWith(sink)
    futureResult(futResult).reverse
  }

  "DavOperationHandler" should "delete elements" in {
    val folder = createFolder("/someData")
    val file = createFile(folder, "/cool data.txt")
    val operations = List(SyncOperation(folder, ActionRemove, 1),
      SyncOperation(file, ActionRemove, 2))
    stubSuccess()

    val processedOps = runSync(operations)
    processedOps should contain theSameElementsAs operations
    verify(deleteRequestedFor(elemUri(folder)))
    verify(deleteRequestedFor(urlPathEqualTo(RootPath + folder.relativeUri +
      "/cool%20data.txt")))
    getAllServeEvents should have size 2
  }

  it should "drop sync operations that fail" in {
    val FailureCount = 16
    val successFolder = createFolder("/success")
    val successOperation = SyncOperation(successFolder, ActionRemove, 1)
    val failedFolders = (1 to FailureCount) map (i => createFolder("/failed" + i))
    val operations = failedFolders.map(fld => SyncOperation(fld, ActionRemove, 1))
      .foldLeft(List(successOperation)) { (lst, op) => op :: lst }
    stubFor(authorized(any(anyUrl()).atPriority(PriorityDefault))
      .willReturn(aResponse().withStatus(StatusCodes.BadRequest.intValue)
        .withBody("<status>failed</status>")))
    stubFor(authorized(delete(elemUri(successFolder)).atPriority(PrioritySpecific))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))

    runSync(operations) should contain only successOperation
  }

  it should "create a folder" in {
    val folder = createFolder("/new-folder")
    val operations = List(SyncOperation(folder, ActionCreate, 1))
    stubFor(authorized(request("MKCOL", elemUri(folder)))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))

    runSync(operations) should contain theSameElementsAs operations
  }

  /**
    * Checks whether an action that requires a file upload is handled
    * correctly.
    *
    * @param action the action to be handled
    */
  private def checkUploadFile(action: SyncAction): Unit = {
    val FileTime = Instant.parse("2018-10-14T15:02:50.00Z")
    val fileContent = FileTestHelper.testBytes()
    val file = createFile(createFolder("/upload"), "/upload.txt", FileTime,
      Some(fileContent.length))
    val pathPatchData = Paths.get(classOf[DavOperationHandlerSpec]
      .getResource("/patch_default_modify.xml").toURI)
    val expPatchContent = readDataFile(pathPatchData)
    val fileProvider = createFileProvider(file, fileContent)
    val operations = List(SyncOperation(file, action, 2))
    stubFor(put(elemUri(file))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    stubFor(request("PROPPATCH", elemUri(file))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))

    runSync(operations, fileProvider) should contain theSameElementsAs operations
    verify(putRequestedFor(elemUri(file))
      .withHeader("Content-Length", equalTo(fileContent.length.toString))
      .withRequestBody(binaryEqualTo(fileContent)))
    verify(anyRequestedFor(elemUri(file))
      .withHeader("Content-Type", equalTo("text/xml; charset=UTF-8"))
      .withRequestBody(equalTo(expPatchContent)))
    getAllServeEvents should have size 2
  }

  it should "handle a file create action" in {
    checkUploadFile(ActionCreate)
  }

  it should "handle a file override action" in {
    checkUploadFile(ActionOverride)
  }

  it should "not send a PROPPATCH request for a failed upload operation" in {
    val file = createFile(createFolder("/failedUpload"), "/failure.txt")
    val fileProvider = createFileProvider(file, file.relativeUri.getBytes)
    val operations = List(SyncOperation(file, ActionOverride, 2))
    stubFor(put(elemUri(file))
      .willReturn(aResponse().withStatus(StatusCodes.InternalServerError.intValue)
        .withBody("Crashed!")))

    runSync(operations, fileProvider) should have size 0
    getAllServeEvents should have size 1
  }

  it should "filter out upload operations with a failed PROPPATCH request" in {
    val file = createFile(createFolder("/failedPatch"), "/patchIt.diff")
    val fileProvider = createFileProvider(file, file.relativeUri.getBytes)
    val operations = List(SyncOperation(file, ActionCreate, 2))
    stubSuccess()
    stubFor(request("PROPPATCH", elemUri(file))
      .willReturn(aResponse().withStatus(StatusCodes.BadRequest.intValue)
        .withBody("Rejected!")))

    runSync(operations, fileProvider) should have size 0
  }

  it should "handle an invalid sync operation" in {
    val opSuccess = SyncOperation(createFolder("/valid"), ActionRemove, 1)
    val operations = List(SyncOperation(createFolder("/invalid"), ActionOverride, 1),
      opSuccess)
    stubSuccess()

    runSync(operations) should contain only opSuccess
  }
}
