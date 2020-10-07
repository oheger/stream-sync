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

package com.github.sync.cli

import java.nio.file.Files
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import com.github.sync.OAuthMockSupport.{CurrentTokenData, RefreshedTokenData}
import com.github.sync.WireMockSupport._
import com.github.sync.cli.oauth.OAuthParameterManager
import com.github.sync.onedrive.{OneDriveConfig, OneDriveStubbingSupport}
import com.github.sync.{FileTestHelper, OAuthMockSupport, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock._

import scala.concurrent.ExecutionContext

/**
  * Integration test class for sync processes that contains tests related to
  * OneDrive servers. The tests typically make use of a WireMock server.
  */
class OneDriveSyncSpec extends BaseSyncSpec with WireMockSupport with OneDriveStubbingSupport with OAuthMockSupport {

  import OneDriveStubbingSupport._

  override implicit val ec: ExecutionContext = system.dispatcher

  private def stubDownloadRequest(config: OneDriveConfig, uri: String, authFunc: AuthFunc,
                                  contentFunc: ResponseFunc = bodyString(FileTestHelper.TestData)): Unit = {
    val downloadPath = "/" + UUID.randomUUID()
    stubFor(authFunc(get(urlPathEqualTo(path(mapElementUri(config, uri, prefix = PrefixItems)) + ":/content")))
      .willReturn(aResponse().withStatus(302)
        .withHeader("Location", serverUri(downloadPath))))
    stubFor(authFunc(get(urlPathEqualTo(downloadPath)))
      .willReturn(contentFunc(aResponse().withStatus(StatusCodes.OK.intValue))))
  }

  "Sync" should "support a OneDrive URI for the source structure with OAuth" in {
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

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    readFileInPath(dstFolder, FileName) should be(FileTestHelper.TestData)
  }

  it should "support a OneDrive URI for the source structure with basic auth" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val ServerPath = "/test%20data/folder%20(2)/folder%20(3)"
    val FileName = "file (5).mp3"
    val FileNameEnc = "file%20(5).mp3"
    val config = createOneDriveConfig(ServerPath)
    stubOneDriveFolderRequest(config, "", "folder3.json", authFunc = BasicAuthFunc)
    stubDownloadRequest(config, "/" + FileNameEnc, BasicAuthFunc)
    val options = Array("onedrive:" + DriveID, dstFolder.toAbsolutePath.toString, "--src-path", ServerPath,
      "--src-server-uri", serverUri("/"), "--src-user", UserId, "--src-password", Password)

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    readFileInPath(dstFolder, FileName) should be(FileTestHelper.TestData)
  }

  it should "support a OneDrive URI for the destination structure with OAuth" in {
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

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    verify(deleteRequestedFor(urlPathEqualTo(path(ExpUri))))
  }

  it should "not support short alias names for storage configuration options" in {
    val options = Array("-n", "someIDP", "-d", testDirectory.toAbsolutePath.toString, "-U", "some/src/path",
      "onedrive:" + DriveID, "--dst-path", "ServerPath", "--dst-server-uri", serverUri("/"))

    val output = checkSyncOutput(options, "Invalid")
    val usagePos = output.indexOf("Usage:")
    usagePos should be > 0
    val errorMsg = output.substring(0, usagePos)
    errorMsg should include("-d ")
    errorMsg should include("-n ")
    errorMsg should include("-U ")
  }

  it should "support a OneDrive URI for the destination structure with basic auth" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val ServerPath = "/test%20data/folder%20(2)/folder%20(3)"
    val FileNameEnc = "file%20(5).mp3"
    val config = createOneDriveConfig(ServerPath)
    val ExpUri = mapElementUri(config, "/" + FileNameEnc)
    stubOneDriveFolderRequest(config, "", "folder3.json", authFunc = BasicAuthFunc)
    stubFor(BasicAuthFunc(delete(anyUrl())).willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val options = Array(srcFolder.toAbsolutePath.toString, "onedrive:" + DriveID, "--dst-path", ServerPath,
      "--dst-server-uri", serverUri("/"), "--dst-user", UserId, "--dst-password", Password)

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    verify(deleteRequestedFor(urlPathEqualTo(path(ExpUri))))
  }

  it should "support a OneDrive source with encrypted file names" in {
    val CryptPassword = Password
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val ServerPath = "/encrypted"
    val config = createOneDriveConfig(ServerPath)
    stubOneDriveFolderRequest(config, "", "root_encrypted.json", authFunc = BasicAuthFunc)
    stubOneDriveFolderRequest(config, "/Q8Xcluxx2ADWaUAtUHLurqSmvw==", "folder_encrypted.json",
      authFunc = BasicAuthFunc)
    stubDownloadRequest(config, "/HLL2gCNjWKvwRnp4my1U2ex0QLKWpZs=", BasicAuthFunc,
      bodyFile("encrypted1.dat"))
    stubDownloadRequest(config, "/uBQQYWockOWLuCROIHviFhU2XayMtps=", BasicAuthFunc,
      bodyFile("encrypted2.dat"))
    stubDownloadRequest(config, "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN",
      BasicAuthFunc, bodyFile("encrypted3.dat"))
    stubDownloadRequest(config, "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Z3BDvmY89rQwUqJ3XzMUWgtBE9bcOCYxiTq-Zfo-sNlIGA==",
      BasicAuthFunc, bodyFile("encrypted4.dat"))
    val options = Array("onedrive:" + DriveID, dstFolder.toAbsolutePath.toString, "--src-path", ServerPath,
      "--src-server-uri", serverUri("/"), "--src-user", UserId, "--src-password", Password,
      "--src-encrypt-password", CryptPassword, "--src-crypt-mode", "filesAndNames")

    val result = futureResult(runSync(options))
    result.successfulOperations should be(result.totalOperations)
    val rootFiles = dstFolder.toFile.listFiles()
    rootFiles.map(_.getName) should contain only("foo.txt", "bar.txt", "sub")
    checkFile(dstFolder, "foo.txt")
    val subFolder = dstFolder.resolve("sub")
    checkFile(subFolder, "subFile.txt")
    checkFile(subFolder, "anotherSubFile.dat")
  }

  it should "generate a usage message if invalid parameters are passed in" in {
    val options = Array("onedrive:" + DriveID, "/some/path")

    checkSyncOutput(options, "src-" + SyncStructureConfig.PropOneDrivePath,
      "src-" + SyncStructureConfig.PropOneDriveServer, "src-" + SyncStructureConfig.PropOneDriveUploadChunkSize,
      "src-" + SyncStructureConfig.PropAuthUser, "src-" + SyncStructureConfig.PropAuthPassword,
      "src-" + OAuthParameterManager.PasswordOption, "src-" + OAuthParameterManager.StoragePathOption)
  }
}
