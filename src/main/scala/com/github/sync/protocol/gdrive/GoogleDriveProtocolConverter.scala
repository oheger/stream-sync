/*
 * Copyright 2018-2024 The Developers Team.
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

import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.cloudfiles.gdrive.GoogleDriveModel
import com.github.sync.SyncTypes
import com.github.sync.protocol.FileSystemProtocolConverter

/**
  * A [[FileSystemProtocolConverter]] implementation for the OneDrive protocol.
  *
  * This implementation converts between file and folder representations of the
  * Sync model and the ones defined by the [[GoogleDriveModel]] module. For the
  * properties needed, there is a direct correlation between the two models.
  */
object GoogleDriveProtocolConverter
  extends FileSystemProtocolConverter[String, GoogleDriveModel.GoogleDriveFile, GoogleDriveModel.GoogleDriveFolder]:
  override def elementIDFromString(strID: String): String = strID

  override def toFsFile(fileElement: SyncTypes.FsFile, name: String, useID: Boolean): GoogleDriveModel.GoogleDriveFile =
    GoogleDriveModel.newFile(name = name, lastModifiedAt = fileElement.lastModified, size = fileElement.size,
      id = if useID then fileElement.id else null)

  override def toFsFolder(folderElement: SyncTypes.FsFolder, name: String): GoogleDriveModel.GoogleDriveFolder =
    GoogleDriveModel.newFolder(name)

  override def toFileElement(file: GoogleDriveModel.GoogleDriveFile, path: String, level: Int): SyncTypes.FsFile =
    SyncTypes.FsFile(id = file.id, lastModified = file.lastModifiedAt, size = file.size, level = level,
      relativeUri = path + UriEncodingHelper.encode(file.name))

  override def toFolderElement(folder: GoogleDriveModel.GoogleDriveFolder, path: String, level: Int):
  SyncTypes.FsFolder =
    SyncTypes.FsFolder(id = folder.id, relativeUri = path + UriEncodingHelper.encode(folder.name), level = level)
