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
import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import com.github.sync._
import com.github.sync.util.UriEncodingHelper
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.xml.sax.SAXException

import scala.concurrent.Future
import scala.xml.SAXParseException

object DavFsElementSourceSpec {
  /** The prefix for folder names. */
  private val FolderName = "folder"

  /** Regular expression to parse the index from a folder name. */
  private val RegFolderIndex =
    """.*folder\s\((\d+)\)""".r

  /** Reference date to calculate modified dates for files. */
  private val RefDate = Instant.parse("2018-09-19T20:10:00.000Z")

  /** The root path for the sync process on the WebDav server. */
  private val RootPath = "/test%20data"

  /** Element for the root folder. */
  private val RootFolder = FsFolder("/", -1)

  /**
    * A sequence with test elements that should be generated by the test source
    * when parsing the test responses from the mock server.
    */
  private val ExpectedElements = createExpectedElements()

  /**
    * Generates the name of a test file based on its index.
    *
    * @param idx the index
    * @return the name of the test file with this index
    */
  private def fileName(idx: Int): String = s"file ($idx).mp3"

  /**
    * Generates the name of a child element of a folder.
    *
    * @param parent the parent folder
    * @param name   the name of the child element
    * @return the resulting relative URI for this child element
    */
  private def childName(parent: FsFolder, name: String): String = {
    val nextName = if (parent.relativeUri.endsWith("/")) name else "/" + name
    parent.relativeUri + nextName
  }

  /**
    * Generates the name of a folder based on the given index.
    *
    * @param idx the index of the test folder
    * @return the name of this folder
    */
  private def folderName(idx: Int): String = FolderName + s" ($idx)"

  /**
    * Generates an element representing a sub folder of the given folder.
    *
    * @param parent the parent folder
    * @param idx    the index of the sub folder
    * @return the resulting element for the sub folder
    */
  private def createSubFolder(parent: FsFolder, idx: Int): FsFolder =
    FsFolder(childName(parent, folderName(idx)), parent.level + 1)

  /**
    * Generates the encoded URI for a folder. Requests to the server for this
    * folder must use this URI while the relative folder URI is not encoded.
    *
    * @param relUri the relative element URI of the folder
    * @return the encoded folder URI
    */
  private def encodedFolderUri(relUri: String): String = {
    val components = relUri split "/"
    components.map(UriEncodingHelper.encode)
      .mkString("/")
  }

  /**
    * Generates the name of a file defining the content of a folder based on
    * the folder's relative URI.
    *
    * @param relativeUri the relative URI of the folder
    * @param suffix      the suffix to append to the file name
    * @return
    */
  private def folderFileName(relativeUri: String, suffix: String): String =
    relativeUri match {
      case RegFolderIndex(idx) =>
        FolderName + idx + suffix + ".xml"
    }

  /**
    * Calculates the last modified time of a test file.
    *
    * @param idx the index of the test file
    * @return the last modified time of this file
    */
  private def fileModifiedDate(idx: Int): Instant =
    RefDate.plus(Duration.of(idx - 1, ChronoUnit.MINUTES))

  /**
    * Generates an element representing a file in a folder.
    *
    * @param parent the parent folder
    * @param idx    the index of the test file
    * @return the resulting element for the test file
    */
  private def createFile(parent: FsFolder, idx: Int): FsFile =
    FsFile(childName(parent, fileName(idx)), parent.level + 1,
      fileModifiedDate(idx), idx * 100)

  /**
    * Generates a list of elements that should be produced by the source under
    * test.
    *
    * @return the expected sequence of elements
    */
  private def createExpectedElements(): List[FsElement] = {
    val folder1 = createSubFolder(RootFolder, 1)
    val folder2 = createSubFolder(RootFolder, 2)
    val folder3 = createSubFolder(folder2, 3)
    val file1 = createFile(folder1, 1)
    val file2 = createFile(folder1, 2)
    val file3 = createFile(folder1, 3)
    val file4 = createFile(folder2, 4)
    val file5 = createFile(folder3, 5)
    List(folder1, folder2, file1, file2, file3, file4, folder3, file5)
  }
}

/**
  * Integration test class for ''DavFsElementSource''.
  */
class DavFsElementSourceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with Matchers with AsyncTestHelper with WireMockSupport {
  def this() = this(ActorSystem("DavFsElementSourceSpec"))

