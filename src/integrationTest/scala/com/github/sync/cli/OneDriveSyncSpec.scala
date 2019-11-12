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
    stubFor(authFunc(get(urlPathEqualTo(path(mapElementUri(config, uri, prefix = PrefixItems)) + ":/content")))
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
    stubOneDriveFolderRequest(config, "", "folder3.json",
      status = StatusCodes.Unauthorized.intValue,
      authFunc = TokenAuthFunc(CurrentTokenData.accessToken))
    stubOneDriveFolderRequest(config, "", "folder3.json", authFunc = authFunc)
    stubTokenRefresh()
    stubDownloadRequest(config, "/" + FileNameEnc, authFunc)
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
    stubOneDriveFolderRequest(config, "", "folder3.json", authFunc = BasicAuthFunc)
    stubDownloadRequest(config, "/" + FileNameEnc, BasicAuthFunc)
    val options = Array("onedrive:" + DriveID, dstFolder.toAbsolutePath.toString, "--src-path", ServerPath,
      "--src-server-uri", serverUri("/"), "--src-user", UserId, "--src-password", Password)

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    readFileInPath(dstFolder, FileName) should be(FileTestHelper.TestData)
  }

  it should "support a OneDrive URI for the destination structure with OAuth" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val ServerPath = "/test%20data/folder%20(2)/folder%20(3)"
    val FileNameEnc = "file%20(5).mp3"
    val config = createOneDriveConfig(ServerPath)
    val ExpUri = mapElementUri(config, "/" + FileNameEnc)
    val storageConfig = prepareIdpConfig()
    val oldTokenAuth = TokenAuthFunc(CurrentTokenData.accessToken)
    val newTokenAuth = TokenAuthFunc(RefreshedTokenData.accessToken)
    stubOneDriveFolderRequest(config, "", "folder3.json",
      status = StatusCodes.Unauthorized.intValue,
      authFunc = oldTokenAuth)
    stubOneDriveFolderRequest(config, "", "folder3.json", authFunc = newTokenAuth)
    stubTokenRefresh()
    stubFor(oldTokenAuth(delete(anyUrl())).willReturn(aResponse().withStatus(StatusCodes.Unauthorized.intValue)))
    stubFor(newTokenAuth(delete(anyUrl())).willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val options = withOAuthOptions(storageConfig, "--dst-",
      srcFolder.toAbsolutePath.toString, "onedrive:" + DriveID, "--dst-path", ServerPath,
      "--dst-server-uri", serverUri("/"))

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    verify(deleteRequestedFor(urlPathEqualTo(path(ExpUri))))
  }

  it should "support a OneDrive URI for the destination structure with basic auth" in {
    val factory = new SyncComponentsFactory
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val ServerPath = "/test%20data/folder%20(2)/folder%20(3)"
    val FileNameEnc = "file%20(5).mp3"
    val config = createOneDriveConfig(ServerPath)
    val ExpUri = mapElementUri(config, "/" + FileNameEnc)
    stubOneDriveFolderRequest(config, "", "folder3.json", authFunc = BasicAuthFunc)
    stubFor(BasicAuthFunc(delete(anyUrl())).willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val options = Array(srcFolder.toAbsolutePath.toString, "onedrive:" + DriveID, "--dst-path", ServerPath,
      "--dst-server-uri", serverUri("/"), "--dst-user", UserId, "--dst-password", Password)

    val result = futureResult(Sync.syncProcess(factory, options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    verify(deleteRequestedFor(urlPathEqualTo(path(ExpUri))))
  }
}
