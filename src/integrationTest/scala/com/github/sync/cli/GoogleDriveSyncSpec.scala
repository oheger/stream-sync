/*
 * Copyright 2018-2025 The Developers Team.
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

import com.github.sync.OAuthMockSupport.{CurrentTokenData, RefreshedTokenData}
import com.github.sync.WireMockSupport.{AuthFunc, TokenAuthFunc}
import com.github.sync.{FileTestHelper, OAuthMockSupport, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock.*
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}

import java.nio.file.Files
import scala.concurrent.ExecutionContext

object GoogleDriveSyncSpec:
  /** The endpoint for accessing the GoogleDrive API. */
  private val GoogleDriveAPI = "/drive/v3/files"

/**
  * Integration test class for sync processes against a GoogleDrive account.
  * Here only basic operations are tested to make sure that the GoogleDrive
  * protocol has been correctly setup.
  */
class GoogleDriveSyncSpec extends BaseSyncSpec with WireMockSupport with OAuthMockSupport:
  override protected implicit val ec: ExecutionContext = system.dispatcher

  import GoogleDriveSyncSpec.*

  /**
    * Stubs a request to the GoogleDrive root folder and returns a JSON
    * document with a single file.
    *
    * @param authFunc the authentication function
    * @param status   the status code to return
    */
  private def stubGoogleDriveFolderRequest(authFunc: AuthFunc, status: StatusCode = StatusCodes.OK): Unit =
    stubFor(authFunc(get(urlPathEqualTo(GoogleDriveAPI))
      .withQueryParam("q", equalTo("'root' in parents and trashed = false")))
      .willReturn(aResponse().withStatus(status.intValue())
        .withHeader("Content-Type", "application/json")
        .withBodyFile("gdrive_folder.json")))

  "Sync" should "support a GoogleDrive URI for the source structure with OAuth" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val FileName = "test-file.txt"
    val storageConfig = prepareIdpConfig()
    val authFunc = TokenAuthFunc(RefreshedTokenData.accessToken)
    val FileID = "file_id-1"
    stubGoogleDriveFolderRequest(TokenAuthFunc(CurrentTokenData.accessToken), StatusCodes.Unauthorized)
    stubGoogleDriveFolderRequest(authFunc)
    stubTokenRefresh()
    stubFor(authFunc(get(urlPathEqualTo(s"$GoogleDriveAPI/$FileID")))
      .withQueryParam("alt", equalTo("media"))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBody(FileTestHelper.TestData)))
    val options = withOAuthOptions(storageConfig, "--src-",
      "googledrive:/", dstFolder.toAbsolutePath.toString, "--src-server-uri", serverUri("/"))

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    readFileInPath(dstFolder, FileName) should be(FileTestHelper.TestData)
  }

  it should "support a GoogleDrive URI for the destination structure with OAuth" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val storageConfig = prepareIdpConfig()
    val authFunc = TokenAuthFunc(RefreshedTokenData.accessToken)
    val FileID = "file_id-1"
    stubGoogleDriveFolderRequest(TokenAuthFunc(CurrentTokenData.accessToken), StatusCodes.Unauthorized)
    stubGoogleDriveFolderRequest(authFunc)
    stubTokenRefresh()
    stubFor(authFunc(delete(urlPathEqualTo(s"$GoogleDriveAPI/$FileID")))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val options = withOAuthOptions(storageConfig, "--dst-",
      dstFolder.toAbsolutePath.toString, "googledrive:", "--dst-server-uri", serverUri("/"))

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    verify(deleteRequestedFor(urlPathEqualTo(s"$GoogleDriveAPI/$FileID")))
  }