  import WireMockSupport._
  import DavFsElementSourceSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Adds stubbing declarations for all test folders. Each folder is mapped to
    * an XML file with its WebDav properties. With the ''suffix'' parameter a
    * specific set of properties files can be selected.
    *
    * @param suffix the suffix of the files to represent the test folders
    */
  private def stubTestFolders(suffix: String): Unit = {
    stubFolderRequest(RootPath, "root" + suffix + ".xml")
    ExpectedElements foreach {
      case FsFolder(relativeUri, _) =>
        val fileName = folderFileName(relativeUri, suffix)
        val httpUri = Uri(RootPath + encodedFolderUri(relativeUri))
        stubFolderRequest(httpUri.toString(), fileName)
      case _ => // ignore other elements
    }
  }

  /**
    * Runs the given test source and returns its future result.
    *
    * @param source the source to be run
    * @return the ''Future'' with the result of the source
    */
  private def runSource(source: Source[FsElement, Any]): Future[Seq[FsElement]] = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val sink = Sink.fold[List[FsElement], FsElement](List.empty) { (lst, e) => e :: lst }
    source.runWith(sink)
  }

  /**
    * Runs a stream with the given test source and returns the elements that
    * are generated.
    *
    * @param source the source to be run
    * @return the resulting elements
    */
  private def executeStream(source: Source[FsElement, Any]): Seq[FsElement] =
    futureResult(runSource(source)).reverse

  /**
    * Runs a stream with the given test source and verifies that the result
    * is the expected sequence of test elements.
    *
    * @param source the source to be run
    */
  private def runAndVerifySource(source: Source[FsElement, Any]): Unit = {
    executeStream(source) should contain theSameElementsInOrderAs ExpectedElements
  }

  /**
    * Creates a test WebDav source with the settings to access the mock WebDav
    * server.
    *
    * @param modifiedProperty the property for the modified time
    * @return the test source
    */
  private def createTestSource(modifiedProperty: String = DavConfig.DefaultModifiedProperty):
  Source[FsElement, Any] =
    Source.fromGraph(createTestSourceShape(modifiedProperty))

  /**
    * Creates a ''DavFsElementSource'' instance with default settings to access
    * the mock WebDav server.
    *
    * @param modifiedProperty the property for the modified time
    * @return the test source shape
    */
  private def createTestSourceShape(modifiedProperty: String): DavFsElementSource = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val config = DavConfig(serverUri(RootPath), UserId, Password, modifiedProperty, None,
      deleteBeforeOverride = false,
      modifiedProperties = List(modifiedProperty, DavConfig.DefaultModifiedProperty))
    new DavFsElementSource(config)
  }

  "A DavFsElementSource" should "iterate over a WebDav structure" in {
    stubTestFolders("")

    runAndVerifySource(createTestSource())
  }

  it should "support a custom modified time property" in {
    stubTestFolders("_full")

    runAndVerifySource(createTestSource("Win32LastModifiedTime"))
  }

  it should "handle an absent modified time property" in {
    stubTestFolders("")

    runAndVerifySource(createTestSource("Win32LastModifiedTime"))
  }

  it should "fail processing when receiving a non XML response" in {
    stubFolderRequest(RootPath, "invalidResponse.txt")

    expectFailedFuture[SAXParseException](runSource(createTestSource()))
  }

  it should "shutdown the request queue when done" in {
    stubFolderRequest(RootPath, "folder3.xml")
    val testRequest = HttpRequest(uri = RootFolder.relativeUri)
    val source = createTestSourceShape(DavConfig.DefaultModifiedProperty)
    futureResult(runSource(Source.fromGraph(source)))

    expectFailedFuture[RuntimeException](source.requestQueue.queueRequest(testRequest))
  }

  it should "evaluate the status code from a response" in {
    stubFor(request("PROPFIND", urlPathEqualTo(RootPath))
      .willReturn(aResponse()
        .withStatus(401)
        .withBodyFile("folder3.xml")))

    val ex = expectFailedFuture[IOException](runSource(createTestSource()))
    ex.getMessage should include(RootFolder.relativeUri)
  }

  it should "fail processing if no modified time can be obtained" in {
    stubFolderRequest(RootPath, "folder_no_timestamp.xml")

    expectFailedFuture[SAXException](runSource(createTestSource()))
  }
}
