/*
 * Copyright 2018-2021 The Developers Team.
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

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.http.scaladsl.model.ResponseEntity
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.sync.{AsyncTestHelper, FileTestHelper, SyncTypes}
import org.mockito.Mockito.{verify, when}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.Future

class FileSystemSyncProtocolSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with MockitoSugar with AsyncTestHelper {
  /**
    * Returns a ''Source'' that simulates the content of a file.
    *
    * @return a source for the content of a test file
    */
  private def fileContent: Source[ByteString, NotUsed] =
    Source(ByteString(FileTestHelper.TestData).grouped(42).toList)

  "FileSystemSyncProtocol" should "read the content of a folder" in {
    val FolderID = "theFolder"
    val PathPrefix = "/the/test/path/"
    val Level = 28
    val files = (1 to 10).map(FileSystemProtocolConverterTestImpl.testFile(_)).map(f => (f.id, f)).toMap
    val folders = (20 to 30).map(FileSystemProtocolConverterTestImpl.testFolder).map(f => (f.id, f)).toMap
    val content = Model.FolderContent(FolderID, files, folders)
    val expFiles = (1 to 10).map(idx => FileSystemProtocolConverterTestImpl.testFileElement(idx, PathPrefix, Level))
    val expFolders = (20 to 30)
      .map(idx => FileSystemProtocolConverterTestImpl.testFolderElement(idx, PathPrefix, Level))
    val helper = new ProtocolTestHelper

    helper.withFileSystem { fs =>
      when(fs.folderContent(FileSystemProtocolConverterTestImpl.IDPrefix + FolderID))
        .thenReturn(helper.stubOperation(content))
    }
    val result = futureResult(helper.protocol.readFolder(FolderID, PathPrefix, Level))
    result should have size (expFiles.size + expFolders.size)
    result should contain allElementsOf expFiles
    result should contain allElementsOf expFolders
  }

  it should "read the content of the root folder" in {
    val RootID = "TheRootFolder"
    val files = (1 to 5).map(FileSystemProtocolConverterTestImpl.testFile(_)).map(f => (f.id, f)).toMap
    val folders = (10 to 16).map(FileSystemProtocolConverterTestImpl.testFolder).map(f => (f.id, f)).toMap
    val content = Model.FolderContent(RootID, files, folders)
    val expFiles = (1 to 5).map(idx => FileSystemProtocolConverterTestImpl.testFileElement(idx, "/", 0))
    val expFolders = (10 to 16)
      .map(idx => FileSystemProtocolConverterTestImpl.testFolderElement(idx, "/", 0))
    val helper = new ProtocolTestHelper

    helper.withFileSystem { fs =>
      when(fs.rootID).thenReturn(helper.stubOperation(RootID))
      when(fs.folderContent(RootID)).thenReturn(helper.stubOperation(content))
    }
    val result = futureResult(helper.protocol.readRootFolder())
    result should have size (expFiles.size + expFolders.size)
    result should contain allElementsOf expFiles
    result should contain allElementsOf expFolders
  }

  it should "remove a file" in {
    val FileID = "theFileToRemove"
    val helper = new ProtocolTestHelper

    helper.withFileSystem { fs =>
      when(fs.deleteFile(FileSystemProtocolConverterTestImpl.elementIDFromString(FileID)))
        .thenReturn(helper.stubOperation(()))
    }
    futureResult(helper.protocol.removeFile(FileID))
    helper.withFileSystem { fs =>
      verify(fs).deleteFile(FileSystemProtocolConverterTestImpl.elementIDFromString(FileID))
    }
  }

  it should "remove a folder" in {
    val FolderId = "theFolderToRemove"
    val helper = new ProtocolTestHelper

    helper.withFileSystem { fs =>
      when(fs.deleteFolder(FileSystemProtocolConverterTestImpl.elementIDFromString(FolderId)))
        .thenReturn(helper.stubOperation(()))
    }
    futureResult(helper.protocol.removeFolder(FolderId))
    helper.withFileSystem { fs =>
      verify(fs).deleteFolder(FileSystemProtocolConverterTestImpl.elementIDFromString(FolderId))
    }
  }

  it should "create a new folder" in {
    val folderElem = FileSystemProtocolConverterTestImpl.testFolderElement(1, "", 11)
    val folder = FileSystemProtocolConverterTestImpl.testFolder(1)
    val ParentPath = "/path/to/parent"
    val ParentID = "theParentFolder"
    val helper = new ProtocolTestHelper

    helper.withFileSystem { fs =>
      when(fs.resolvePath(ParentPath)).thenReturn(helper.stubOperation(ParentID))
      when(fs.createFolder(ParentID, folder)).thenReturn(helper.stubOperation("aNewFolder"))
    }
    futureResult(helper.protocol.createFolder(ParentPath, folder.name, folderElem))
    helper.withFileSystem { fs =>
      verify(fs).createFolder(ParentID, folder)
    }
  }

  it should "create a new file" in {
    val fileElem = FileSystemProtocolConverterTestImpl.testFileElement(2, "", 17)
    val file = FileSystemProtocolConverterTestImpl.testFile(2)
    val content = fileContent
    val ParentPath = "/the/parent/path"
    val ParentID = "parentFolderOfNewFile"
    val helper = new ProtocolTestHelper

    helper.withFileSystem { fs =>
      when(fs.resolvePath(ParentPath)).thenReturn(helper.stubOperation(ParentID))
      when(fs.createFile(ParentID, file, content)).thenReturn(helper.stubOperation("aNewFile"))
    }
    futureResult(helper.protocol.createFile(ParentPath, file.name, fileElem, content))
    helper.withFileSystem { fs =>
      verify(fs).createFile(ParentID, file, content)
    }
  }

  it should "update a file" in {
    val fileElem = FileSystemProtocolConverterTestImpl.testFileElement(3, "", 1)
    val fsFile = FileSystemProtocolConverterTestImpl.testFile(3, optName = Some(""))
    val content = fileContent
    val helper = new ProtocolTestHelper

    helper.withFileSystem { fs =>
      when(fs.updateFileAndContent(fsFile, content)).thenReturn(helper.stubOperation(()))
    }
    futureResult(helper.protocol.updateFile(fileElem, content))
    helper.withFileSystem { fs =>
      verify(fs).updateFileAndContent(fsFile, content)
    }
  }

  it should "download a file" in {
    val FileIDStr = "theIDStr"
    val data = fileContent
    val entity = mock[ResponseEntity]
    when(entity.dataBytes).thenReturn(data)
    val helper = new ProtocolTestHelper

    helper.withFileSystem { fs =>
      when(fs.downloadFile(FileSystemProtocolConverterTestImpl.elementIDFromString(FileIDStr)))
        .thenReturn(helper.stubOperation(entity))
    }
    futureResult(helper.protocol.downloadFile(FileIDStr)) should be(data)
  }

  it should "close the file system in its close implementation" in {
    val helper = new ProtocolTestHelper

    helper.closeProtocol()
      .withFileSystem { fs =>
        verify(fs).close()
      }
  }

  it should "stop the HTTP sender actor in its close implementation" in {
    val helper = new ProtocolTestHelper

    helper.closeProtocol()
      .expectHttpSenderStopped()
  }

  /**
    * A test helper class managing a test protocol instance and its
    * dependencies.
    */
  private class ProtocolTestHelper {
    /** The mock for the file system. */
    private val fileSystem = mock[FileSystem[String, Model.File[String], Model.Folder[String],
      Model.FolderContent[String, Model.File[String], Model.Folder[String]]]]

    /** The actor for sending requests (not actually invoked). */
    private val httpSender = testKit.createTestProbe[HttpRequestSender.HttpCommand]()

    /** The protocol to be tested. */
    val protocol = new FileSystemSyncProtocol[String, Model.File[String], Model.Folder[String]](fileSystem,
      httpSender.ref, FileSystemProtocolConverterTestImpl)

    /**
      * Returns a stub file system operation that produces the given value.
      *
      * @param value the return value
      * @tparam R the type of the value
      * @return the operation producing this value
      */
    def stubOperation[R](value: R): FileSystem.Operation[R] = FileSystem.Operation { sender =>
      sender should be(httpSender.ref)
      Future.successful(value)
    }

    /**
      * Allows accessing the mock for the file system by invoking the passed in
      * access function. This can be used to prepare or verify the mock.
      *
      * @param f the function to prepare the mock file system
      * @return this test helper
      */
    def withFileSystem(f: FileSystem[String, Model.File[String], Model.Folder[String],
      Model.FolderContent[String, Model.File[String], Model.Folder[String]]] => Unit): ProtocolTestHelper = {
      f(fileSystem)
      this
    }

    /**
      * Invokes the close() function on the test protocol.
      *
      * @return this test helper
      */
    def closeProtocol(): ProtocolTestHelper = {
      protocol.close()
      this
    }

    /**
      * Expects that the request sender actor has been stopped.
      *
      * @return this test helper
      */
    def expectHttpSenderStopped(): ProtocolTestHelper = {
      httpSender.expectMessage(HttpRequestSender.Stop)
      this
    }
  }
}

