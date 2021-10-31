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

package com.github.sync.cli

import akka.util.ByteString
import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.sync.cli.LocalSyncSpec.encodePath
import com.github.sync.cli.SyncSetup.ProtocolFactorySetupFunc
import com.github.sync.protocol.{SyncProtocol, SyncProtocolFactory}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{verify, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatestplus.mockito.MockitoSugar

import java.io.File
import java.nio.file.{Files, Path}
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}

object LocalSyncSpec:
  /**
    * Returns a string representation of the element ID referring to the given
    * path.
    *
    * @param p the path
    * @return the encoded string for this path
    */
  private def encodePath(p: Path): String = UriEncodingHelper encode p.toString

/**
  * Integration test class for sync processes that mainly deals with operations
  * on the local file system.
  */
class LocalSyncSpec extends BaseSyncSpec with MockitoSugar:
  "Sync" should "synchronize two directory structures" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "test1.txt")
    createTestFile(srcFolder, "test2.txt")
    createTestFile(srcFolder, "ignored.tmp")
    createTestFile(dstFolder, "toBeRemoved.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--filter", "exclude:*.tmp")

    val result = futureResult(runSync(options))
    result.totalOperations should be(result.successfulOperations)
    checkFile(dstFolder, "test1.txt")
    checkFileNotPresent(dstFolder, "toBeRemoved.txt")
    checkFileNotPresent(dstFolder, "ignored.tmp")
  }

  it should "read parameters from files" in {
    val filterFileContent =
      """
        |--filter
        |exclude:*.tmp""".stripMargin
    val filterFile = createDataFile(content = filterFileContent)
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "test1.txt")
    createTestFile(srcFolder, "test2.txt")
    createTestFile(srcFolder, "ignored.tmp")
    createTestFile(dstFolder, "toBeRemoved.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--file", filterFile.toAbsolutePath.toString)

    val result = futureResult(runSync(options))
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
      "-f", "exclude:*.tmp")
    val sync = createSync()

    sync.run(options)
    checkFile(dstFolder, "test1.txt")
    checkFileNotPresent(dstFolder, "toBeRemoved.txt")
    checkFileNotPresent(dstFolder, "ignored.tmp")
  }

  it should "generate a usage message if no structure URIs are passed in" in {
    val options = Array("--filter", "exclude:*.tmp", "--foo", "bar")

    val output = checkSyncOutput(options, "<sourceURI>", "<destinationURI>",
      SyncParameterManager.DryRunOption, SyncParameterManager.TimeoutOption,
      SyncParameterManager.LogFileOption, FilterManager.ArgCommonFilter, FilterManager.ArgCreateFilter,
      "--" + CliActorSystemLifeCycle.FileOption)
    output should not include "src-" + SyncCliStructureConfig.PropOneDrivePath
    output should not include "src-" + SyncCliStructureConfig.PropDavDeleteBeforeOverride
  }

  it should "generate a usage message with the options for the local file system" in {
    val options = Array("src/directory", "dav:directory", "--filter", "exclude:*.tmp", "--help")

    val output = checkSyncOutput(options, "src-" + SyncCliStructureConfig.PropLocalFsTimeZone)
    output should not include "dst-" + SyncCliStructureConfig.PropLocalFsTimeZone
    output should not include "src-" + SyncCliStructureConfig.PropOneDrivePath
    output should not include "src-" + SyncCliStructureConfig.PropDavDeleteBeforeOverride
    output should not include "Invalid"
  }

  it should "generate a usage and error message if unsupported options are passed in" in {
    val options = Array("src/directory", "dst/targetFolder", "--unknown", "yes",
      "--filter", "exclude:*.tmp", "--foo", "bar")

    checkSyncOutput(options, "unknown", "foo", "Invalid command line options")
  }

  it should "display parameter alias names in the usage message" in {
    val options = Array("src/directory", "dst/folder", "-h")

    checkSyncOutput(options, "--filter, -f", "--help, -h")
  }

  it should "store sync operations in a log file" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val logFile = createFileReference()
    val createFile = createTestFile(srcFolder, "create.txt")
    createTestFile(srcFolder, "ignored.tmp")
    val removeFile = createTestFile(dstFolder, "removed.txt")
    val removeFileID = UriEncodingHelper encode removeFile.toString
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--filter", "exclude:*.tmp", "--log", logFile.toAbsolutePath.toString)

    val result = futureResult(runSync(options))
    result.totalOperations should be(2)
    result.successfulOperations should be(2)
    val lines = Files.readAllLines(logFile)
    lines.get(0) should include(s"CREATE 0 - FILE ${UriEncodingHelper encode createFile.toString} %2Fcreate.txt 0")
    lines.get(1) should include(s"REMOVE 0 $removeFileID FILE $removeFileID %2Fremoved.txt 0")
    checkFileNotPresent(dstFolder, "removed.txt")
  }

  it should "append an existing log file" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val LogHeader = "This is my log." + System.lineSeparator()
    val logFile = createDataFile(content = LogHeader)
    createTestFile(srcFolder, "fileToSync.dat")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "-d", "-l", logFile.toAbsolutePath.toString)

    futureResult(runSync(options))
    readDataFile(logFile) should startWith(LogHeader)
  }

  it should "support a dry-run mode" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "file1.txt")
    createTestFile(srcFolder, "file2.txt")
    createTestFile(dstFolder, "removed.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString, "-d")

    val result = futureResult(runSync(options))
    result.successfulOperations should be(3)
    result.totalOperations should be(result.successfulOperations)
    checkFile(dstFolder, "removed.txt")
    checkFileNotPresent(dstFolder, "file1.txt")
  }

  it should "execute sync operations from a sync log file" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val NewFolderName = "newFolder"
    val RemoveFileName = "killed.txt"
    val lastModified = Instant.parse("2018-09-12T21:06:00.10Z")
    val createFile = createTestFile(srcFolder, "syncFile.txt")
    createTestFile(srcFolder, "otherFile.dat")
    val removeFile = createTestFile(dstFolder, RemoveFileName)
    createTestFile(dstFolder, "remaining.txt")
    val procLog = createPathInDirectory("processed.log")
    val operations = List(
      s"CREATE 0 - FILE ${encodePath(createFile)} /syncFile.txt 0 $lastModified 42",
      s"CREATE 0 - FOLDER $NewFolderName /$NewFolderName 0",
      s"REMOVE 0 ${encodePath(removeFile)} FILE $RemoveFileName /$RemoveFileName 0 2018-09-12T21:12:45.00Z 10")
    val syncLogFile = createDataFile(content = operations.mkString("\n"))
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--sync-log", syncLogFile.toAbsolutePath.toString, "--log", procLog.toAbsolutePath.toString)

    val result = futureResult(runSync(options))
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
    val successPath = createTestFile(srcFolder, SuccessFile)
    val operations = List("not a valid sync operation!?",
      s"CREATE 0 null FILE ${encodePath(successPath)} /$SuccessFile 0 $lastModified 42")
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
    val successPath = createTestFile(srcFolder, SuccessFile)
    val operations = List(s"OVERRIDE 0 nonExisting.dat FILE f1 /nonExisting.file 0 $lastModified 10",
      s"CREATE 0 null FILE ${encodePath(successPath)} /$SuccessFile 0 $lastModified 42")
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
    val successPath = createTestFile(srcFolder, SuccessFile)
    val operations = List(s"OVERRIDE 0 $FailedFile FILE $FailedFile /$FailedFile 0 $lastModified 10",
      s"CREATE 0 null FILE ${encodePath(successPath)} /$SuccessFile 0 $lastModified 42")
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
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val ProcessedFile = "done.txt"
    val NewFile = "copy.txt"
    val lastModified = Instant.parse("2018-09-13T19:00:01.11Z")
    val processedPath = createTestFile(srcFolder, ProcessedFile)
    val newPath = createTestFile(srcFolder, NewFile)
    val ProcessedOp = s"CREATE 0 null FILE ${encodePath(processedPath)} /$ProcessedFile 0 $lastModified 2"
    val operations = List(ProcessedOp, s"CREATE 0 null FILE ${encodePath(newPath)} /$NewFile 0 $lastModified 4")
    val syncLogFile = createDataFile(content = operations.mkString("\n"))
    val logFile = createDataFile(content = ProcessedOp)
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--sync-log", syncLogFile.toAbsolutePath.toString, "--log", logFile.toAbsolutePath.toString)

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    checkFile(dstFolder, NewFile)
    checkFileNotPresent(dstFolder, ProcessedFile)
  }

  it should "properly close the protocols after a sync process" in {
    def createMockProtocol(): SyncProtocol =
      val protocol = mock[SyncProtocol]
      when(protocol.readRootFolder()).thenReturn(Future.successful(Nil))
      protocol

    val srcFolder = Files.createDirectory(createPathInDirectory("source")).toAbsolutePath
    val dstFolder = Files.createDirectory(createPathInDirectory("dest")).toAbsolutePath
    val srcProtocol = createMockProtocol()
    val dstProtocol = createMockProtocol()
    val protocolFactory = mock[SyncProtocolFactory]
    when(protocolFactory.createProtocol(anyString(), any())).thenAnswer((invocation: InvocationOnMock) => {
      val uri = invocation.getArgument[String](0)
      if uri == dstFolder.toString then dstProtocol else srcProtocol
    })
    val protocolSetupFunc: ProtocolFactorySetupFunc = (_, _, _, _) => protocolFactory
    val options = Array(srcFolder.toString, dstFolder.toString)

    futureResult(runSync(options, optProtocolSetupFunc = Some(protocolSetupFunc)))
    verify(srcProtocol).close()
    verify(dstProtocol).close()
  }

  it should "take the time zone of a local files source into account" in {
    val DeltaHours = 2
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

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    val operations = Files.readAllLines(logFile)
    operations.get(0) should include(ChangedFile)
    val updatedTime = Files.getLastModifiedTime(changedPath).toInstant
    updatedTime should be(Time2.minus(DeltaHours, ChronoUnit.HOURS))
  }

  it should "ignore a time difference below the configured threshold" in {
    val TimeDeltaThreshold = 10
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

    val result = futureResult(runSync(options))
    result.totalOperations should be(0)
  }

  it should "support encryption of files in a destination structure" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val TestFileName = "TestFileToBeEncrypted.txt"
    createTestFile(srcFolder, TestFileName)
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--dst-encrypt-password", "!encryptDest!", "--dst-crypt-mode", "files")

    val result = futureResult(runSync(options))
    result.totalOperations should be(result.successfulOperations)
    readBinaryFileInPath(srcFolder, TestFileName) should not be readBinaryFileInPath(dstFolder, TestFileName)
  }

  it should "support a round-trip with encryption and decryption of files" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder1 = Files.createDirectory(createPathInDirectory("destEnc"))
    val dstFolder2 = Files.createDirectory(createPathInDirectory("destPlain"))
    val TestFileName = "TestFileToBeEncryptedAndDecrypted.txt"
    val Password = "privacy"
    createTestFile(srcFolder, TestFileName)
    val options1 = Array(srcFolder.toAbsolutePath.toString, dstFolder1.toAbsolutePath.toString,
      "--dst-encrypt-password", Password, "--dst-crypt-mode", "files")
    futureResult(runSync(options1))

    val options2 = Array(dstFolder1.toAbsolutePath.toString, dstFolder2.toAbsolutePath.toString,
      "--src-encrypt-password", Password, "--src-crypt-mode", "files")
    val result = futureResult(runSync(options2))
    result.totalOperations should be(result.successfulOperations)
    readFileInPath(srcFolder, TestFileName) should be(readFileInPath(dstFolder2, TestFileName))
  }

  it should "correctly calculate the sizes of encrypted files" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val TestFileName = "TestFileToBeEncrypted.txt"
    createTestFile(srcFolder, TestFileName)
    val Password = "let's_crypt"
    val options1 = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--dst-encrypt-password", Password, "--dst-crypt-mode", "files")
    val options2 = Array(dstFolder.toAbsolutePath.toString, srcFolder.toAbsolutePath.toString,
      "--src-encrypt-password", Password, "--src-crypt-mode", "files")
    futureResult(runSync(options1))

    val result1 = futureResult(runSync(options1))
    result1.totalOperations should be(0)
    val result2 = futureResult(runSync(options2))
    result2.totalOperations should be(0)
  }

  it should "support encrypted file names in a destination structure" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val TestFileName = "TestFileToBeEncryptedAndScrambled.txt"
    createTestFile(srcFolder, TestFileName)
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--dst-encrypt-password", "crYptiC", "--dst-crypt-mode", "FilesAndNames")

    val result = futureResult(runSync(options))
    result.totalOperations should be(result.successfulOperations)
    val destFiles = dstFolder.toFile.list()
    destFiles should have length 1
    destFiles.head should not be TestFileName
  }

  it should "support other operations on encrypted file names" in {
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

    val result = futureResult(runSync(options))
    result.totalOperations should be(result.successfulOperations)
    Files.exists(delFolder) shouldBe false
    val overrideData = ByteString(Files.readAllBytes(pathOverride))
    decrypt(Password, overrideData).utf8String should be(OverrideName)
  }

  it should "support a round-trip with encrypted file names" in {
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
    futureResult(runSync(options1))

    val options2 = Array(dstFolder1.toAbsolutePath.toString, dstFolder2.toAbsolutePath.toString,
      "--src-encrypt-password", Password, "--src-crypt-mode", "filesAndNames")
    futureResult(runSync(options2))
    checkFile(dstFolder2, "top.txt")
    val options3 = Array(srcFolder.toAbsolutePath.toString, dstFolder2.toAbsolutePath.toString)
    val result = futureResult(runSync(options3))
    result.totalOperations should be(0)
  }

  it should "support complex structures when syncing with encrypted file names" in {
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
    futureResult(runSync(options)).successfulOperations should be(5)

    createTestFile(subSubDir, "deep2.txt")
    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)

    def findDirectory(content: Array[File]): File =
      val dirs = content.filter(_.isDirectory)
      dirs should have length 1
      dirs.head

    val topContent = dstFolder.toFile.listFiles()
    topContent should have length 2
    val subContent = findDirectory(topContent).listFiles()
    subContent should have length 2
    val subSubContent = findDirectory(subContent).listFiles()
    subSubContent should have length 2
  }

  it should "support restricting the number of operations per second" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "smallFile1.txt")
    createTestFile(srcFolder, "smallFile2.txt")
    createTestFile(srcFolder, "smallFile3.txt")
    createTestFile(srcFolder, "smallFile4.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--ops-per-second", "1")

    intercept[TimeoutException] {
      val syncFuture = runSync(options)
      Await.ready(syncFuture, 2.seconds)
    }
  }

  it should "log failed sync operations" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val FailedFile = "nonExisting.file"
    val lastModified = Instant.parse("2021-07-15T20:10:02.12Z")
    val operations = List(s"OVERRIDE 0 $FailedFile FILE $FailedFile /$FailedFile 0 $lastModified 10")
    val syncLogFile = createDataFile(content = operations.mkString("\n"))
    val logFile = createFileReference()
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--sync-log", syncLogFile.toAbsolutePath.toString, "--log", logFile.toAbsolutePath.toString,
      "--timeout", "2")

    val log = runSyncAndCaptureLogs(options)
    log should include("Failed to apply operation")
    log should include(FailedFile)
  }

  it should "log all sync operations in debug level" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val f1 = createTestFile(srcFolder, "test1.txt")
    val f2 = createTestFile(srcFolder, "test2.txt")
    val f3 = createTestFile(dstFolder, "toBeRemoved.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString, "--debug")

    val log = runSyncAndCaptureLogs(options)
    List(f1, f2, f3) foreach { file =>
      log should include(file.getFileName.toString)
    }
  }

  it should "not log sync operations in info level" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val f = createTestFile(srcFolder, "test1.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString, "--info")

    val log = runSyncAndCaptureLogs(options)
    log should not include f.getFileName.toString
  }

  it should "log the folders currently processed in info level" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "test1.txt")
    val srcSubFolder1 = Files.createDirectory(srcFolder.resolve("sub"))
    val srcSubFolder2 = Files.createDirectory(srcSubFolder1.resolve("anotherSub"))
    val dstSubFolder = Files.createDirectory(dstFolder.resolve("sub"))
    createTestFile(dstSubFolder, "dest.dat")
    createTestFile(srcSubFolder1, "src.dat")
    createTestFile(srcSubFolder2, "moreData.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString, "--info")

    val log = runSyncAndCaptureLogs(options)
    List(srcSubFolder1, srcSubFolder2) foreach { folder =>
      log should include("/" + folder.getFileName)
    }
  }

  it should "log data about overridden files in debug level" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val UnchangedFile = "notChanged.txt"
    val ChangedFile = "modifiedTime.txt"
    val Time1 = Instant.parse("2021-07-17T14:25:04.01Z")
    val Time2 = Instant.parse("2021-07-17T14:29:25.11Z")
    val Time3 = Instant.parse("2021-07-17T14:29:35.11Z")
    createTestFile(srcFolder, UnchangedFile, fileTime = Some(Time1))
    createTestFile(dstFolder, UnchangedFile, fileTime = Some(Time1))
    createTestFile(srcFolder, ChangedFile, fileTime = Some(Time2))
    createTestFile(dstFolder, ChangedFile, fileTime = Some(Time3))
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString, "--debug")

    val log = runSyncAndCaptureLogs(options)
    log should not include Time1.toString
    log should include(Time2.toString)
    log should include(Time3.toString)
  }
