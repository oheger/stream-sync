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

package com.github.sync.cli

import com.github.sync.cli.Sync.SyncResult

import java.nio.file.{Files, Path, Paths}
import java.time.Instant

object BidirectionalSyncSpec:
  /**
    * Obtains the last modified time of the given file.
    *
    * @param file the file in question
    * @return the time when this file was modified
    */
  private def fileTime(file: Path): Instant = Files.getLastModifiedTime(file).toInstant

  /**
    * Generates the name of a test file based on an index.
    *
    * @param idx the index of the test file
    * @return the name for this file
    */
  private def testFileName(idx: Int): String = s"testFile$idx.txt"

/**
  * Integration test class for bidirectional sync processes. The tests operate
  * on local folders.
  */
class BidirectionalSyncSpec extends BaseSyncSpec :

  import BidirectionalSyncSpec.*

  /**
    * Runs a stream to import the content of the given folder to the local
    * state. Returns the folder with the local state
    *
    * @param srcFolder     the local folder
    * @param dstFolder     the remote folder
    * @param optStreamName an optional name for the sync stream
    */
  private def stateImport(srcFolder: Path, dstFolder: Path, optStreamName: Option[String] = None): Path =
    val stateFolder = Files.createDirectory(createPathInDirectory("state"))
    val optionsImport = List(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--state-path", stateFolder.toAbsolutePath.toString, "--sync", "--import-state")
    val optionsStreamName = optStreamName.fold(optionsImport) { name =>
      optionsImport ::: List("--" + SyncCliStreamConfig.StreamNameOption, name)
    }
    val resultImport = futureResult(runSync(optionsStreamName))
    resultImport.failedOperations should be(0)
    stateFolder

  /**
    * Executes a stream that syncs the given structures using the specified
    * folder with the local state. Additional command line arguments can be
    * provided.
    *
    * @param srcFolder   the local folder
    * @param dstFolder   the remote folder
    * @param stateFolder the folder for the local state
    * @param args        additional command line arguments
    * @return the result of the sync stream
    */
  private def runSync(srcFolder: Path, dstFolder: Path, stateFolder: Path, args: String*): SyncResult =
    val commandLine = srcFolder.toAbsolutePath.toString :: dstFolder.toAbsolutePath.toString ::
      "--state-path" :: stateFolder.toAbsolutePath.toString :: "--sync" :: args.toList
    futureResult(runSync(commandLine))

  /**
    * Creates a sub folder for the given parent and a number of test files in
    * this folder.
    *
    * @param parent     the parent folder
    * @param folderName the name of the new sub folder
    * @param count      the number of test files in this folder
    * @param time       the timestamp to compute the times of the test files
    * @return the ''Path'' to the new folder
    */
  private def createFolderWithTestFiles(parent: Path, folderName: String, count: Int, time: Instant): Path =
    val subFolder = Files.createDirectory(parent.resolve(folderName))
    (1 to count) foreach { idx =>
      createTestFile(subFolder, testFileName(idx), fileTime = Some(time.plusSeconds(idx * 60)))
    }
    subFolder

  "A Sync stream" should "sync two structures" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val localFileTime = Instant.parse("2022-05-23T16:16:33Z")
    val file1 = createTestFile(srcFolder, "test1.txt")
    val subFolder = Files.createDirectory(srcFolder.resolve("sub"))
    val file2 = createTestFile(subFolder, "test2.txt")
    val localOverwrite = createTestFile(subFolder, "override.dat", fileTime = Some(localFileTime))
    val stateFolder = stateImport(srcFolder, dstFolder)

    createTestFile(subFolder, "newLocal.txt")
    val subFolderDst = Files.createDirectory(dstFolder.resolve("sub"))
    createTestFile(dstFolder, "test1.txt", fileTime = Some(fileTime(file1)))
    createTestFile(subFolderDst, "test2.txt", fileTime = Some(fileTime(file2)))
    val OverrideText = "Modified content of a file that was overwritten."
    val remoteOverwrite = createTestFile(subFolderDst, "override.dat", content = Some(OverrideText),
      fileTime = Some(localFileTime.plusSeconds(122)))
    val newRemoteFolder = Files.createDirectory(dstFolder.resolve("newFolder"))
    createTestFile(newRemoteFolder, "fileInNewPath.doc")

    val result = runSync(srcFolder, dstFolder, stateFolder)
    result.totalOperations should be(result.successfulOperations)
    checkFile(subFolderDst, "newLocal.txt")
    readDataFile(localOverwrite) should be(OverrideText)
    fileTime(localOverwrite) should be(fileTime(remoteOverwrite))
    val newLocalFolder = srcFolder.resolve("newFolder")
    checkFile(newLocalFolder, "fileInNewPath.doc")
  }

  it should "handle errors while executing operations" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("roFolder"))
    dstFolder.toFile.setWritable(false) shouldBe true
    val stateFolder = stateImport(srcFolder, dstFolder)
    createTestFile(srcFolder, "file1.tst")
    createTestFile(srcFolder, "file2.123")

    val result = runSync(srcFolder, dstFolder, stateFolder)
    result.totalOperations should be(2)
    result.successfulOperations should be(0)
    result.failedOperations should be(result.totalOperations)
  }

  it should "ignore a time difference below the configured threshold" in {
    val TimeDeltaThreshold = 60
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val Time = Instant.parse("2022-05-24T19:41:22.45Z")
    createTestFile(srcFolder, "replace.txt", fileTime = Some(Time), content = Some("Will change"))
    createTestFile(srcFolder, "constant.doc", fileTime = Some(Time))
    val stateFolder = stateImport(srcFolder, dstFolder)
    createTestFile(dstFolder, "replace.txt", fileTime = Some(Time.plusSeconds(TimeDeltaThreshold + 1)))
    createTestFile(dstFolder, "constant.doc", fileTime = Some(Time.plusSeconds(TimeDeltaThreshold)),
      content = Some("Will be ignored"))

    val result = runSync(srcFolder, dstFolder, stateFolder, "--ignore-time-delta", TimeDeltaThreshold.toString)
    result.totalOperations should be(1)
    checkFile(srcFolder, "replace.txt")
    checkFile(srcFolder, "constant.doc")
  }

  it should "support a dry-run mode" in {
    val BaseTime = Instant.parse("2022-05-25T19:50:20.22Z")
    val StateFileName = "my_sync_state"
    val srcFolder = Files.createDirectory(createPathInDirectory("local"))
    val dstFolder = Files.createDirectory(createPathInDirectory("remote"))
    createTestFile(srcFolder, "doc1.txt", fileTime = Some(BaseTime))
    createTestFile(srcFolder, "anotherDoc.txt", fileTime = Some(BaseTime.plusSeconds(10)))
    createTestFile(srcFolder, "superfluousFile.txt")
    val stateFolder = stateImport(srcFolder, dstFolder, optStreamName = Some(StateFileName))
    val stateFile = stateFolder.resolve(StateFileName + ".lst")
    val stateFileTime = fileTime(stateFile)
    createTestFile(srcFolder, "newDocument.txt")
    createTestFile(dstFolder, "doc1.txt", fileTime = Some(BaseTime))
    createTestFile(dstFolder, "anotherDoc.txt", fileTime = Some(BaseTime.plusSeconds(20)))

    val result = runSync(srcFolder, dstFolder, stateFolder, "--dry-run", "--stream-name", StateFileName)
    result.successfulOperations should be(3)
    fileTime(stateFile) should be(stateFileTime)
    checkFile(srcFolder, "superfluousFile.txt")
    checkFileNotPresent(dstFolder, "newDocument.txt")
  }

  it should "detect invalid sync stream parameters in mirror mode" in {
    val options = IndexedSeq("src", "dst", "--state-path", ".state", "--stream-name", "my-sync-stream")

    checkSyncOutput(options, "Invalid command line options", "--state-path", "--stream-name")
  }

  it should "detect invalid mirror stream parameters in sync mode" in {
    val options = IndexedSeq("local", "remote", "--sync-log", "my-log.log", "--switch", "--sync")

    checkSyncOutput(options, "Invalid command line options", "--sync-log", "--switch")
  }

  it should "handle more complex sync operations" in {
    val localFolder = Files.createDirectory(createPathInDirectory("local"))
    val remoteFolder = Files.createDirectory(createPathInDirectory("remote"))
    val BaseTime = Instant.parse("2022-05-26T16:26:55.12Z")
    val ConflictFileName = "fileCausingConflict.err"
    val errorLogFile = createPathInDirectory("errors.log")
    createFolderWithTestFiles(localFolder, "unchanged", 8, BaseTime)
    createFolderWithTestFiles(remoteFolder, "unchanged", 8, BaseTime)
    createFolderWithTestFiles(localFolder, "deleted", 4, BaseTime)
    val conflictFolder = createFolderWithTestFiles(localFolder, "deleteConflict", 8, BaseTime)
    val stateFolder = stateImport(localFolder, remoteFolder)
    createFolderWithTestFiles(localFolder, "newFolder", 2, BaseTime.plusSeconds(3600))
    createTestFile(conflictFolder, ConflictFileName)

    val result = runSync(localFolder, remoteFolder, stateFolder,
      "--error-log", errorLogFile.toAbsolutePath.toString)
    result.failedOperations should be(1)
    checkFile(conflictFolder, testFileName(1))
    checkFile(conflictFolder, ConflictFileName)
    val newRemoteFolder = remoteFolder.resolve("newFolder")
    checkFile(newRemoteFolder, testFileName(1))

    val errors = readDataFile(errorLogFile)
    errors should include(conflictFolder.getFileName.toString)
    errors should include(ConflictFileName)
  }

  it should "initialize the local structure from the remote one" in {
    val localFolder = Files.createDirectory(createPathInDirectory("empty_local"))
    val remoteFolder = Files.createDirectory(createPathInDirectory("remote_to_import"))
    val BaseTime = Instant.parse("2022-05-27T19:54:57.88Z")
    val DataFile = "important-data.doc"
    val DataFolder = "more-data"
    createTestFile(remoteFolder, DataFile)
    createFolderWithTestFiles(remoteFolder, DataFolder, 8, BaseTime)
    val stateFolder = testDirectory.resolve(Paths.get("deeply", "nested", "state", "folder"))

    val resultInit = runSync(localFolder, remoteFolder, stateFolder)
    resultInit.successfulOperations should be(10)
    checkFile(localFolder, DataFile)
    val localDataFolder = localFolder.resolve(DataFolder)
    val modifiedFileName = testFileName(2)
    checkFile(localDataFolder, modifiedFileName)
    val localModifiedFile = localDataFolder.resolve(modifiedFileName)
    val ModifiedContent = "This file has been changed locally."
    Files.delete(localModifiedFile)
    createTestFile(localDataFolder, modifiedFileName, content = Some(ModifiedContent))

    val result2 = runSync(localFolder, remoteFolder, stateFolder)
    result2.successfulOperations should be(1)
    readDataFile(remoteFolder.resolve(DataFolder).resolve(modifiedFileName)) should be(ModifiedContent)
  }
