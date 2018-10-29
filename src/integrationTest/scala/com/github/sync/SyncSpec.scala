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

package com.github.sync

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.github.sync.WireMockSupport._
import com.github.sync.cli.Sync
import com.github.sync.impl.SyncStreamFactoryImpl
import com.github.sync.local.LocalUriResolver
import com.github.sync.webdav.{DavConfig, DavSourceFileProvider, RequestQueue}
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Integration test class for sync processes.
  */
class SyncSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with
  BeforeAndAfterAll with BeforeAndAfterEach with Matchers with FileTestHelper with
  AsyncTestHelper with WireMockSupport {
  def this() = this(ActorSystem("SyncSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  override protected def afterEach(): Unit = {
    tearDownTestFile()
    super.afterEach()
  }

  /**
    * @inheritdoc Use a higher timeout because of more complex operations.
    */
  override val timeout: Duration = 10.seconds

  /**
    * Creates a test file with the given name in the directory specified. The
    * content of the file is the name in plain text.
    *
    * @param dir  the parent directory
    * @param name the name of the file
    * @return the path to the newly created file
    */
  private def createTestFile(dir: Path, name: String): Path =
    writeFileContent(dir.resolve(name), name)

  /**
    * Reads the content of a file that is located in the given directory.
    *
    * @param dir  the directory
    * @param name the name of the file
    * @return the content of this file as string
    */
  private def readFileInPath(dir: Path, name: String): String = {
    val file = dir.resolve(name)
    readDataFile(file)
  }

  /**
    * Checks whether a file with the given name exists in the directory
    * provided. The content of the file is checked as well.
    *
    * @param dir  the parent directory
    * @param name the name of the file
    */
  private def checkFile(dir: Path, name: String): Unit = {
    readFileInPath(dir, name) should be(name)
  }

  /**
    * Checks that the file specified does not exist.
    *
    * @param dir  the parent directory
    * @param name the name of the file
    */
  private def checkFileNotPresent(dir: Path, name: String): Unit = {
    val file = dir.resolve(name)
    Files.exists(file) shouldBe false
  }

  /**
    * Returns a ''SyncStreamFactory'' that always returns the given
    * ''SourceFileProvider''.
    *
    * @param provider the ''SourceFileProvider'' to be returned
    * @return the factory
    */
  private def factoryWithMockSourceProvider(provider: SourceFileProvider):
  DelegateSyncStreamFactory = new DelegateSyncStreamFactory {
    override def createSourceFileProvider(uri: String)
                                         (implicit ec: ExecutionContext, system: ActorSystem,
                                          mat: ActorMaterializer):
    ArgsFunc[SourceFileProvider] = _ => Future.successful(provider)
  }

  "Sync" should "synchronize two directory structures" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "test1.txt")
    createTestFile(srcFolder, "test2.txt")
    createTestFile(srcFolder, "ignored.tmp")
    createTestFile(dstFolder, "toBeRemoved.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--filter", "exclude:*.tmp")

    val result = futureResult(Sync.syncProcess(options))
    result.totalOperations should be(result.successfulOperations)
    checkFile(dstFolder, "test1.txt")
    checkFileNotPresent(dstFolder, "toBeRemoved.txt")
    checkFileNotPresent(dstFolder, "ignored.tmp")
  }

  it should "apply operations to an alternative target" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val dstFolder2 = Files.createDirectory(createPathInDirectory("dest2"))
    createTestFile(srcFolder, "new.txt")
    createTestFile(dstFolder, "obsolete.dat")
    createTestFile(dstFolder2, "obsolete.dat")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--apply", "target:" + dstFolder2.toAbsolutePath.toString)

    futureResult(Sync.syncProcess(options))
    checkFile(dstFolder2, "new.txt")
    checkFile(dstFolder, "obsolete.dat")
    checkFileNotPresent(dstFolder2, "obsolete.dat")
  }

  it should "store sync operations in a log file" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val logFile = createFileReference()
    createTestFile(srcFolder, "create.txt")
    createTestFile(srcFolder, "ignored.tmp")
    createTestFile(dstFolder, "removed.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--filter", "exclude:*.tmp", "--log", logFile.toAbsolutePath.toString)

    val result = futureResult(Sync.syncProcess(options))
    result.totalOperations should be(2)
    result.successfulOperations should be(2)
    val lines = Files.readAllLines(logFile)
    lines.get(0) should include("CREATE 0 FILE %2Fcreate.txt 0")
    lines.get(1) should include("REMOVE 0 FILE %2Fremoved.txt 0")
    checkFileNotPresent(dstFolder, "removed.txt")
  }

  it should "append an existing log file" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val LogHeader = "This is my log." + System.lineSeparator()
    val logFile = createDataFile(content = LogHeader)
    createTestFile(srcFolder, "fileToSync.dat")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--apply", "none", "--log", logFile.toAbsolutePath.toString)

    futureResult(Sync.syncProcess(options))
    readDataFile(logFile) should startWith(LogHeader)
  }

  it should "support an apply mode 'None'" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "file1.txt")
    createTestFile(srcFolder, "file2.txt")
    createTestFile(dstFolder, "removed.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--apply", "NonE")

    val result = futureResult(Sync.syncProcess(options))
    result.successfulOperations should be(3)
    result.totalOperations should be(result.successfulOperations)
    checkFile(dstFolder, "removed.txt")
    checkFileNotPresent(dstFolder, "file1.txt")
  }

  it should "execute sync operations from a sync log file" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val NewFolderName = "newFolder"
    val RemoveFileName = "killed.txt"
    val lastModified = Instant.parse("2018-09-12T21:06:00.10Z")
    createTestFile(srcFolder, "syncFile.txt")
    createTestFile(srcFolder, "otherFile.dat")
    createTestFile(dstFolder, RemoveFileName)
    createTestFile(dstFolder, "remaining.txt")
    val procLog = createPathInDirectory("processed.log")
    val operations = List(s"CREATE 0 FILE /syncFile.txt 0 $lastModified 42",
      s"CREATE 0 FOLDER /$NewFolderName 0",
      s"REMOVE 0 FILE /$RemoveFileName 0 2018-09-12T21:12:45.00Z 10")
    val syncLogFile = createDataFile(content = operations.mkString("\n"))
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--sync-log", syncLogFile.toAbsolutePath.toString, "--log", procLog.toAbsolutePath.toString)

    val result = futureResult(Sync.syncProcess(options))
    result.successfulOperations should be(operations.size)
    result.totalOperations should be(result.successfulOperations)
    checkFile(dstFolder, "syncFile.txt")
    checkFileNotPresent(dstFolder, "otherFile.dat")
    checkFileNotPresent(dstFolder, RemoveFileName)
    val newFolder = dstFolder.resolve(NewFolderName)
    Files.isDirectory(newFolder) shouldBe true
  }

  it should "ignore invalid sync operations in the sync log file" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val SuccessFile = "successSync.txt"
    val lastModified = Instant.parse("2018-09-12T21:35:10.10Z")
    createTestFile(srcFolder, SuccessFile)
    val operations = List("not a valid sync operation!?",
      s"CREATE 0 FILE /$SuccessFile 0 $lastModified 42")
    val syncLogFile = createDataFile(content = operations.mkString("\n"))
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--sync-log", syncLogFile.toAbsolutePath.toString)

    futureResult(Sync.syncProcess(options))
    checkFile(dstFolder, SuccessFile)
  }

  it should "skip an operation that cannot be processed" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val SuccessFile = "successSync.txt"
    val lastModified = Instant.parse("2018-09-13T16:39:16.10Z")
    createTestFile(srcFolder, SuccessFile)
    val operations = List(s"OVERRIDE 0 FILE /nonExisting.file 0 $lastModified 10",
      s"CREATE 0 FILE /$SuccessFile 0 $lastModified 42")
    val syncLogFile = createDataFile(content = operations.mkString("\n"))
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--sync-log", syncLogFile.toAbsolutePath.toString, "--timeout", "2")

    futureResult(Sync.syncProcess(options))
    checkFile(dstFolder, SuccessFile)
  }

  it should "log only successfully executed sync operations" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val SuccessFile = "successSync.txt"
    val FailedFile = "nonExisting.file"
    val lastModified = Instant.parse("2018-10-29T20:26:49.11Z")
    createTestFile(srcFolder, SuccessFile)
    val operations = List(s"OVERRIDE 0 FILE /$FailedFile 0 $lastModified 10",
      s"CREATE 0 FILE /$SuccessFile 0 $lastModified 42")
    val syncLogFile = createDataFile(content = operations.mkString("\n"))
    val logFile = createFileReference()
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--sync-log", syncLogFile.toAbsolutePath.toString, "--log", logFile.toAbsolutePath.toString,
      "--timeout", "2")

    futureResult(Sync.syncProcess(options))
    val lines = Files.readAllLines(logFile)
    lines should have size 1
    lines.get(0) should include(SuccessFile)
  }

  it should "skip operations in the sync log that are contained in the processed log" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val ProcessedFile = "done.txt"
    val NewFile = "copy.txt"
    val lastModified = Instant.parse("2018-09-13T19:00:01.11Z")
    createTestFile(srcFolder, ProcessedFile)
    createTestFile(srcFolder, NewFile)
    val ProcessedOp = s"CREATE 0 FILE /$ProcessedFile 0 $lastModified 2"
    val operations = List(ProcessedOp, s"CREATE 0 FILE /$NewFile 0 $lastModified 4")
    val syncLogFile = createDataFile(content = operations.mkString("\n"))
    val logFile = createDataFile(content = ProcessedOp)
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--sync-log", syncLogFile.toAbsolutePath.toString, "--log", logFile.toAbsolutePath.toString)

    val result = futureResult(Sync.syncProcess(options))
    result.successfulOperations should be(1)
    checkFile(dstFolder, NewFile)
    checkFileNotPresent(dstFolder, ProcessedFile)
  }

  it should "support a WebDav URI for the source structure" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val WebDavPath = "/test%20data/folder%20(2)/folder%20(3)"
    stubFolderRequest(WebDavPath, "folder3.xml")
    val logFile = createFileReference()
    val options = Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--log", logFile.toAbsolutePath.toString, "--apply", "None", "--src-user", UserId,
      "--src-password", Password)

    val result = futureResult(Sync.syncProcess(options))
    result.successfulOperations should be(1)
    val lines = Files.readAllLines(logFile)
    lines.size() should be(1)
    lines.get(0) should be("CREATE 0 FILE %2Ffile%20%285%29.mp3 0 2018-09-19T20:14:00Z 500")
  }

  it should "support a WebDav URI for the destination structure" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val WebDavPath = "/test%20data/folder%20(2)/folder%20(3)"
    stubFolderRequest(WebDavPath, "folder3_full.xml")
    val logFile = createFileReference()
    val options = Array(srcFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--log", logFile.toAbsolutePath.toString, "--apply", "None", "--dst-user", UserId,
      "--dst-password", Password, "--dst-modified-Property", "Win32LastModifiedTime")

    val result = futureResult(Sync.syncProcess(options))
    result.successfulOperations should be(1)
    val lines = Files.readAllLines(logFile)
    lines.size() should be(1)
    lines.get(0) should be("REMOVE 0 FILE %2Ffile%20%285%29.mp3 0 2018-09-19T20:14:00Z 500")
  }

  it should "make sure that an element source for local files is shutdown" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val shutdownCount = new AtomicInteger
    val provider = new LocalUriResolver(srcFolder) {
      override def shutdown(): Unit = {
        shutdownCount.incrementAndGet() // records this invocation
        super.shutdown()
      }
    }
    implicit val factory: SyncStreamFactory = factoryWithMockSourceProvider(provider)
    createTestFile(srcFolder, "test.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString)

    futureResult(Sync.syncProcess(options))
    shutdownCount.get() should be(1)
  }

  it should "create a correct SourceFileProvider for a WebDav source" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val WebDavPath = "/test%20data/folder%20(2)/folder%20(3)"
    stubFolderRequest(WebDavPath, "folder3.xml")
    stubFor(authorized(get(urlPathEqualTo(WebDavPath + "/file%20(5).mp3")))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBodyFile("response.txt")))
    val options = Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--src-user", UserId, "--src-password", Password)

    val result = futureResult(Sync.syncProcess(options))
    result.successfulOperations should be(1)
    val fileContent = readFileInPath(dstFolder, "file (5).mp3")
    fileContent should startWith(FileTestHelper.TestData take 50)
  }

  it should "support sync operations targetting a WebDav server" in {
    implicit val factory: SyncStreamFactory = SyncStreamFactoryImpl
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val WebDavPath = "/destination"
    val FileName = "testDataFile.dat"
    val ModifiedProperty = "Win32LastModifiedTime"
    val ModifiedNamespace = "modified-urn:"
    createTestFile(srcFolder, FileName)
    stubFolderRequest(WebDavPath, "empty_folder.xml")
    stubFor(authorized(put(urlPathEqualTo(WebDavPath + "/" + FileName)))
      .withRequestBody(equalTo(FileName))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    stubFor(request("PROPPATCH", urlPathEqualTo(WebDavPath + "/" + FileName))
      .withRequestBody(matching(".*xmlns:ssync=\"" + ModifiedNamespace + ".*"))
      .withRequestBody(matching(s".*<ssync:$ModifiedProperty>.*"))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val options = Array(srcFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--dst-user", UserId, "--dst-password", Password,
      "--dst-modified-property", ModifiedProperty, "--dst-modified-namespace", ModifiedNamespace)

    val result = futureResult(Sync.syncProcess(options))
    result.successfulOperations should be(1)
    getAllServeEvents should have size 3
  }

  it should "make sure that an element source for WebDav operations is shutdown" in {
    val WebDavPath = "/destination"
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val davConfig = DavConfig(serverUri(WebDavPath), UserId, Password,
      DavConfig.DefaultModifiedProperty, None)
    val shutdownCount = new AtomicInteger
    val provider = new DavSourceFileProvider(davConfig, new RequestQueue(davConfig.rootUri)) {
      override def shutdown(): Unit = {
        shutdownCount.incrementAndGet() // records this invocation
        super.shutdown()
      }
    }
    implicit val factory: SyncStreamFactory = factoryWithMockSourceProvider(provider)
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    stubFolderRequest(WebDavPath, "empty_folder.xml")
    stubSuccess()
    val options = Array(srcFolder.toAbsolutePath.toString, "dav:" + davConfig.rootUri,
      "--dst-user", UserId, "--dst-password", Password)

    futureResult(Sync.syncProcess(options))
    shutdownCount.get() should be(1)
  }
}
