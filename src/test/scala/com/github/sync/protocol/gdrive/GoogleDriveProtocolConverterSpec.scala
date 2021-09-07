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

package com.github.sync.protocol.gdrive

import com.github.cloudfiles.gdrive.GoogleDriveModel
import com.github.sync.SyncTypes
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/**
  * Test class for ''GoogleDriveProtocolConverter''.
  */
class GoogleDriveProtocolConverterSpec extends AnyFlatSpec with Matchers {
  "GoogleDriveProtocolConverter" should "extract an element ID from a string" in {
    val StrID = "File-ID-123456789"

    GoogleDriveProtocolConverter.elementIDFromString(StrID) should be(StrID)
  }

  it should "convert a sync folder to a GoogleDrive folder" in {
    val syncFolder = SyncTypes.FsFolder("someID", "/some/path", 2)
    val FolderName = "theTestFolder"
    val ExpGoogleFolder = GoogleDriveModel.newFolder(name = FolderName)

    val googleFolder = GoogleDriveProtocolConverter.toFsFolder(syncFolder, FolderName)
    googleFolder should be(ExpGoogleFolder)
  }

  it should "convert a sync file to a GoogleDrive file" in {
    val FileLastModified = Instant.parse("2021-09-07T10:21:59.147Z")
    val FileName = "MyTestFile.txt"
    val syncFile = SyncTypes.FsFile("someID", "/some/path", 3, FileLastModified, 128654)
    val ExpGoogleFile = GoogleDriveModel.newFile(name = FileName, size = syncFile.size,
      lastModifiedAt = FileLastModified)

    val googleFile = GoogleDriveProtocolConverter.toFsFile(syncFile, FileName, useID = false)
    googleFile should be(ExpGoogleFile)
  }

  it should "convert a sync file to a GoogleDrive file with an ID" in {
    val FileID = "testFileID"
    val FileLastModified = Instant.parse("2021-09-07T10:27:48.144Z")
    val FileName = "MyTestFileWithID.txt"
    val syncFile = SyncTypes.FsFile(FileID, "/some/path", 3, FileLastModified, 222222)
    val ExpGoogleFile = GoogleDriveModel.newFile(name = FileName, size = syncFile.size,
      lastModifiedAt = FileLastModified, id = FileID)

    val googleFile = GoogleDriveProtocolConverter.toFsFile(syncFile, FileName, useID = true)
    googleFile should be(ExpGoogleFile)
  }

  it should "convert a GoogleDrive folder to a sync folder" in {
    val FolderPath = "/the/test/folder/path/"
    val FolderName = "the test folder"
    val FolderID = "1236547897"
    val FolderLastModified = Instant.parse("2021-09-07T10:33:52.987Z")
    val FolderLevel = 4
    val googleFolder = GoogleDriveModel.newFolder(name = FolderName, id = FolderID,
      lastModifiedAt = FolderLastModified)
    val ExpSyncFolder = SyncTypes.FsFolder(FolderID, FolderPath + "the%20test%20folder", FolderLevel)

    val syncFolder = GoogleDriveProtocolConverter.toFolderElement(googleFolder, FolderPath, FolderLevel)
    syncFolder should be(ExpSyncFolder)
  }

  it should "convert a GoogleDrive file to a sync file" in {
    val FilePath = "/the/test/path/"
    val FileName = "my test file.doc"
    val FileID = "file-987654123"
    val FileLastModified = Instant.parse("2021-09-07T18:08:44.587Z")
    val FileSize = 987456321
    val FileLevel = 3
    val googleFile = GoogleDriveModel.newFile(name = FileName, id = FileID, lastModifiedAt = FileLastModified,
      size = FileSize)
    val ExpSyncFile = SyncTypes.FsFile(FileID, FilePath + "my%20test%20file.doc", FileLevel, FileLastModified,
      FileSize)

    val syncFile = GoogleDriveProtocolConverter.toFileElement(googleFile, FilePath, FileLevel)
    syncFile should be(ExpSyncFile)
  }
}
