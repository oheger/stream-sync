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

package com.github.sync.protocol.onedrive

import com.github.cloudfiles.onedrive.{OneDriveJsonProtocol, OneDriveModel}
import com.github.sync.SyncTypes
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

object OneDriveProtocolConverterSpec:
  /** A test element ID. */
  private val ElementID = "a-one-drive-element"

  /** A test path to a parent element. */
  private val ParentPath = "/path/to/parent/"

  /** A test level. */
  private val TestLevel = 11

  /** A test modified time. */
  private val LastModified = Instant.parse("2021-06-05T19:24:39Z")

  /** A test file size. */
  private val FileSize = 33445

/**
  * Test class for ''OneDriveProtocolConverter''.
  */
class OneDriveProtocolConverterSpec extends AnyFlatSpec with Matchers:

  import OneDriveProtocolConverterSpec._

  "OneDriveProtocolConverter" should "extract an element ID from a string" in {
    OneDriveProtocolConverter.elementIDFromString(ElementID) should be(ElementID)
  }

  it should "convert a Sync folder to a OneDrive folder" in {
    val FolderName = "theNewFolder"
    val syncFolder = SyncTypes.FsFolder(ElementID, "/some/relative/uri", 0)

    val fsFolder = OneDriveProtocolConverter.toFsFolder(syncFolder, FolderName)
    fsFolder.name should be(FolderName)
  }

  it should "convert a OneDrive folder to a Sync folder" in {
    val FolderName = "existing Folder"
    val EncFolderName = "existing%20Folder"
    val oneDriveFolder = OneDriveModel.newFolder(id = ElementID, name = FolderName, description = Some("a desc"))
    val expSyncFolder = SyncTypes.FsFolder(ElementID, ParentPath + EncFolderName, TestLevel)

    val syncFolder = OneDriveProtocolConverter.toFolderElement(oneDriveFolder, ParentPath, TestLevel)
    syncFolder should be(expSyncFolder)
  }

  it should "convert a Sync file to a OneDrive file" in {
    val FileName = "aNewFile.mp3"
    val syncFile = SyncTypes.FsFile(id = ElementID, relativeUri = ParentPath + FileName, level = 0, size = FileSize,
      lastModified = LastModified)

    val fsFile = OneDriveProtocolConverter.toFsFile(syncFile, FileName, useID = true)
    fsFile.id should be(ElementID)
    fsFile.name should be(FileName)
    fsFile.size should be(FileSize)
    fsFile.item.fileSystemInfo.lastModifiedDateTime should be(LastModified)
  }

  it should "optionally ignore the ID when converting a Sync file to a OneDrive file" in {
    val FileName = "aNewFile.mp3"
    val syncFile = SyncTypes.FsFile(id = ElementID, relativeUri = ParentPath + FileName, level = 0, size = FileSize,
      lastModified = LastModified)

    val fsFile = OneDriveProtocolConverter.toFsFile(syncFile, FileName, useID = false)
    fsFile.id should be(null)
    fsFile.name should be(FileName)
    fsFile.size should be(FileSize)
    fsFile.item.fileSystemInfo.lastModifiedDateTime should be(LastModified)
  }

  it should "convert a OneDrive file to a Sync file" in {
    val FileName = "file To Convert.doc"
    val EncFileName = "file%20To%20Convert.doc"
    val oneDriveFile = OneDriveModel.newFile(id = ElementID, name = FileName, size = FileSize,
      description = Some("desc"),
      info = Some(OneDriveJsonProtocol.WritableFileSystemInfo(lastModifiedDateTime = Some(LastModified))))
    val expSyncFile = SyncTypes.FsFile(id = ElementID, relativeUri = ParentPath + EncFileName, size = FileSize,
      lastModified = LastModified, level = TestLevel)

    val syncFile = OneDriveProtocolConverter.toFileElement(oneDriveFile, ParentPath, TestLevel)
    syncFile should be(expSyncFile)
  }
