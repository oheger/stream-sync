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

package com.github.sync.cli

import java.nio.file.Files
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import com.github.sync.OAuthMockSupport.{CurrentTokenData, RefreshedTokenData}
import com.github.sync.WireMockSupport._
import com.github.sync.onedrive.{OneDriveConfig, OneDriveStubbingSupport}
import com.github.sync.{FileTestHelper, OAuthMockSupport, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock._

import scala.concurrent.ExecutionContext

class OneDriveSyncSpec extends BaseSyncSpec with WireMockSupport with OneDriveStubbingSupport with OAuthMockSupport {

  import OneDriveStubbingSupport._

  override implicit val ec: ExecutionContext = system.dispatcher

  private def stubDownloadRequest(config: OneDriveConfig, uri: String, authFunc: AuthFunc,
                                  content: String = FileTestHelper.TestData): Unit = {
    val downloadPath = "/" + UUID.randomUUID()
    stubFor(authFunc(get(urlPathEqualTo(mapElementUri(config, uri) + "/content")))
      .willReturn(aResponse().withStatus(302)
        .withHeader("Location", serverUri(downloadPath))))
    stubFor(authFunc(get(urlPathEqualTo(downloadPath)))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBody(content)))
  }

  "Sync" should "support a OneDrive URI for the source structure with OAuth" in {
    val factory = new SyncComponentsFactory
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val ServerPath = "/test%20data/folder%20(2)/folder%20(3)"
    val FileName = "file (5).mp3"
    val FileNameEnc = "file%20(5).mp3"
    val config = createOneDriveConfig(ServerPath)
    val storageConfig = prepareIdpConfig()
    val authFunc = TokenAuthFunc(RefreshedTokenData.accessToken)
    stubOneDriveFolderRequest(config, ServerPath, "folder3.json",
      status = StatusCodes.Unauthorized.intValue,
      authFunc = TokenAuthFunc(CurrentTokenData.accessToken))
    stubOneDriveFolderRequest(config, ServerPath, "folder3.json",
      authFunc = authFunc)
    stubTokenRefresh()
    stubDownloadRequest(config, ServerPath + "/" + FileNameEnc, authFunc)
    val options = withOAuthOptions(storageConfig, "--src-",
      "onedrive:" + DriveID, dstFolder.toAbsolutePath.toString, "--src-path", ServerPath,
      "--src-server-uri", serverUri("/"))

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    readFileInPath(dstFolder, FileName) should be(FileTestHelper.TestData)
  }

  it should "support a OneDrive URI for the source structure with basic auth" in {
    val factory = new SyncComponentsFactory
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val ServerPath = "/test%20data/folder%20(2)/folder%20(3)"
    val FileName = "file (5).mp3"
    val FileNameEnc = "file%20(5).mp3"
    val config = createOneDriveConfig(ServerPath)
    stubOneDriveFolderRequest(config, ServerPath, "folder3.json", authFunc = BasicAuthFunc)
    stubDownloadRequest(config, ServerPath + "/" + FileNameEnc, BasicAuthFunc)
    val options = Array("onedrive:" + DriveID, dstFolder.toAbsolutePath.toString, "--src-path", ServerPath,
      "--src-server-uri", serverUri("/"), "--src-user", UserId, "--src-password", Password)

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    readFileInPath(dstFolder, FileName) should be(FileTestHelper.TestData)
  }
}
