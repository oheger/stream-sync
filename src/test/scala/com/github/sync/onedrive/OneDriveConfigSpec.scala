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

package com.github.sync.onedrive

import akka.http.scaladsl.model.Uri
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

object OneDriveConfigSpec {
  /** A drive ID used by tests. */
  private val DriveID = "My-Test-Drive"

  /** A test relative path for sync operations. */
  private val SyncPath = "/sync/data"
}

/**
  * Test class for ''OneDriveConfig''.
  */
class OneDriveConfigSpec extends FlatSpec with Matchers {

  import OneDriveConfigSpec._

  "OneDriveConfig" should "generate a URI from all components" in {
    val ServerUri = "http://www.my-drive.com"
    val config = OneDriveConfig(DriveID, SyncPath, 1, 5.minutes, None, optServerUri = Some(ServerUri))

    config.rootUri should be(Uri(ServerUri + "/" + DriveID + "/root:" + SyncPath))
  }

  it should "generate a URI with the default server URI" in {
    val config = OneDriveConfig(DriveID, SyncPath, 1, 5.minutes, None)

    config.rootUri should be(Uri(OneDriveConfig.OneDriveServerUri + "/" + DriveID + "/root:" + SyncPath))
  }

  it should "handle a sync path not starting with a slash" in {
    val config = OneDriveConfig(DriveID, SyncPath drop 1, 1, 5.minutes, None)

    config.rootUri should be(Uri(OneDriveConfig.OneDriveServerUri + "/" + DriveID + "/root:" + SyncPath))
  }

  it should "handle a server URI with a trailing slash" in {
    val ServerUri = "http://www.my-drive.com/"
    val config = OneDriveConfig(DriveID, SyncPath, 1, 5.minutes, None, optServerUri = Some(ServerUri))

    config.rootUri should be(Uri(ServerUri + DriveID + "/root:" + SyncPath))
  }

  it should "set the upload chunk size in MB" in {
    val config = OneDriveConfig(DriveID, SyncPath, 1, 5.minutes, None)

    config.uploadChunkSize should be(1024 * 1024)
  }

  it should "generate a correct drive root URI" in {
    val ServerUri = "http://www.my-drive.com"
    val config = OneDriveConfig(DriveID, SyncPath, 1, 5.minutes, None, optServerUri = Some(ServerUri))

    config.driveRootUri should be(ServerUri + "/" + DriveID)
  }
}