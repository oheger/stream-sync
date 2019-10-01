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

package com.github.sync.cli

import java.io.File
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorIdentity, ActorSystem, Identify}
import akka.http.scaladsl.model.StatusCodes
import akka.pattern.AskTimeoutException
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.{ByteString, Timeout}
import com.github.sync.WireMockSupport.{Password, _}
import com.github.sync._
import com.github.sync.cli.ParameterManager.Parameters
import com.github.sync.cli.SyncParameterManager.CryptMode
import com.github.sync.crypt.{CryptOpHandler, CryptService, CryptStage, DecryptOpHandler}
import com.github.sync.local.LocalUriResolver
import com.github.sync.util.UriEncodingHelper
import com.github.sync.webdav.{DavConfig, DavSourceFileProvider}
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.http.RequestMethod
import org.scalatest._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}

/**
  * Integration test class for sync processes.
  */
class SyncSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender with FlatSpecLike with
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

  import system.dispatcher

  /**
    * @inheritdoc Use a higher timeout because of more complex operations.
    */
  override val timeout: Duration = 10.seconds

  /** The object to materialize streams. */
  implicit private val mat: ActorMaterializer = ActorMaterializer()

  /**
    * Creates a test file with the given name in the directory specified. The
    * content of the file is the name in plain text.
    *
    * @param dir      the parent directory
    * @param name     the name of the file
    * @param fileTime an optional timestamp for the file
    * @param content  optional content of the file
    * @return the path to the newly created file
    */
  private def createTestFile(dir: Path, name: String, fileTime: Option[Instant] = None,
                             content: Option[String] = None): Path = {
    val path = writeFileContent(dir.resolve(name), content getOrElse name)
    fileTime foreach { time =>
      Files.setLastModifiedTime(path, FileTime.from(time))
    }
    path
  }

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
    * Reads the content of a binary file that is located in the given directory
    * and returns it as byte array.
    *
    * @param dir  the directory
    * @param name the name of the file
    * @return the content of this file as byte array
    */
  private def readBinaryFileInPath(dir: Path, name: String): Array[Byte] = {
    val file = dir.resolve(name)
    Files.readAllBytes(file)
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
  private def factoryWithMockSourceProvider(provider: SourceFileProvider): SyncComponentsFactory =
    new SyncComponentsFactory {
      override def createSourceComponentsFactory(uri: String, timeout: Timeout, parameters: Parameters)
                                                (implicit system: ActorSystem, mat: ActorMaterializer,
                                                 ec: ExecutionContext, consoleReader: ConsoleReader):
      Future[(Parameters, SyncComponentsFactory.SourceComponentsFactory)] =
        super.createSourceComponentsFactory(uri, timeout, parameters) map { t =>
          val delegateFactory = new DelegateSourceComponentsFactory(t._2) {
            override def createSourceFileProvider(): SourceFileProvider = provider
          }
          (t._1, delegateFactory)
        }
    }

  /**
    * Performs a crypt operation on the given data and returns the result.
    *
    * @param key     the key for encryption / decryption
    * @param handler the crypt handler
    * @param data    the data to be processed
    * @return the processed data
    */
  private def crypt(key: String, handler: CryptOpHandler, data: ByteString): ByteString = {
    val source = Source.single(data)
    val stage = new CryptStage(handler, CryptStage.keyFromString(key))
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    futureResult(source.via(stage).runWith(sink))
  }

  /**
    * Encrypts the given file name.
    *
    * @param key  the key for encryption
    * @param data the name to be encrypted
    * @return the encrypted name
    */
  private def encryptName(key: String, data: String): String =
    futureResult(CryptService.encryptName(CryptStage.keyFromString(key), data))

  /**
    * Decrypts the given file name.
    *
    * @param key  the key for decryption
    * @param name the name to be decrypted
    * @return the decrypted name
    */
  private def decryptName(key: String, name: String): String =
    futureResult(CryptService.decryptName(CryptStage.keyFromString(key), name))

  /**
    * Creates a new ''Sync'' instance that is configured to use the actor
    * system of this test class.
    *
    * @return the special ''Sync'' instance
    */
  private def createSync(): Sync =
    new Sync {
      override implicit def actorSystem: ActorSystem = system
    }

  "Sync" should "synchronize two directory structures" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "test1.txt")
    createTestFile(srcFolder, "test2.txt")
    createTestFile(srcFolder, "ignored.tmp")
    createTestFile(dstFolder, "toBeRemoved.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--filter", "exclude:*.tmp")

    val result = futureResult(Sync.syncProcess(factory, options))
    result.totalOperations should be(result.successfulOperations)
    checkFile(dstFolder, "test1.txt")
    checkFileNotPresent(dstFolder, "toBeRemoved.txt")
    checkFileNotPresent(dstFolder, "ignored.tmp")
  }

  it should "start a sync process via its run() function" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "test1.txt")
    createTestFile(srcFolder, "test2.txt")
    createTestFile(srcFolder, "ignored.tmp")
    createTestFile(dstFolder, "toBeRemoved.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--filter", "exclude:*.tmp")
    val sync = createSync()

    sync.run(options)
    checkFile(dstFolder, "test1.txt")
    checkFileNotPresent(dstFolder, "toBeRemoved.txt")
    checkFileNotPresent(dstFolder, "ignored.tmp")
  }

  it should "apply operations to an alternative target" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val dstFolder2 = Files.createDirectory(createPathInDirectory("dest2"))
    createTestFile(srcFolder, "new.txt")
    createTestFile(dstFolder, "obsolete.dat")
    createTestFile(dstFolder2, "obsolete.dat")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--apply", "target:" + dstFolder2.toAbsolutePath.toString)

    futureResult(Sync.syncProcess(factory, options))
    checkFile(dstFolder2, "new.txt")
    checkFile(dstFolder, "obsolete.dat")
    checkFileNotPresent(dstFolder2, "obsolete.dat")
  }

  it should "store sync operations in a log file" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val logFile = createFileReference()
    createTestFile(srcFolder, "create.txt")
    createTestFile(srcFolder, "ignored.tmp")
    createTestFile(dstFolder, "removed.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--filter", "exclude:*.tmp", "--log", logFile.toAbsolutePath.toString)

    val result = futureResult(Sync.syncProcess(factory, options))
    result.totalOperations should be(2)
    result.successfulOperations should be(2)
    val lines = Files.readAllLines(logFile)
    lines.get(0) should include("CREATE 0 FILE %2Fcreate.txt 0")
    lines.get(1) should include("REMOVE 0 FILE %2Fremoved.txt 0")
    checkFileNotPresent(dstFolder, "removed.txt")
  }

  it should "append an existing log file" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val LogHeader = "This is my log." + System.lineSeparator()
    val logFile = createDataFile(content = LogHeader)
    createTestFile(srcFolder, "fileToSync.dat")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--apply", "none", "--log", logFile.toAbsolutePath.toString)

    futureResult(Sync.syncProcess(factory, options))
    readDataFile(logFile) should startWith(LogHeader)
  }

  it should "support an apply mode 'None'" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "file1.txt")
    createTestFile(srcFolder, "file2.txt")
    createTestFile(dstFolder, "removed.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--apply", "NonE")

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(3)
    result.totalOperations should be(result.successfulOperations)
    checkFile(dstFolder, "removed.txt")
    checkFileNotPresent(dstFolder, "file1.txt")
  }

  it should "execute sync operations from a sync log file" in {
    val factory = new SyncComponentsFactory
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

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(operations.size)
    result.totalOperations should be(result.successfulOperations)
    checkFile(dstFolder, "syncFile.txt")
    checkFileNotPresent(dstFolder, "otherFile.dat")
    checkFileNotPresent(dstFolder, RemoveFileName)
    val newFolder = dstFolder.resolve(NewFolderName)
    Files.isDirectory(newFolder) shouldBe true
  }

  it should "ignore invalid sync operations in the sync log file" in {
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

    createSync().run(options)
    checkFile(dstFolder, SuccessFile)
  }

  it should "skip an operation that cannot be processed" in {
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

    createSync().run(options)
    checkFile(dstFolder, SuccessFile)
  }

  it should "log only successfully executed sync operations" in {
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

    createSync().run(options)
    val lines = Files.readAllLines(logFile)
    lines should have size 1
    lines.get(0) should include(SuccessFile)
  }

  it should "skip operations in the sync log that are contained in the processed log" in {
    val factory = new SyncComponentsFactory
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

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(1)
    checkFile(dstFolder, NewFile)
    checkFileNotPresent(dstFolder, ProcessedFile)
  }

  it should "support a WebDav URI for the source structure" in {
    val factory = new SyncComponentsFactory
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val WebDavPath = "/test%20data/folder%20(2)/folder%20(3)"
    stubFolderRequest(WebDavPath, "folder3.xml")
    val logFile = createFileReference()
    val options = Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--log", logFile.toAbsolutePath.toString, "--apply", "None", "--src-user", UserId,
      "--src-password", Password)

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(1)
    val lines = Files.readAllLines(logFile)
    lines.size() should be(1)
    lines.get(0) should be("CREATE 0 FILE %2Ffile%20%285%29.mp3 0 2018-09-19T20:14:00Z 500")
  }

  it should "support a WebDav URI for the destination structure" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val WebDavPath = "/test%20data/folder%20(2)/folder%20(3)"
    stubFolderRequest(WebDavPath, "folder3_full.xml")
    val logFile = createFileReference()
    val options = Array(srcFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--log", logFile.toAbsolutePath.toString, "--apply", "None", "--dst-user", UserId,
      "--dst-password", Password, "--dst-modified-Property", "Win32LastModifiedTime")

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(1)
    val lines = Files.readAllLines(logFile)
    lines.size() should be(1)
    lines.get(0) should be("REMOVE 0 FILE %2Ffile%20%285%29.mp3 0 2018-09-19T20:14:00Z 500")
  }

  it should "do proper cleanup for a Dav source when using a log file source and apply mode NONE" in {
    val factory = new SyncComponentsFactory
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val procLog = createPathInDirectory("processed.log")
    val operations = List(s"CREATE 0 FILE /syncFile.txt 0 2019-09-04T21:30:23.00Z 42")
    val syncLogFile = createDataFile(content = operations.mkString("\n"))
    val options = Array("dav:http://irrelevant.host.org/test", dstFolder.toAbsolutePath.toString,
      "--sync-log", syncLogFile.toAbsolutePath.toString, "--log", procLog.toAbsolutePath.toString,
      "--apply", "none", "--src-user", UserId, "--src-password", Password)

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(operations.size)
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
    val factory = factoryWithMockSourceProvider(provider)
    createTestFile(srcFolder, "test.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString)

    futureResult(Sync.syncProcess(factory, options))
    shutdownCount.get() should be(1)
  }

  it should "stop the actor for local sync operations after stream processing" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val identifyId = 20190817
    createTestFile(srcFolder, "test.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString)

    futureResult(Sync.syncProcess(factory, options))
    val selection = system.actorSelection(s"/user/${LocalFsDestinationComponentsFactory.LocalSyncOpActorName}")
    selection ! Identify(identifyId)
    val identity = expectMsgType[ActorIdentity]
    identity.correlationId should be(identifyId)
    identity.ref.isDefined shouldBe false
  }

  it should "take the time zone of a local files source into account" in {
    val DeltaHours = 2
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val UnchangedFile = "notChanged.txt"
    val ChangedFile = "modifiedTime.txt"
    val Time1 = Instant.parse("2018-11-08T20:45:00.01Z")
    val Time2 = Instant.parse("2018-11-08T20:47:26.14Z")
    createTestFile(srcFolder, UnchangedFile, fileTime = Some(Time1))
    createTestFile(dstFolder, UnchangedFile,
      fileTime = Some(Time1.minus(DeltaHours, ChronoUnit.HOURS)))
    createTestFile(srcFolder, ChangedFile, fileTime = Some(Time2))
    val changedPath = createTestFile(dstFolder, ChangedFile, fileTime = Some(Time2))
    val logFile = createFileReference()
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--log", logFile.toAbsolutePath.toString, "--dst-time-zone", s"UTC+0$DeltaHours:00")

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(1)
    val operations = Files.readAllLines(logFile)
    operations.get(0) should include(ChangedFile)
    val updatedTime = Files.getLastModifiedTime(changedPath).toInstant
    updatedTime should be(Time2.minus(DeltaHours, ChronoUnit.HOURS))
  }

  it should "ignore a time difference below the configured threshold" in {
    val TimeDeltaThreshold = 10
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val Time1 = Instant.parse("2018-11-19T21:18:04.34Z")
    val Time2 = Time1.plus(TimeDeltaThreshold - 1, ChronoUnit.SECONDS)
    val Time3 = Time1.minus(TimeDeltaThreshold - 1, ChronoUnit.SECONDS)
    val Name1 = "f1.dat"
    val Name2 = "f2.dat"
    createTestFile(srcFolder, Name1, fileTime = Some(Time1))
    createTestFile(srcFolder, Name2, fileTime = Some(Time1))
    createTestFile(dstFolder, Name1, fileTime = Some(Time2))
    createTestFile(dstFolder, Name2, fileTime = Some(Time3))
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--ignore-time-delta", TimeDeltaThreshold.toString)

    val result = futureResult(Sync.syncProcess(factory, options))
    result.totalOperations should be(0)
  }

  it should "create a correct SourceFileProvider for a WebDav source" in {
    val factory = new SyncComponentsFactory
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val WebDavPath = "/test%20data/folder%20(2)/folder%20(3)"
    stubFolderRequest(WebDavPath, "folder3.xml")
    stubFor(authorized(get(urlPathEqualTo(WebDavPath + "/file%20(5).mp3")))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBodyFile("response.txt")))
    val options = Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--src-user", UserId, "--src-password", Password)

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(1)
    val fileContent = readFileInPath(dstFolder, "file (5).mp3")
    fileContent should startWith(FileTestHelper.TestData take 50)
  }

  it should "support sync operations targeting a WebDav server" in {
    val factory = new SyncComponentsFactory
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

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(1)
    getAllServeEvents should have size 3
  }

  it should "make sure that an element source for WebDav operations is shutdown" in {
    val WebDavPath = "/destination"
    val davConfig = DavConfig(serverUri(WebDavPath), UserId, Password,
      DavConfig.DefaultModifiedProperty, None, deleteBeforeOverride = false,
      modifiedProperties = List(DavConfig.DefaultModifiedProperty), Timeout(10.seconds))
    val shutdownCount = new AtomicInteger
    val provider = new DavSourceFileProvider(davConfig, TestProbe().ref) {
      override def shutdown(): Unit = {
        shutdownCount.incrementAndGet() // records this invocation
        super.shutdown()
      }
    }
    val factory = factoryWithMockSourceProvider(provider)
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    stubFolderRequest(WebDavPath, "empty_folder.xml")
    stubSuccess()
    val options = Array(srcFolder.toAbsolutePath.toString, "dav:" + davConfig.rootUri,
      "--dst-user", UserId, "--dst-password", Password)

    futureResult(Sync.syncProcess(factory, options))
    shutdownCount.get() should be(1)
  }

  it should "evaluate the timeout for the WebDav file source provider" in {
    val factory = new SyncComponentsFactory
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val WebDavPath = "/test%20data/folder%20(2)/folder%20(3)"
    val timeout = 1.second
    stubFolderRequest(WebDavPath, "folder3.xml")
    stubFor(authorized(get(urlPathEqualTo(WebDavPath + "/file%20(5).mp3")))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue).withFixedDelay(2 * timeout.toMillis.toInt)
        .withBodyFile("response.txt")))
    val options = Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--src-user", UserId, "--src-password", Password, "--timeout", timeout.toSeconds.toString)

    val sync = createSync()
    val result = futureResult(Sync.syncProcess(factory, options)(system, sync.createStreamMat(), system.dispatcher))
    result.successfulOperations should be(0)
  }

  it should "evaluate the timeout for the WebDav element source" in {
    val factory = new SyncComponentsFactory
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val WebDavPath = "/test%20data/folder%20(2)/folder%20(3)"
    val timeout = 1.second
    stubFolderRequest(WebDavPath, "folder3.xml", optDelay = Some(timeout * 2))
    stubFor(authorized(get(urlPathEqualTo(WebDavPath + "/file%20(5).mp3")))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBodyFile("response.txt")))
    val options = Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--src-user", UserId, "--src-password", Password, "--timeout", timeout.toSeconds.toString)

    expectFailedFuture[AskTimeoutException](Sync.syncProcess(factory, options))
  }

  it should "support encryption of files in a destination structure" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val TestFileName = "TestFileToBeEncrypted.txt"
    createTestFile(srcFolder, TestFileName)
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--dst-encrypt-password", "!encryptDest!", "--dst-crypt-mode", "files")

    val result = futureResult(Sync.syncProcess(factory, options))
    result.totalOperations should be(result.successfulOperations)
    readBinaryFileInPath(srcFolder, TestFileName) should not be readBinaryFileInPath(dstFolder, TestFileName)
  }

  it should "support a round-trip with encryption and decryption of files" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder1 = Files.createDirectory(createPathInDirectory("destEnc"))
    val dstFolder2 = Files.createDirectory(createPathInDirectory("destPlain"))
    val TestFileName = "TestFileToBeEncryptedAndDecrypted.txt"
    val Password = "privacy"
    createTestFile(srcFolder, TestFileName)
    val options1 = Array(srcFolder.toAbsolutePath.toString, dstFolder1.toAbsolutePath.toString,
      "--dst-encrypt-password", Password, "--dst-crypt-mode", "files")
    futureResult(Sync.syncProcess(factory, options1))

    val options2 = Array(dstFolder1.toAbsolutePath.toString, dstFolder2.toAbsolutePath.toString,
      "--src-encrypt-password", Password, "--src-crypt-mode", "files")
    val result = futureResult(Sync.syncProcess(factory, options2))
    result.totalOperations should be(result.successfulOperations)
    readFileInPath(srcFolder, TestFileName) should be(readFileInPath(dstFolder2, TestFileName))
  }

  it should "correctly calculate the sizes of encrypted files" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val TestFileName = "TestFileToBeEncrypted.txt"
    createTestFile(srcFolder, TestFileName)
    val Password = "let's_crypt"
    val options1 = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--dst-encrypt-password", Password, "--dst-crypt-mode", "files")
    val options2 = Array(dstFolder.toAbsolutePath.toString, srcFolder.toAbsolutePath.toString,
      "--src-encrypt-password", Password, "--src-crypt-mode", "files")
    futureResult(Sync.syncProcess(factory, options1))

    val result1 = futureResult(Sync.syncProcess(factory, options1))
    result1.totalOperations should be(0)
    val result2 = futureResult(Sync.syncProcess(factory, options2))
    result2.totalOperations should be(0)
  }

  it should "support encrypted file names in a destination structure" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val TestFileName = "TestFileToBeEncryptedAndScrambled.txt"
    createTestFile(srcFolder, TestFileName)
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--dst-encrypt-password", "crYptiC", "--dst-crypt-mode", "FilesAndNames")

    val result = futureResult(Sync.syncProcess(factory, options))
    result.totalOperations should be(result.successfulOperations)
    val destFiles = dstFolder.toFile.list()
    destFiles should have length 1
    destFiles.head should not be TestFileName
  }

  it should "support other operations on encrypted file names" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val Password = "foo-bar;"
    val OverrideName = "FileToOverride.txt"
    val encOverrideName = encryptName(Password, OverrideName)
    val delFolderName = encryptName(Password, "folderToDelete")
    val delFileName = encryptName(Password, "fileToDelete.dat")
    createTestFile(srcFolder, OverrideName)
    val pathOverride = createTestFile(dstFolder, encOverrideName)
    val delFolder = Files.createDirectory(dstFolder.resolve(delFolderName))
    createTestFile(delFolder, delFileName)
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--dst-encrypt-password", Password, "--dst-crypt-mode", "filesAndNames")

    val result = futureResult(Sync.syncProcess(factory, options))
    result.totalOperations should be(result.successfulOperations)
    Files.exists(delFolder) shouldBe false
    val overrideData = ByteString(Files.readAllBytes(pathOverride))
    crypt(Password, DecryptOpHandler, overrideData).utf8String should be(OverrideName)
  }

  it should "support a round-trip with encrypted file names" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder1 = Files.createDirectory(createPathInDirectory("destEnc"))
    val dstFolder2 = Files.createDirectory(createPathInDirectory("destPlain"))
    createTestFile(srcFolder, "top.txt")
    val subDir = Files.createDirectory(srcFolder.resolve("sub"))
    createTestFile(subDir, "sub.dat")
    createTestFile(subDir, "anotherSub.txt")
    val Password = "test-privacy"
    val options1 = Array(srcFolder.toAbsolutePath.toString, dstFolder1.toAbsolutePath.toString,
      "--dst-encrypt-password", Password, "--dst-crypt-mode", "filesAndNames")
    futureResult(Sync.syncProcess(factory, options1))

    val options2 = Array(dstFolder1.toAbsolutePath.toString, dstFolder2.toAbsolutePath.toString,
      "--src-encrypt-password", Password, "--src-crypt-mode", "filesAndNames")
    futureResult(Sync.syncProcess(factory, options2))
    checkFile(dstFolder2, "top.txt")
    val options3 = Array(srcFolder.toAbsolutePath.toString, dstFolder2.toAbsolutePath.toString)
    val result = futureResult(Sync.syncProcess(factory, options3))
    result.totalOperations should be(0)
  }

  it should "support complex structures when syncing with encrypted file names" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "originalTestFile.tst")
    val subDir = Files.createDirectory(srcFolder.resolve("sub"))
    val subSubDir = Files.createDirectory(subDir.resolve("deep"))
    createTestFile(subDir, "sub.txt")
    createTestFile(subSubDir, "deep1.txt")
    val Password = "Complex?"
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--dst-encrypt-password", Password, "--dst-crypt-mode", "filesAndNames")
    futureResult(Sync.syncProcess(factory, options)).successfulOperations should be(5)

    createTestFile(subSubDir, "deep2.txt")
    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(1)

    def findDirectory(content: Array[File]): File = {
      val dirs = content.filter(_.isDirectory)
      dirs should have length 1
      dirs.head
    }

    val topContent = dstFolder.toFile.listFiles()
    topContent should have length 2
    val subContent = findDirectory(topContent).listFiles()
    subContent should have length 2
    val subSubContent = findDirectory(subContent).listFiles()
    subSubContent should have length 2
  }

  /**
    * Stubs a GET request to access a file from a DAV server.
    *
    * @param uri          the URI
    * @param responseFile the file to be returned
    */
  private def stubFileRequest(uri: String, responseFile: String): Unit = {
    stubFor(authorized(get(urlPathEqualTo(uri))
      .willReturn(aResponse()
        .withStatus(StatusCodes.OK.intValue)
        .withBodyFile(responseFile))))
  }

  it should "support a WebDav source with encrypted file names" in {
    val factory = new SyncComponentsFactory
    val CryptPassword = Password
    val WebDavPath = "/encrypted"
    stubFolderRequest(WebDavPath, "root_encrypted.xml")
    stubFolderRequest(WebDavPath + "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/", "folder_encrypted.xml")
    stubFileRequest(WebDavPath + "/HLL2gCNjWKvwRnp4my1U2ex0QLKWpZs=", "encrypted1.dat")
    stubFileRequest(WebDavPath + "/uBQQYWockOWLuCROIHviFhU2XayMtps=", "encrypted2.dat")
    stubFileRequest(WebDavPath + "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN",
      "encrypted3.dat")
    stubFileRequest(WebDavPath + "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Z3BDvmY89rQwUqJ3XzMUWgtBE9bcOCYxiTq-Zfo-sNlIGA==",
      "encrypted4.dat")
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(dstFolder, "foo.txt", content = Some("Test file content"))
    val pathDeleted = createTestFile(dstFolder, "toDelete.txt")
    val options = Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--src-encrypt-password", CryptPassword, "--src-crypt-mode", "filesAndNames", "--src-user", UserId,
      "--src-password", Password)

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(result.totalOperations)
    Files.exists(pathDeleted) shouldBe false
    val rootFiles = dstFolder.toFile.listFiles()
    rootFiles.map(_.getName) should contain only("foo.txt", "bar.txt", "sub")
    checkFile(dstFolder, "foo.txt")
    val subFolder = dstFolder.resolve("sub")
    checkFile(subFolder, "subFile.txt")
    checkFile(subFolder, "anotherSubFile.dat")
  }

  it should "support a WebDav destination with encrypted file names" in {
    val factory = new SyncComponentsFactory
    val CryptPassword = "secretServer"
    val WebDavPath = "/secret"
    stubSuccess()
    stubFolderRequest(WebDavPath, "empty_folder.xml")
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val FileName = "plainFile.txt"
    val Content = "This is the content of the test file ;-)"
    createTestFile(srcFolder, FileName, content = Some(Content))
    val options = Array(srcFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--dst-encrypt-password", CryptPassword, "--dst-crypt-mode", "filesAndNames", "--dst-user", UserId,
      "--dst-password", Password)

    futureResult(Sync.syncProcess(factory, options)).successfulOperations should be(1)
    import collection.JavaConverters._
    val events = getAllServeEvents.asScala
    val putRequest = events.find(event => event.getRequest.getMethod == RequestMethod.PUT).get.getRequest
    val (parent, fileUri) = UriEncodingHelper.splitParent(putRequest.getUrl)
    parent should be(WebDavPath)
    decryptName(CryptPassword, fileUri) should be(FileName)
    val bodyPlain = crypt(CryptPassword, DecryptOpHandler, ByteString(putRequest.getBody))
    bodyPlain.utf8String should be(Content)
  }

  it should "support an encrypted WebDav destination with a complex structure" in {
    val factory = new SyncComponentsFactory
    val CryptPassword = Password
    val WebDavPath = "/encrypted"
    stubSuccess()
    stubFolderRequest(WebDavPath, "root_encrypted.xml")
    stubFolderRequest(WebDavPath + "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/", "folder_encrypted.xml")
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    createTestFile(srcFolder, "foo.txt")
    createTestFile(srcFolder, "bar.txt")
    val subFolder = Files.createDirectory(srcFolder.resolve("sub"))
    createTestFile(subFolder, "subFile.txt")
    createTestFile(subFolder, "anotherSubFile.dat")
    createTestFile(subFolder, "newSubFile.doc")
    val options = Array(srcFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--dst-encrypt-password", CryptPassword, "--dst-crypt-mode", "filesAndNames", "--dst-user", UserId,
      "--dst-password", Password, "--ignore-time-delta", Int.MaxValue.toString)

    futureResult(Sync.syncProcess(factory, options)).successfulOperations should be(1)
  }

  it should "evaluate the cache size for encrypted names" in {
    val CacheSize = 444
    val transformer = Sync.createResultTransformer(Some("secret"), cryptMode = CryptMode.Files, CacheSize)
    val cache = transformer.get.initialState
    cache.capacity should be(CacheSize)
  }

  it should "support restricting the number of operations per second" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "smallFile1.txt")
    createTestFile(srcFolder, "smallFile2.txt")
    createTestFile(srcFolder, "smallFile3.txt")
    createTestFile(srcFolder, "smallFile4.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--ops-per-second", "1")

    intercept[TimeoutException] {
      val syncFuture = Sync.syncProcess(factory, options)
      Await.ready(syncFuture, 2.seconds)
    }
  }
}
