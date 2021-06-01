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

package com.github.sync.onedrive

import akka.http.scaladsl.model.Uri
import com.github.sync.http.SyncNoAuth
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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
class OneDriveConfigSpec extends AnyFlatSpec with Matchers {

  import OneDriveConfigSpec._

  "OneDriveConfig" should "generate a URI from all components" in {
    val ServerUri = "http://www.my-drive.com"
    val config = OneDriveConfig(DriveID, SyncPath, 1, 5.minutes, SyncNoAuth, optServerUri = Some(ServerUri))

    config.rootUri should be(Uri(ServerUri + "/" + DriveID + "/root:" + SyncPath))
  }

  it should "generate a URI with the default server URI" in {
    val config = OneDriveConfig(DriveID, SyncPath, 1, 5.minutes, SyncNoAuth)

    config.rootUri should be(Uri(OneDriveConfig.OneDriveServerUri + "/" + DriveID + "/root:" + SyncPath))
  }

  it should "handle a sync path not starting with a slash" in {
    val config = OneDriveConfig(DriveID, SyncPath drop 1, 1, 5.minutes, SyncNoAuth)

    config.rootUri should be(Uri(OneDriveConfig.OneDriveServerUri + "/" + DriveID + "/root:" + SyncPath))
  }

  it should "handle a server URI with a trailing slash" in {
    val ServerUri = "http://www.my-drive.com/"
    val config = OneDriveConfig(DriveID, SyncPath, 1, 5.minutes, SyncNoAuth, optServerUri = Some(ServerUri))

    config.rootUri should be(Uri(ServerUri + DriveID + "/root:" + SyncPath))
  }

  it should "set the upload chunk size in MB" in {
    val config = OneDriveConfig(DriveID, SyncPath, 1, 5.minutes, SyncNoAuth)

    config.uploadChunkSize should be(1024 * 1024)
  }

  it should "generate a correct drive root URI" in {
    val ServerUri = "http://www.my-drive.com"
    val config = OneDriveConfig(DriveID, SyncPath, 1, 5.minutes, SyncNoAuth, optServerUri = Some(ServerUri))

    config.driveRootUri should be(Uri(ServerUri + "/" + DriveID))
  }

  it should "resolve relative URIs to absolute URIs" in {
    val ServerUri = "http://www.test-uri.org"
    val relUri = "/path/file.txt"
    val expUri = Uri(s"$ServerUri/$DriveID/root:$SyncPath$relUri")
    val config = OneDriveConfig(DriveID, SyncPath, 1, 5.minutes, SyncNoAuth, optServerUri = Some(ServerUri))

    config resolveRelativeUri relUri should be(expUri)
  }

  it should "resolve a URI for the items resource" in {
    val relUri = "/a test/path/elem.txt"
    val expUri = Uri(s"${OneDriveConfig.OneDriveServerUri}/$DriveID/items/root:$SyncPath/a%20test/path/elem.txt")
    val config = OneDriveConfig(DriveID, SyncPath, 1, 1.minute, SyncNoAuth)

    config resolveItemsUri relUri should be(expUri)
  }

  it should "handle a sync path that does not start with a slash" in {
    val SyncPathNoSlash = SyncPath drop 1
    val relUri = "/foo/bar/baz.txt"
    val expUri = Uri(s"${OneDriveConfig.OneDriveServerUri}/$DriveID/items/root:$SyncPath$relUri")
    val config = OneDriveConfig(DriveID, SyncPathNoSlash, 1, 1.minute, SyncNoAuth)

    config.syncPath should be(SyncPath)
    config resolveItemsUri relUri should be(expUri)
  }

  it should "resolve a URI to a folder's children" in {
    val relUri = "my/special/test folder"
    val expUri = Uri(s"${OneDriveConfig.OneDriveServerUri}/$DriveID/root:$SyncPath/my/special/test%20folder:/children")
    val config = OneDriveConfig(DriveID, SyncPath, 1, 1.minute, SyncNoAuth)

    config resolveFolderChildrenUri relUri should be(expUri)
  }

  it should "resolve a URI to the root folder's children" in {
    val expUri = Uri(s"${OneDriveConfig.OneDriveServerUri}/$DriveID/root:$SyncPath:/children")
    val config = OneDriveConfig(DriveID, SyncPath, 1, 1.minute, SyncNoAuth)

    config resolveFolderChildrenUri "" should be(expUri)
  }
}
