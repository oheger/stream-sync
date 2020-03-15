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

package com.github.sync.webdav

import java.nio.file.Paths
import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.testkit.TestKit
import akka.util.{ByteString, Timeout}
import com.github.sync.SyncTypes._
import com.github.sync.WireMockSupport.{Password, PriorityDefault, PrioritySpecific, UserId}
import com.github.sync._
import com.github.sync.crypt.Secret
import com.github.sync.http.{BasicAuthConfig, HttpBasicAuthActor, HttpRequestActor}
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.UrlPathPattern
import org.mockito.Mockito
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future
import scala.concurrent.duration._

object DavOperationHandlerSpec {
  /** The root path for sync operations. */
  private val RootPath = "/target/sync/test"

  /** A suffix indicating the original URI in the source structure. */
  private val SrcSuffix = "_src"

  /** A suffix indicating the original URI in the destination structure. */
  private val DstSuffix = "_dst"

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

  /**
    * Generates the original of an element in a given structure.
    *
    * @param elem   the element
    * @param suffix the suffix indicating the structure
    * @return the original URI
    */
  private def orgUri(elem: FsElement, suffix: String): String = elem.originalUri + suffix
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
  import WireMockSupport.BasicAuthFunc

  /**
    * Creates the configuration object for the simulated WebDav server.
    *
    * @return the test WebDav server configuration
    */
  private def createDavConfig(): DavConfig =
    DavConfig(serverUri(RootPath), DavConfig.DefaultModifiedProperty, None,
      deleteBeforeOverride = false, modifiedProperties = List(DavConfig.DefaultModifiedProperty),
      Timeout(10.seconds), optBasicAuthConfig = Some(BasicAuthConfig(UserId, Secret(Password))),
      optOAuthConfig = None)

