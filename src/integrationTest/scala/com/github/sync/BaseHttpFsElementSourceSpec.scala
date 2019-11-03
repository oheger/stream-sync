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

package com.github.sync

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Graph, SourceShape}
import akka.testkit.TestKit
import com.github.sync.SyncTypes._
import com.github.sync.impl.ElementSource
import com.github.sync.util.UriEncodingHelper
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

object BaseHttpFsElementSourceSpec {
  /** The prefix for folder names. */
  val FolderName = "folder"

  /** Regular expression to parse the index from a folder name. */
  val RegFolderIndex: Regex =
    """.*folder\s\((\d+)\)""".r

  /** Reference date to calculate modified dates for files. */
  val RefDate: Instant = Instant.parse("2018-09-19T20:10:00.000Z")

  /** The root path for the sync process on the WebDav server. */
  val RootPath = "/test%20data"

  /** Element for the root folder. */
  val RootFolder = FsFolder("/", -1)

  /**
    * A sequence with test elements that should be generated by the test source
    * when parsing the test responses from the mock server.
    */
  val ExpectedElements: List[FsElement] = createExpectedElements()

  /**
    * Generates the name of a test file based on its index.
    *
    * @param idx the index
    * @return the name of the test file with this index
    */
  def fileName(idx: Int): String = s"file ($idx).mp3"

  /**
    * Generates the name of a child element of a folder.
    *
    * @param parent the parent folder
    * @param name   the name of the child element
    * @return the resulting relative URI for this child element
    */
  def childName(parent: FsFolder, name: String): String = {
    val nextName = if (parent.relativeUri.endsWith("/")) name else "/" + name
    parent.relativeUri + nextName
  }

  /**
    * Generates the name of a folder based on the given index.
    *
    * @param idx the index of the test folder
    * @return the name of this folder
    */
  def folderName(idx: Int): String = FolderName + s" ($idx)"

  /**
    * Generates an element representing a sub folder of the given folder.
    *
    * @param parent the parent folder
    * @param idx    the index of the sub folder
    * @return the resulting element for the sub folder
    */
  def createSubFolder(parent: FsFolder, idx: Int): FsFolder =
    FsFolder(childName(parent, folderName(idx)), parent.level + 1)

  /**
    * Generates the encoded URI for a folder. Requests to the server for this
    * folder must use this URI while the relative folder URI is not encoded.
    *
    * @param relUri the relative element URI of the folder
    * @return the encoded folder URI
    */
  def encodedFolderUri(relUri: String): String = {
    val components = relUri split "/"
    components.map(UriEncodingHelper.encode)
      .mkString("/")
  }

  /**
    * Generates the name of a file defining the content of a folder based on
    * the folder's relative URI.
    *
    * @param relativeUri the relative URI of the folder
    * @param format      the format to generate the file extension
    * @param suffix      the suffix to append to the file name
    * @return
    */
  def folderFileName(relativeUri: String, format: String, suffix: String): String =
    relativeUri match {
      case RegFolderIndex(idx) =>
        FolderName + idx + suffix + format
    }

  /**
    * Calculates the last modified time of a test file.
    *
    * @param idx the index of the test file
    * @return the last modified time of this file
    */
  def fileModifiedDate(idx: Int): Instant =
    RefDate.plus(Duration.of(idx - 1, ChronoUnit.MINUTES))

  /**
    * Generates an element representing a file in a folder.
    *
    * @param parent the parent folder
    * @param idx    the index of the test file
    * @return the resulting element for the test file
    */
  def createFile(parent: FsFolder, idx: Int): FsFile =
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
  * An abstract base class for tests of element sources.
  *
  * The class and its companion object define some helper functions for
  * creating test data and running streams that iterate over HTTP-based element
  * sources.
  *
  * @param testSystem the test actor system
  */
abstract class BaseHttpFsElementSourceSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with FlatSpecLike with BeforeAndAfterAll with Matchers with AsyncTestHelper with WireMockSupport {

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import BaseHttpFsElementSourceSpec._

  /** The object to materialize streams in implicit scope. */
  protected implicit val mat: ActorMaterializer = ActorMaterializer()

  /**
    * Runs the given test source and returns its future result.
    *
    * @param source the source to be run
    * @return the ''Future'' with the result of the source
    */
  protected def runSource(source: Source[FsElement, Any]): Future[Seq[FsElement]] = {
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
  protected def executeStream(source: Source[FsElement, Any]): Seq[FsElement] =
    futureResult(runSource(source)).reverse

  /**
    * Runs a stream with the given test source and verifies that the result
    * is the expected sequence of test elements.
    *
    * @param source the source to be run
    */
  protected def runAndVerifySource(source: Source[FsElement, Any]): Unit = {
    executeStream(source) should contain theSameElementsInOrderAs ExpectedElements
  }

  /**
    * An implementation of the ElementSourceFactory trait to create test
    * sources.
    */
  protected class SourceFactoryImpl extends ElementSourceFactory {
    /** Holds a reference to the latest source that was created. */
    val refSource = new AtomicReference[ElementSource[_, _, _]]()

    override def createElementSource[F, S](initState: S, initFolder: SyncFolderData[F],
                                           optCompletionFunc: Option[CompletionFunc[S]])
                                          (iterateFunc: IterateFunc[F, S]):
    Graph[SourceShape[FsElement], NotUsed] = {
      implicit val ec: ExecutionContext = system.dispatcher
      val source = new ElementSource[F, S, Unit](initState, initFolder, optCompletionFunc)(iterateFunc)
      refSource set source
      source
    }
  }

}
