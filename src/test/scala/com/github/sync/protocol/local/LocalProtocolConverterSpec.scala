/*
 * Copyright 2018-2022 The Developers Team.
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

package com.github.sync.protocol.local

import com.github.cloudfiles.localfs.LocalFsModel
import com.github.sync.SyncTypes
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths
import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneId}

object LocalProtocolConverterSpec:
  /** A path used by some test cases. */
  private val TestPath = Paths.get("this", "is", "a", "test", "path")

  /** A string representation of the test path. */
  private val TestPathStr = TestPath.toString + "/"

  /** Test modified time for files or folders. */
  private val ModifiedTime = Instant.parse("2021-05-27T19:31:44.000Z")

  /** Test file size. */
  private val FileSize = 20210527

  /** Test level for sync elements. */
  private val Level = 5

/**
  * Test class for ''LocalProtocolConverter''.
  */
class LocalProtocolConverterSpec extends AnyFlatSpec with Matchers:

  import LocalProtocolConverterSpec._

  "LocalProtocolConverter" should "convert a string to an element ID" in {
    val idStr = TestPath.toString
    val converter = new LocalProtocolConverter(None)

    converter.elementIDFromString(idStr) should be(TestPath)
  }

  it should "convert a sync file to an FS file" in {
    val FileName = "testFile.dat"
    val syncFile = SyncTypes.FsFile(TestPath.toString, "/some/uri", 0, ModifiedTime, FileSize)
    val converter = new LocalProtocolConverter(None)

    val fsFile = converter.toFsFile(syncFile, FileName, useID = true)
    fsFile.id should be(TestPath)
    fsFile.name should be(FileName)
    fsFile.lastModifiedUpdate should be(Some(ModifiedTime))
  }

  it should "optionally ignore the ID when converting a sync file to an FS file" in {
    val syncFile = SyncTypes.FsFile("someID", "/some/uri", 0, ModifiedTime, FileSize)
    val converter = new LocalProtocolConverter(None)

    val fsFile = converter.toFsFile(syncFile, "someName.tst", useID = false)
    fsFile.id should be(null)
  }

  it should "convert a sync folder to an FS folder" in {
    val FolderName = "aFolder"
    val syncFolder = SyncTypes.FsFolder(TestPath.toString, "/some/uri", 0)
    val converter = new LocalProtocolConverter(None)

    val fsFolder = converter.toFsFolder(syncFolder, FolderName)
    fsFolder.name should be(FolderName)
  }

  it should "convert an FS file to a sync file" in {
    val FileName = "a File.txt"
    val EncFileName = "a%20File.txt"
    val fsFile = LocalFsModel.LocalFile(id = TestPath.resolve(FileName), name = FileName, createdAt = Instant.now(),
      lastModifiedAt = ModifiedTime, size = FileSize)
    val expSyncFile = SyncTypes.FsFile(id = fsFile.id.toString, relativeUri = TestPathStr + EncFileName, level = Level,
      lastModified = ModifiedTime, size = FileSize)
    val converter = new LocalProtocolConverter(None)

    val syncFile = converter.toFileElement(fsFile, TestPathStr, Level)
    syncFile should be(expSyncFile)
  }

  it should "convert an FS folder to a sync folder" in {
    val FolderName = "a Sync Folder"
    val EncFolderName = "a%20Sync%20Folder"
    val fsFolder = LocalFsModel.LocalFolder(id = TestPath.resolve(FolderName), name = FolderName,
      createdAt = Instant.now(), lastModifiedAt = ModifiedTime)
    val expSyncFolder = SyncTypes.FsFolder(id = fsFolder.id.toString, relativeUri = TestPathStr + EncFolderName,
      level = Level)
    val converter = new LocalProtocolConverter(None)

    val syncFolder = converter.toFolderElement(fsFolder, TestPathStr, Level)
    syncFolder should be(expSyncFolder)
  }

  it should "apply the time zone when converting an FS file to a sync file" in {
    val timeZone = ZoneId.of("UTC+01:00")
    val FileName = "fileInZone.doc"
    val fsFile = LocalFsModel.LocalFile(id = TestPath.resolve(FileName), name = FileName, createdAt = Instant.now(),
      lastModifiedAt = ModifiedTime, size = FileSize)
    val converter = new LocalProtocolConverter(Some(timeZone))

    val syncFile = converter.toFileElement(fsFile, TestPathStr, Level)
    syncFile.lastModified should be(ModifiedTime.plus(1, ChronoUnit.HOURS))
  }

  it should "apply the time zone when converting a sync file to an FS file" in {
    val timeZone = ZoneId.of("UTC+02:00")
    val FileName = "testFileInZone.dat"
    val syncFile = SyncTypes.FsFile(TestPath.toString, "/some/uri", 0, ModifiedTime, FileSize)
    val converter = new LocalProtocolConverter(Some(timeZone))

    val fsFile = converter.toFsFile(syncFile, FileName, useID = true)
    fsFile.lastModifiedUpdate.get should be(ModifiedTime.minus(2, ChronoUnit.HOURS))
  }
