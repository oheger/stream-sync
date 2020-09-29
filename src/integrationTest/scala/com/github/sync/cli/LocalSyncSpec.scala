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

package com.github.sync.cli

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorIdentity, Identify}
import akka.util.ByteString
import com.github.sync.cli.SyncParameterManager.CryptMode
import com.github.sync.crypt.DecryptOpHandler
import com.github.sync.local.LocalUriResolver

import scala.concurrent.{Await, TimeoutException}
import scala.concurrent.duration._

/**
  * Integration test class for sync processes that mainly deals with operations
  * on the local file system.
  */
class LocalSyncSpec extends BaseSyncSpec {

  import system.dispatcher

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

  it should "generate a usage message if no structure URIs are passed in" in {
    val options = Array("--filter", "exclude:*.tmp", "--foo", "bar")

    val output = checkSyncOutput(options, "<sourceURI>", "<destinationURI>",
      SyncParameterManager.ApplyModeOption, SyncParameterManager.TimeoutOption,
      SyncParameterManager.LogFileOption, FilterManager.ArgCommonFilter, FilterManager.ArgCreateFilter,
      "--" + CliActorSystemLifeCycle.FileOption)
    output should not include "src-" + SyncStructureConfig.PropOneDrivePath
    output should not include "src-" + SyncStructureConfig.PropDavDeleteBeforeOverride
  }

  it should "generate a usage message with the options for the local file system" in {
    val options = Array("src/directory", "--filter", "exclude:*.tmp", "--foo", "bar")

    val output = checkSyncOutput(options, "src-" + SyncStructureConfig.PropLocalFsTimeZone)
    output should not include "dst-" + SyncStructureConfig.PropLocalFsTimeZone
    output should not include "src-" + SyncStructureConfig.PropOneDrivePath
    output should not include "src-" + SyncStructureConfig.PropDavDeleteBeforeOverride
  }

  it should "generate a usage and error message if unsupported options are passed in" in {
    val options = Array("src/directory", "dst/targetFolder", "--unknown", "yes",
      "--filter", "exclude:*.tmp", "--foo", "bar")

    checkSyncOutput(options, "unknown", "foo", "Invalid command line options")
  }

  it should "apply operations to an alternative target" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val dstFolder2 = Files.createDirectory(createPathInDirectory("dest2"))
    createTestFile(srcFolder, "new.txt")
    createTestFile(dstFolder, "obsolete.dat")
    createTestFile(dstFolder2, "obsolete.dat")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--apply", "target:" + dstFolder2.toAbsolutePath.toString)

    futureResult(runSync(options))
    checkFile(dstFolder2, "new.txt")
    checkFile(dstFolder, "obsolete.dat")
    checkFileNotPresent(dstFolder2, "obsolete.dat")
  }

  it should "store sync operations in a log file" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val logFile = createFileReference()
    createTestFile(srcFolder, "create.txt")
    createTestFile(srcFolder, "ignored.tmp")
    createTestFile(dstFolder, "removed.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--filter", "exclude:*.tmp", "--log", logFile.toAbsolutePath.toString)

    val result = futureResult(runSync(options))
    result.totalOperations should be(2)
    result.successfulOperations should be(2)
    val lines = Files.readAllLines(logFile)
    lines.get(0) should include("CREATE 0 FILE %2Fcreate.txt 0")
    lines.get(1) should include("REMOVE 0 FILE %2Fremoved.txt 0")
    checkFileNotPresent(dstFolder, "removed.txt")
  }

  it should "append an existing log file" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val LogHeader = "This is my log." + System.lineSeparator()
    val logFile = createDataFile(content = LogHeader)
    createTestFile(srcFolder, "fileToSync.dat")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--apply", "none", "--log", logFile.toAbsolutePath.toString)

    futureResult(runSync(options))
    readDataFile(logFile) should startWith(LogHeader)
  }

  it should "support an apply mode 'None'" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "file1.txt")
    createTestFile(srcFolder, "file2.txt")
    createTestFile(dstFolder, "removed.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--apply", "NonE")

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

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    checkFile(dstFolder, NewFile)
    checkFileNotPresent(dstFolder, ProcessedFile)
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

    futureResult(runSync(options, factory))
    shutdownCount.get() should be(1)
  }

  it should "stop the actor for local sync operations after stream processing" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val identifyId = 20190817
    createTestFile(srcFolder, "test.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString)

    futureResult(runSync(options))
    val selection = system.actorSelection(s"/user/${LocalFsDestinationComponentsFactory.LocalSyncOpActorName}")
    selection ! Identify(identifyId)
    val identity = expectMsgType[ActorIdentity]
    identity.correlationId should be(identifyId)
    identity.ref.isDefined shouldBe false
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
    futureResult(runSync(options1, factory))

    val options2 = Array(dstFolder1.toAbsolutePath.toString, dstFolder2.toAbsolutePath.toString,
      "--src-encrypt-password", Password, "--src-crypt-mode", "filesAndNames")
    futureResult(runSync(options2, factory))
    checkFile(dstFolder2, "top.txt")
    val options3 = Array(srcFolder.toAbsolutePath.toString, dstFolder2.toAbsolutePath.toString)
    val result = futureResult(runSync(options3, factory))
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
    futureResult(runSync(options, factory)).successfulOperations should be(5)

    createTestFile(subSubDir, "deep2.txt")
    val result = futureResult(runSync(options, factory))
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

  it should "evaluate the cache size for encrypted names" in {
    val CacheSize = 444
    val transformer = Sync.createResultTransformer(Some("secret"), cryptMode = CryptMode.Files, CacheSize)
    val cache = transformer.get.initialState
    cache.capacity should be(CacheSize)
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
}
