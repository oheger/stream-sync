/*
 * Copyright 2018-2025 The Developers Team.
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

package com.github.sync.protocol

import com.github.cloudfiles.core.http.factory.HttpRequestSenderConfig
import com.github.sync.FileTestHelper
import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder}
import com.github.sync.protocol.config.{FsStructureConfig, StructureCryptConfig}
import com.github.sync.protocol.local.LocalProtocolFactory
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.Timeout
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.*

object FileSystemSyncProtocolElementSourceSpec:
  /**
    * Determines the name of an element from its relative path. The name is the
    * last component in the URI.
    *
    * @param relativeUri the relative URI
    * @return the name of this element
    */
  private def extractName(relativeUri: String): String =
    val nameSeparator = relativeUri.lastIndexOf('/')
    relativeUri.substring(nameSeparator + 1)
end FileSystemSyncProtocolElementSourceSpec

/**
  * Test class for [[FileSystemSyncProtocol]] that tests the element source
  * returned by the class. This is done based on a local file system, because it
  * rather easy here to construct a test fixture.
  */
class FileSystemSyncProtocolElementSourceSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem)
  with AsyncFlatSpecLike with BeforeAndAfterAll with BeforeAndAfter with Matchers with FileTestHelper:
  def this() = this(classic.ActorSystem("FileSystemSyncProtocolElementSourceSpec"))

  given ActorSystem[?] = system.toTyped

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  after {
    tearDownTestFile()
  }

  import FileSystemSyncProtocolElementSourceSpec.*

  /**
    * Determines the path of an element based on its relative URI and an
    * optional parent folder. If no parent folder is specified, the root test
    * directory is used.
    *
    * @param relativeUri     the relative element URI
    * @param optParentFolder the optional parent folder
    * @return the resolved [[Path]]
    */
  private def pathFromParent(relativeUri: String, optParentFolder: Option[FsFolder] = None): Path =
    val fileName = extractName(relativeUri)
    val folderPath = optParentFolder.fold(testDirectory) { parentFolder =>
      Paths.get(parentFolder.id)
    }
    folderPath.resolve(fileName)

  /**
    * Creates a [[FsFile]] with the given properties together with the physical
    * file that matches these attributes.
    *
    * @param relativeUri     the relative URI
    * @param size            the size of the file
    * @param optParentFolder the optional parent folder (defaults to the root
    *                        test folder)
    * @param level           the level
    * @return the newly created [[FsElement]]
    */
  private def createFileElement(relativeUri: String,
                                time: Instant,
                                size: Int,
                                optParentFolder: Option[FsFolder] = None,
                                level: Int = 0): FsFile =
    val filePath = pathFromParent(relativeUri, optParentFolder)
    writeFileContent(filePath, FileTestHelper.TestData.substring(0, size))
    Files.setLastModifiedTime(filePath, FileTime.from(time))
    FsFile(filePath.toString, relativeUri, level, time, size)

  /**
    * Creates a [[FsFolder]] with the given properties and the underlying
    * physical folder.
    *
    * @param relativeUri     the relative URI
    * @param optParentFolder the optional parent folder (defaults to the root
    *                        test folder)
    * @param level           the level
    * @return the newly created [[FsFolder]]
    */
  private def createFolderElement(relativeUri: String,
                                  optParentFolder: Option[FsFolder] = None,
                                  level: Int = 0): FsFolder =
    val folder = Files.createDirectories(pathFromParent(relativeUri, optParentFolder))
    FsFolder(folder.toString, relativeUri, level)

  /**
    * Executes the test source on the current content of the test directory and
    * returns a [[Future]] with the list of encountered elements.
    *
    * @return a [[Future]] with the list of found elements
    */
  private def runSource(): Future[List[FsElement]] =
    val protocol = createProtocol()
    val futSource = protocol.elementSource
    val sink = Sink.fold[List[FsElement], FsElement](List.empty) { (lst, e) => e :: lst }
    for
      source <- futSource
      elements <- source.runWith(sink)
    yield
      elements.reverse

  /**
    * Returns a [[SyncProtocol]] to be used for testing. The returned instance
    * is configured with a local file system using the test directory as root
    * folder.
    *
    * @return the protocol for testing
    */
  private def createProtocol(): SyncProtocol =
    val factory = new LocalProtocolFactory(
      config = FsStructureConfig(None),
      httpSenderConfig = HttpRequestSenderConfig(),
      timeout = Timeout(10.seconds),
      spawner = system,
      ec = system.dispatcher
    )
    val cryptConfig = StructureCryptConfig(
      password = None,
      cryptNames = false,
      cryptCacheSize = 0
    )
    factory.createProtocol(testDirectory.toString, cryptConfig)

  "elementSource" should "handle an empty source structure" in :
    runSource().map { elements =>
      elements shouldBe empty
    }

  it should "iterate over files in the root folder" in :
    val refTime = Instant.parse("2025-03-16T15:38:31Z")
    val files = List(
      createFileElement("/file1.txt", refTime, 32),
      createFileElement("/file2.txt", refTime.plusSeconds(3), 64),
      createFileElement("/file3.txt", refTime.plusSeconds(7), 100)
    )

    runSource().map { elements =>
      elements should contain theSameElementsAs files
    }

  it should "iterate over multiple sub folders" in :
    val refTime = Instant.parse("2025-03-16T16:03:50Z")
    val rootFile = createFileElement("/root.dat", refTime, 50)
    val folder1 = createFolderElement("/folder1")
    val folder2 = createFolderElement("/folder2")
    val folder3 = createFolderElement("/folder1/folder3", Some(folder1), level = 1)
    val folder1File = createFileElement("/folder1/data1.txt", refTime.plusSeconds(10), 44, Some(folder1), 1)
    val folder2File = createFileElement("/folder2/data2.txt", refTime.plusSeconds(17), 88, Some(folder2), 1)
    val folder3File = createFileElement("/folder1/folder3/data3.txt", refTime.plusSeconds(11), 66, Some(folder3), 2)
    val expResult = List(folder1, folder2, rootFile, folder1File, folder3, folder3File, folder2File)

    runSource().map { elements =>
      elements should contain theSameElementsAs expResult
    }

  it should "handle empty folders during the iteration" in {
    val folder = createFolderElement("/folder")
    val emptyFolder = createFolderElement("/folder-empty")
    val file1 = createFileElement("/folder/test.dat", Instant.now(), 80, Some(folder), 1)
    val emptySubFolder = createFolderElement("/folder-empty/sub-empty", Some(emptyFolder), level = 1)
    val expResult = List(folder, emptyFolder, emptySubFolder, file1)

    runSource().map { elements =>
      elements should contain theSameElementsAs expResult
    }
  }

  it should "sort the elements within a folder alphabetically" in {
    val folder1 = createFolderElement("/temp")
    val folder2 = createFolderElement("/home")
    val file1 = createFileElement("/data.txt", Instant.now(), 10)
    val file2 = createFileElement("/data.doc", Instant.now(), 20)
    val file3 = createFileElement("/calc.xls", Instant.now(), 30)
    val file4 = createFileElement("/user.dat", Instant.now(), 40)
    val file5 = createFileElement("/temp/alpha.txt", Instant.now(), 50, Some(folder1), 1)
    val file6 = createFileElement("/home/beta.txt", Instant.now(), 60, Some(folder2), 1)
    val expResult = List(file3, file2, file1, folder2, folder1, file4, file6, file5)

    runSource().map { elements =>
      elements should contain theSameElementsInOrderAs expResult
    }
  }