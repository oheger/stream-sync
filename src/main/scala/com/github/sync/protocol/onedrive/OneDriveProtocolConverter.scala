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

package com.github.sync.protocol.onedrive

import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.cloudfiles.onedrive.{OneDriveJsonProtocol, OneDriveModel}
import com.github.sync.SyncTypes
import com.github.sync.protocol.FileSystemProtocolConverter

/**
  * A [[FileSystemProtocolConverter]] implementation for the OneDrive protocol.
  *
  * This implementation converts between file and folder representations of the
  * Sync model and the ones defined by the [[OneDriveModel]] module. The rather
  * comprehensive OneDrive model contains all the attributes that are needed as
  * default properties.
  */
object OneDriveProtocolConverter
  extends FileSystemProtocolConverter[String, OneDriveModel.OneDriveFile, OneDriveModel.OneDriveFolder]:
  override def elementIDFromString(strID: String): String = strID

  override def toFsFile(fileElement: SyncTypes.FsFile, name: String, useID: Boolean): OneDriveModel.OneDriveFile =
    OneDriveModel.newFile(id = if useID then fileElement.id else null, name = name, size = fileElement.size,
      info = Some(OneDriveJsonProtocol.WritableFileSystemInfo(lastModifiedDateTime = Some(fileElement.lastModified))))

  override def toFsFolder(folderElement: SyncTypes.FsFolder, name: String): OneDriveModel.OneDriveFolder =
    OneDriveModel.newFolder(name)

  override def toFileElement(file: OneDriveModel.OneDriveFile, path: String, level: Int): SyncTypes.FsFile =
    SyncTypes.FsFile(id = file.id, relativeUri = path + UriEncodingHelper.encode(file.name), level = level,
      size = file.size, lastModified = file.item.fileSystemInfo.lastModifiedDateTime)

  override def toFolderElement(folder: OneDriveModel.OneDriveFolder, path: String, level: Int): SyncTypes.FsFolder =
    SyncTypes.FsFolder(id = folder.id, relativeUri = path + UriEncodingHelper.encode(folder.name), level = level)
