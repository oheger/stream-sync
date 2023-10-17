/*
 * Copyright 2018-2023 The Developers Team.
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

package com.github.sync.stream

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder}
import com.github.sync.protocol.SyncProtocol
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

object ProtocolElementSourceSpec:
  /**
    * Helper function to create a file element.
    *
    * @param uri   the URI
    * @param level the level
    * @return the file
    */
  private def createFile(uri: String, level: Int = 0): FsFile =
    FsFile(null, uri, level, Instant.now(), uri.length * 100)

  /**
    * Helper function to create a file element that is a child of the given
    * folder.
    *
    * @param parent the parent folder
    * @param name   the file name; the URI is derived from this
    * @return the file
    */
  private def createFile(parent: FsFolder, name: String): FsFile =
    createFile(parent.relativeUri + "/" + name, parent.level + 1)

  /**
    * Helper function to create a folder element.
    *
    * @param id    the ID of the folder
    * @param uri   the URI
    * @param level the level
    * @return the folder
    */
  private def createFolder(id: String, uri: String, level: Int = 0): FsFolder =
    FsFolder(id, uri, level)

  /**
    * Helper function to create folder element that is a child of the given
    * folder.
    *
    * @param parent the parent folder
    * @param id     the ID of the folder
    * @param name   the folder name; the URI is derived from this
    * @return the folder
    */
  private def createFolder(parent: FsFolder, id: String, name: String): FsFolder =
    createFolder(id, parent.relativeUri + "/" + name, parent.level + 1)

/**
  * Test class for ''ProtocolElementSource''.
  */
class ProtocolElementSourceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper:
  def this() = this(ActorSystem("ProtocolElementSourceSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  import ProtocolElementSourceSpec._

  "ProtocolElementSource" should "handle an empty source structure" in {
    val helper = new SourceTestHelper

    helper.initRootFolder()
      .runSource() should have size 0
  }

  it should "iterate over files in the root folder" in {
    val files = List(createFile("/file1.txt"), createFile("/file2.txt"),
      createFile("/file3.txt"))
    val helper = new SourceTestHelper

    helper.initRootFolder(files: _*)
      .runSource() should be(files)
  }

  it should "iterate over multiple sub folders" in {
    val rootFile = createFile("/root.dat")
    val folder1 = createFolder("f1", "/folder1")
    val folder2 = createFolder("f2", "/folder2")
    val folder3 = createFolder(folder1, "f3", "folder3")
    val folder1File = createFile(folder1, "data1.txt")
    val folder2File = createFile(folder2, "data2.txt")
    val folder3File = createFile(folder3, "data3.txt")
    val expResult = List(folder1, folder2, rootFile, folder1File, folder3, folder3File, folder2File)
    val helper = new SourceTestHelper

    helper.initRootFolder(folder1, folder2, rootFile)
      .initFolder(folder1, folder1File, folder3)
      .initFolder(folder2, folder2File)
      .initFolder(folder3, folder3File)
      .runSource() should be(expResult)
  }

  it should "handle empty folders during the iteration" in {
    val folder = createFolder("f1", "/folder")
    val emptyFolder = createFolder("fEmpty", "/folder-empty")
    val file1 = createFile(folder, "test.dat")
    val emptySubFolder = createFolder(folder, "fEmpty2", "sub-empty")
    val expResult = List(folder, emptyFolder, emptySubFolder, file1)
    val helper = new SourceTestHelper

    helper.initRootFolder(folder, emptyFolder)
      .initFolder(folder, emptySubFolder, file1)
      .initFolder(emptyFolder)
      .initFolder(emptySubFolder)
      .runSource() should be(expResult)
  }

  it should "sort the elements within a folder alphabetically" in {
    val folder1 = createFolder("f1", "/temp")
    val folder2 = createFolder("f2", "/home")
    val file1 = createFile("/data.txt")
    val file2 = createFile("/data.doc")
    val file3 = createFile("/calc.xls")
    val file4 = createFile("/user.dat")
    val file5 = createFile("/alpha.txt")
    val file6 = createFile("/beta.txt")
    val expResult = List(file3, file2, file1, folder2, folder1, file4, file6, file5)
    val helper = new SourceTestHelper

    helper.initRootFolder(folder1, folder2, file1, file2, file3, file4)
      .initFolder(folder1, file5)
      .initFolder(folder2, file6)
      .runSource() should be(expResult)
  }

  it should "handle a failure from the protocol when querying a folder" in {
    val folder = createFolder("fError", "/error")
    val exception = new IOException("Test exception: Could not load folder.")
    val helper = new SourceTestHelper

    helper.initRootFolder(folder)
      .initFolderFailure(folder, exception)
    expectFailedFuture[IOException](helper.executeStream()) should be(exception)
  }

  /**
    * A test helper class managing a source to be tested and its dependencies.
    */
  private class SourceTestHelper:
    /** Mock for the sync protocol. */
    private val protocol = mock[SyncProtocol]

    /**
      * Prepares the mock for the protocol to return the content of the root
      * folder. Result is a successful future with a list of the given
      * elements.
      *
      * @param elements the elements in the root folder
      * @return this test helper
      */
    def initRootFolder(elements: FsElement*): SourceTestHelper =
      when(protocol.readRootFolder()).thenReturn(Future.successful(elements.toList))
      this

    /**
      * Prepares the mock for the protocol to return the content of a specific
      * folder. If the given folder is queried, result is a successful future
      * with a list of the provided elements.
      *
      * @param folder   the folder to define
      * @param elements the elements in this folder
      * @return this test helper
      */
    def initFolder(folder: FsFolder, elements: FsElement*): SourceTestHelper =
      prepareFolderRequest(folder, Future.successful(elements.toList))

    /**
      * Prepares the mock for the protocol to return a failed future when it is
      * queried for the given folder.
      *
      * @param folder    the folder in question
      * @param exception the exception to fail the future with
      * @return this test helper
      */
    def initFolderFailure(folder: FsFolder, exception: Throwable): SourceTestHelper =
      prepareFolderRequest(folder, Future.failed(exception))

    /**
      * Executes a stream with the test source and returns the resulting
      * future.
      *
      * @return the future result of the stream execution
      */
    def executeStream(): Future[List[FsElement]] =
      implicit val ec: ExecutionContext = system.dispatcher
      val source = new ProtocolElementSource("test", protocol)
      val sink = Sink.fold[List[FsElement], FsElement](Nil)((lst, e) => e :: lst)
      Source.fromGraph(source).runWith(sink)

    /**
      * Runs a stream with the test source and returns the result.
      *
      * @return the elements emitted by the test source
      */
    def runSource(): List[FsElement] =
      futureResult(executeStream()).reverse

    /**
      * Prepares the mock for the protocol to answer a request for the content
      * of a folder.
      *
      * @param folder the folder in question
      * @param result the result to return for this request
      * @return this test helper
      */
    private def prepareFolderRequest(folder: FsFolder, result: Future[List[FsElement]]): SourceTestHelper =
      when(protocol.readFolder(folder.id, folder.relativeUri + "/", folder.level + 1)).thenReturn(result)
      this