  /**
    * Convenience function to define the URI of a stub or verification based on
    * an element and a suffix.
    *
    * @param elem          the element
    * @param suffix        the suffix indicating the original URI
    * @param trailingSlash flag whether the URI should end on a slash
    * @return the pattern for URI matching
    */
  private def elemUri(elem: FsElement, suffix: String, trailingSlash: Boolean = false): UrlPathPattern = {
    val path = RootPath + elem.relativeUri + suffix
    val finalPath = if (trailingSlash) path + "/" else path
    urlPathEqualTo(finalPath)
  }

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
    Mockito.when(fileProvider.fileSource(file.relativeUri + SrcSuffix))
      .thenReturn(Future.successful(Source.single(ByteString(fileContent))))
    Mockito.when(fileProvider.fileSize(file.size)).thenReturn(file.size)
    fileProvider
  }

  /**
    * Executes a stream with the given sync operations and returns the result.
    *
    * @param operations   the sync operations to be executed
    * @param fileProvider the source file provider
    * @param config       the DAV configuration
    * @return the resulting list of sync operations
    */
  private def runSync(operations: List[SyncOperation],
                      fileProvider: SourceFileProvider = mock[SourceFileProvider],
                      config: DavConfig = createDavConfig()):
  List[SyncOperation] = {
    val decider: Supervision.Decider = ex => {
      ex.printStackTrace()
      Supervision.Resume
    }
    implicit val mat: ActorMaterializer =
      ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))
    val httpActor = system.actorOf(HttpRequestActor(config.rootUri))
    val requestActor = system.actorOf(HttpBasicAuthActor(httpActor, config.optBasicAuthConfig.get))
    val source = Source(operations)
    val sink = Sink.fold[List[SyncOperation], SyncOperation](List.empty) { (lst, op) =>
      op :: lst
    }
    val futResult = source
      .via(DavOperationHandler(config, fileProvider, requestActor))
      .runWith(sink)
    futureResult(futResult).reverse
  }

  "DavOperationHandler" should "delete files" in {
    val folder = createFolder("/someData")
    val file = createFile(folder, "/cool data.txt")
    val operations = List(SyncOperation(file, ActionRemove, 2, null, orgUri(file, DstSuffix)))
    stubSuccess()

    val processedOps = runSync(operations)
    processedOps should contain theSameElementsAs operations
    verify(deleteRequestedFor(urlPathEqualTo(RootPath + folder.relativeUri +
      "/cool%20data.txt" + DstSuffix)))
    getAllServeEvents should have size 1
  }

  it should "delete folders" in {
    val folder = createFolder("/someData")
    val operations = List(SyncOperation(folder, ActionRemove, 1, null, orgUri(folder, DstSuffix)))
    stubSuccess()

    val processedOps = runSync(operations)
    processedOps should contain theSameElementsAs operations
    verify(deleteRequestedFor(elemUri(folder, DstSuffix, trailingSlash = true)))
    getAllServeEvents should have size 1
  }

  it should "drop sync operations that fail" in {
    val FailureCount = 16
    val successFolder = createFolder("/success")
    val successOperation = SyncOperation(successFolder, ActionRemove, 1, null, orgUri(successFolder, DstSuffix))
    val failedFolders = (1 to FailureCount) map (i => createFolder("/failed" + i))
    val operations = failedFolders.map(fld => SyncOperation(fld, ActionRemove, 1, null, orgUri(fld, DstSuffix)))
      .foldLeft(List(successOperation)) { (lst, op) => op :: lst }
    stubFor(BasicAuthFunc(any(anyUrl()).atPriority(PriorityDefault))
      .willReturn(aResponse().withStatus(StatusCodes.BadRequest.intValue)
        .withBody("<status>failed</status>")))
    stubFor(BasicAuthFunc(delete(elemUri(successFolder, DstSuffix, trailingSlash = true))
      .atPriority(PrioritySpecific))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))

    runSync(operations) should contain only successOperation
  }

  it should "create a folder" in {
    val folder = createFolder("/new-folder")
    val operations = List(SyncOperation(folder, ActionCreate, 1, null, orgUri(folder, DstSuffix)))
    stubFor(BasicAuthFunc(request("MKCOL", elemUri(folder, DstSuffix)))
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
    val operations = List(SyncOperation(file, action, 2, orgUri(file, SrcSuffix), orgUri(file, DstSuffix)))
    stubFor(put(elemUri(file, DstSuffix))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    stubFor(request("PROPPATCH", elemUri(file, DstSuffix))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))

    runSync(operations, fileProvider) should contain theSameElementsAs operations
    verify(putRequestedFor(elemUri(file, DstSuffix))
      .withHeader("Content-Length", equalTo(fileContent.length.toString))
      .withRequestBody(binaryEqualTo(fileContent)))
    verify(anyRequestedFor(elemUri(file, DstSuffix))
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

  it should "generate the content length header from the file source provider" in {
    val FileTime = Instant.parse("2018-10-14T15:02:50.00Z")
    val fileContent = FileTestHelper.testBytes()
    val file = createFile(createFolder("/upload"), "/upload.txt", FileTime,
      Some(fileContent.length))
    val fileProvider = createFileProvider(file, fileContent)
    // setting a wrong content length will cause the request to fail
    Mockito.when(fileProvider.fileSize(file.size)).thenReturn(42)
    val operations = List(SyncOperation(file, ActionCreate, 2, orgUri(file, SrcSuffix), orgUri(file, DstSuffix)))
    stubFor(put(elemUri(file, DstSuffix))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))

    runSync(operations, fileProvider) should have size 0
  }

  it should "handle a file override action if deleteBeforeOverride is set" in {
    val fileContent = FileTestHelper.testBytes()
    val file = createFile(createFolder("/upload"), "/upload.txt",
      Instant.now(), Some(fileContent.length))
    val fileProvider = createFileProvider(file, fileContent)
    val operations = List(SyncOperation(file, ActionOverride, 2, orgUri(file, SrcSuffix), orgUri(file, DstSuffix)))
    val config = createDavConfig().copy(deleteBeforeOverride = true)
    stubFor(delete(elemUri(file, DstSuffix))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    stubFor(put(elemUri(file, DstSuffix))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    stubFor(request("PROPPATCH", elemUri(file, DstSuffix))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))

    runSync(operations, fileProvider, config) should contain theSameElementsAs operations
    verify(deleteRequestedFor(elemUri(file, DstSuffix)))
  }

  it should "not send a PROPPATCH request for a failed upload operation" in {
    val file = createFile(createFolder("/failedUpload"), "/failure.txt")
    val fileProvider = createFileProvider(file, file.relativeUri.getBytes)
    val operations = List(SyncOperation(file, ActionOverride, 2, orgUri(file, SrcSuffix), orgUri(file, DstSuffix)))
    stubFor(put(elemUri(file, DstSuffix))
      .willReturn(aResponse().withStatus(StatusCodes.InternalServerError.intValue)
        .withBody("Crashed!")))

    runSync(operations, fileProvider) should have size 0
    getAllServeEvents should have size 1
  }

  it should "filter out upload operations with a failed PROPPATCH request" in {
    val file = createFile(createFolder("/failedPatch"), "/patchIt.diff")
    val fileProvider = createFileProvider(file, file.relativeUri.getBytes)
    val operations = List(SyncOperation(file, ActionCreate, 2, orgUri(file, SrcSuffix), orgUri(file, DstSuffix)))
    stubSuccess()
    stubFor(request("PROPPATCH", elemUri(file, DstSuffix))
      .willReturn(aResponse().withStatus(StatusCodes.BadRequest.intValue)
        .withBody("Rejected!")))

    runSync(operations, fileProvider) should have size 0
  }

  it should "handle a failed delete operation before an override" in {
    val file = createFile(createFolder("/failedDelete"), "/override.txt")
    val fileProvider = createFileProvider(file, file.relativeUri.getBytes)
    val operations = List(SyncOperation(file, ActionOverride, 2, orgUri(file, SrcSuffix), orgUri(file, DstSuffix)))
    val config = createDavConfig().copy(deleteBeforeOverride = true)
    stubFor(delete(elemUri(file, DstSuffix))
      .willReturn(aResponse().withStatus(StatusCodes.InternalServerError.intValue)
        .withBody("Not deleted!")))

    runSync(operations, fileProvider, config) should have size 0
    getAllServeEvents should have size 1
  }

  it should "handle an invalid sync operation" in {
    val opSuccess = SyncOperation(createFolder("/valid"), ActionRemove, 1, null, "/someUri")
    val operations = List(SyncOperation(createFolder("/invalid"), ActionOverride, 1, "/src", "/dst"),
      opSuccess)
    stubSuccess()

    runSync(operations) should contain only opSuccess
  }
}