/**
  * A test converter implementation which does more or less direct conversions
  * of basic elements.
  */
object FileSystemProtocolConverterTestImpl
  extends FileSystemProtocolConverter[String, Model.File[String], Model.Folder[String]] {
  /** Prefix of a file system ID. */
  final val IDPrefix = "id:"

  /** A date value to derive test modification dates. */
  final val RefDate = Instant.parse("2021-05-08T19:00:28.000Z")

  /**
    * A concrete implementation of the file type.
    */
  case class TestFile(override val id: String,
                      override val name: String,
                      override val description: String,
                      override val createdAt: Instant,
                      override val lastModifiedAt: Instant,
                      override val size: Long) extends Model.File[String]

  /**
    * A concrete implementation of the folder type.
    */
  case class TestFolder(override val id: String,
                        override val name: String,
                        override val description: String,
                        override val createdAt: Instant,
                        override val lastModifiedAt: Instant) extends Model.Folder[String]

  /**
    * Calculates a modification date for the test file with the given index.
    *
    * @param idx the index
    * @return the modified date of this test file
    */
  def modifiedDate(idx: Int): Instant = RefDate.plus(idx, ChronoUnit.HOURS)

  /**
    * Generates the test file with the given index.
    *
    * @param idx     the index
    * @param optName an optional file name
    * @return the test file with this index
    */
  def testFile(idx: Int, optName: Option[String] = None): Model.File[String] =
    TestFile(id = IDPrefix + idx, name = optName getOrElse s"testFile$idx.tst", description = null, createdAt = null,
      lastModifiedAt = modifiedDate(idx), 10 * idx)

  /**
    * Generates the test folder with the given index.
    *
    * @param idx the index
    * @return the test folder with this index
    */
  def testFolder(idx: Int): Model.Folder[String] =
    TestFolder(id = IDPrefix + idx, name = s"testFolder$idx", description = null, createdAt = null,
      lastModifiedAt = null)

  /**
    * Generates the test file element with the given index.
    *
    * @param idx   the index
    * @param path  the path to the parent folder
    * @param level the level of the file
    * @return the test file element with this index
    */
  def testFileElement(idx: Int, path: String, level: Int): SyncTypes.FsFile =
    toFileElement(testFile(idx), path, level)

  /**
    * Generates the test folder element with the given index.
    *
    * @param idx   the index
    * @param path  the path to the parent folder
    * @param level the level of the file
    * @return the test folder element with this index
    */
  def testFolderElement(idx: Int, path: String, level: Int): SyncTypes.FsFolder =
    toFolderElement(testFolder(idx), path, level)

  override def elementIDFromString(strID: String): String = IDPrefix + strID

  override def toFsFile(fileElement: SyncTypes.FsFile, name: String): Model.File[String] =
    TestFile(id = elementID(fileElement), name = name, description = null, createdAt = null,
      lastModifiedAt = fileElement.lastModified, size = fileElement.size)

  override def toFsFolder(folderElement: SyncTypes.FsFolder, name: String): Model.Folder[String] =
    TestFolder(id = elementID(folderElement), name = name, description = null, createdAt = null,
      lastModifiedAt = null)

  override def toFileElement(file: Model.File[String], path: String, level: Int): SyncTypes.FsFile =
    SyncTypes.FsFile(id = file.id.substring(IDPrefix.length), relativeUri = path + file.name, level = level,
      lastModified = file.lastModifiedAt, size = file.size)

  override def toFolderElement(folder: Model.Folder[String], path: String, level: Int): SyncTypes.FsFolder =
    SyncTypes.FsFolder(id = folder.id.substring(IDPrefix.length), relativeUri = path + folder.name, level = level)
}
