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

package com.github.sync.onedrive

import spray.json.{DefaultJsonProtocol, RootJsonFormat}

/**
  * A data class representing the ''file'' element in a OneDrive JSON response
  * for a folder request. Note that the content of this element is not
  * evaluated; it is just used to distinguish between items representing files
  * or folders.
  *
  * @param mimeType the mime type of the file
  */
case class OneDriveFile(mimeType: String)

/**
  * A data class representing the ''fileSystemInfo'' element in a OneDrive JSON
  * response for a folder request. Here some additional information about files
  * is contained that is needed to populate ''FsFile'' objects.
  *
  * @param lastModifiedDateTime the timestamp of the file's last modification
  */
case class OneDriveFileSystemInfo(lastModifiedDateTime: String)

/**
  * A data class representing an item in a OneDrive JSON response for a folder
  * request. An item can represent both a file or a folder, which is a child
  * of the current folder. The class defines the sub set of information
  * relevant for sync processes.
  *
  * @param name           the name of the item
  * @param size           the size of the item
  * @param file           file-related information (available only for files)
  * @param fileSystemInfo reference to a [[OneDriveFileSystemInfo]]
  */
case class OneDriveItem(name: String,
                        size: Long,
                        file: Option[OneDriveFile],
                        fileSystemInfo: Option[OneDriveFileSystemInfo])

/**
  * A data class representing the OneDrive JSON response for a folder request.
  * Here we are only interested in the list with the child items of the
  * current folder. For large folders, the content is distributed over multiple
  * pages. In this case, a link for the next page is available.
  *
  * @param value    a list with the child items of this folder
  * @param nextLink an optional link to the next page
  */
case class OneDriveModel(value: List[OneDriveItem], nextLink: Option[String])

/**
  * A data class representing the response of a request for an upload session.
  * From this response the URL where to upload the file's content is
  * extracted.
  *
  * @param uploadUrl the upload URL for the file
  */
case class UploadSessionResponse(uploadUrl: String)

/**
  * An object defining converters for the data classes to be extracted from
  * OneDrive JSON responses.
  *
  * The implicits defined by this object must be available in the current scope
  * in order to deserialize JSON responses to the corresponding object model.
  */
object OneDriveJsonProtocol extends DefaultJsonProtocol {
  implicit val fileFormat: RootJsonFormat[OneDriveFile] = jsonFormat1(OneDriveFile)
  implicit val fileSystemFormat: RootJsonFormat[OneDriveFileSystemInfo] = jsonFormat1(OneDriveFileSystemInfo)
  implicit val itemFormat: RootJsonFormat[OneDriveItem] = jsonFormat4(OneDriveItem)
  implicit val modelFormat: RootJsonFormat[OneDriveModel] =
    jsonFormat(OneDriveModel.apply, "value", "@odata.nextLink")
  implicit val uploadSessionFormat: RootJsonFormat[UploadSessionResponse] = jsonFormat1(UploadSessionResponse)
}
