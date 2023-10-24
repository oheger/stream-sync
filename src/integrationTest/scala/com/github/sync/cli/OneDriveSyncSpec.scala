/*
 * Copyright 2018-2023 The Developers Team.
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
import com.github.sync.WireMockSupport.*
import com.github.sync.cli.oauth.OAuthParameterManager
import com.github.sync.{FileTestHelper, OAuthMockSupport, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock.*
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}

import java.nio.file.Files
import java.util.UUID
import scala.concurrent.ExecutionContext

object OneDriveSyncSpec:
  /** The drive ID used by tests. */
  private val DriveID = "test-drive"

  /** The root path on the server used by tests. */
  private val ServerPath = "/test%20data/folder%20(2)/folder%20(3)"

  /** The URI to resolve the root folder of the test setup. */
  private val ResolveFolderUri = s"/$DriveID/root:$ServerPath:?select=id"

  /** The content type reported by OneDrive for JSON documents. */
  private val ContentType =
    "application/json;odata.metadata=minimal;odata.streaming=true;IEEE754Compatible=false;charset=utf-8"

  /**
    * Generates the URI of an element based on its ID.
    *
    * @param id     the element ID
    * @param suffix an option suffix to append to the URI
    * @return the relative element URI
    */
  private def itemUri(id: String, suffix: String = null): String =
    s"/$DriveID/items/$id${Option(suffix).getOrElse("")}"

  /**
    * Returns the path of the given URI.
    *
    * @param uri the URI
    * @return the path of this URI as string
    */
  private def path(uri: Uri): String = uri.path.toString()

/**
  * Integration test class for sync processes that contains tests related to
  * OneDrive servers. The tests typically make use of a WireMock server.
  */
class OneDriveSyncSpec extends BaseSyncSpec with WireMockSupport with OAuthMockSupport:
  override implicit val ec: ExecutionContext = system.dispatcher

  import OneDriveSyncSpec.*

  /**
    * Adds a stubbing declaration for a request to resolve the given path.
    *
    * @param elementID the ID of the element to be returned
    * @param status    the status code to be returned for the request
    * @param authFunc  the authentication function
    */
  private def stubResolvePathRequest(elementID: String, status: Int = StatusCodes.OK.intValue,
                                     authFunc: AuthFunc = WireMockSupport.NoAuthFunc): Unit =
    val response = "{ \"id\": \"" + elementID + "\" }"
    stubFor(authFunc(get(urlEqualTo(ResolveFolderUri)))
      .withHeader("Accept", equalTo("application/json"))
      .willReturn(aResponse().withStatus(status)
        .withHeader("Content-Type", ContentType)
        .withBody(response)))

  /**
    * Adds a stubbing declaration for a request to a OneDrive folder that is
    * served with content defined by the given content function.
    *
    * @param id       the ID of the folder
    * @param status   the status code to be returned for the request
    * @param authFunc the authentication function
    * @param fContent the function defining the content
    * @return the URI to request the folder
    */
  private def stubOneDriveFolderRequestContent(id: String, status: Int = StatusCodes.OK.intValue,
                                               authFunc: AuthFunc = WireMockSupport.NoAuthFunc)
                                              (fContent: ResponseFunc): String =
    val stubUri = itemUri(id, "/children")
    stubFor(authFunc(get(urlEqualTo(path(stubUri))))
      .withHeader("Accept", equalTo("application/json"))
      .willReturn(fContent(aResponse()
        .withStatus(status)
        .withHeader("Content-Type", ContentType))))
    stubUri

  /**
    * Adds a stubbing declaration for a request to a OneDrive folder that is
    * served with the file specified.
    *
    * @param id           the ID of the folder
    * @param responseFile the file to serve the request
    * @param status       the status code to be returned for the request
    * @param authFunc     the authentication function
    * @return the URI to request the folder
    */
  private def stubOneDriveFolderRequest(id: String, responseFile: String, status: Int = StatusCodes.OK.intValue,
                                        authFunc: AuthFunc = WireMockSupport.NoAuthFunc): String =
    stubOneDriveFolderRequestContent(id, status, authFunc)(bodyFile(responseFile))

  private def stubDownloadRequest(id: String, authFunc: AuthFunc,
                                  contentFunc: ResponseFunc = bodyString(FileTestHelper.TestData)): Unit =
    val downloadPath = "/" + UUID.randomUUID()
    stubFor(authFunc(get(urlEqualTo(itemUri(id, "/content"))))
      .willReturn(aResponse().withStatus(302)
        .withHeader("Location", serverUri(downloadPath))))
    stubFor(authFunc(get(urlPathEqualTo(downloadPath)))
      .willReturn(contentFunc(aResponse().withStatus(StatusCodes.OK.intValue))))

  "Sync" should "support a OneDrive URI for the source structure with OAuth" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val FileName = "file (5).mp3"
    val storageConfig = prepareIdpConfig()
    val authFunc = TokenAuthFunc(RefreshedTokenData.accessToken)
    val FolderID = "theFolderID"
    val FileID = "xxxyyyzzz1234567!26990"
    stubResolvePathRequest(FolderID, status = StatusCodes.Unauthorized.intValue, authFunc = TokenAuthFunc(CurrentTokenData.accessToken))
    stubResolvePathRequest(FolderID, authFunc = authFunc)
    stubOneDriveFolderRequest(FolderID, "folder3.json", authFunc = authFunc)
    stubTokenRefresh()
    stubDownloadRequest(FileID, authFunc)
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
    val FolderID = "theFolderID"
    val FileName = "file (5).mp3"
    val FileID = "xxxyyyzzz1234567!26990"
    stubResolvePathRequest(FolderID, authFunc = BasicAuthFunc)
    stubOneDriveFolderRequest(FolderID, "folder3.json", authFunc = BasicAuthFunc)
    stubDownloadRequest(FileID, BasicAuthFunc)
    val options = IndexedSeq("onedrive:" + DriveID, dstFolder.toAbsolutePath.toString, "--src-path", ServerPath,
      "--src-server-uri", serverUri("/"), "--src-user", UserId, "--src-password", Password)

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    readFileInPath(dstFolder, FileName) should be(FileTestHelper.TestData)
  }

  it should "support a OneDrive URI for the destination structure with OAuth" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val FolderID = "theFolderID"
    val FileID = "xxxyyyzzz1234567!26990"
    val storageConfig = prepareIdpConfig()
    val oldTokenAuth = TokenAuthFunc(CurrentTokenData.accessToken)
    val newTokenAuth = TokenAuthFunc(RefreshedTokenData.accessToken)
    stubResolvePathRequest(FolderID, status = StatusCodes.Unauthorized.intValue, authFunc = oldTokenAuth)
    stubResolvePathRequest(FolderID, authFunc = newTokenAuth)
    stubOneDriveFolderRequest(FolderID, "folder3.json", authFunc = newTokenAuth)
    stubTokenRefresh()
    stubFor(oldTokenAuth(delete(anyUrl())).willReturn(aResponse().withStatus(StatusCodes.Unauthorized.intValue)))
    stubFor(newTokenAuth(delete(anyUrl())).willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val options = withOAuthOptions(storageConfig, "--dst-",
      srcFolder.toAbsolutePath.toString, "onedrive:" + DriveID, "--dst-path", ServerPath,
      "--dst-server-uri", serverUri("/"))

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    verify(deleteRequestedFor(urlEqualTo(itemUri(FileID))))
  }

  it should "not support short alias names for storage configuration options" in {
    val options = IndexedSeq("-n", "someIDP", "-D", testDirectory.toAbsolutePath.toString, "-U", "some/src/path",
      "onedrive:" + DriveID, "--dst-path", "ServerPath", "--dst-server-uri", serverUri("/"))

    val output = checkSyncOutput(options, "Invalid")
    val usagePos = output.indexOf("Usage:")
    usagePos should be > 0
    val errorMsg = output.substring(0, usagePos)
    errorMsg should include("-D ")
    errorMsg should include("-n ")
    errorMsg should include("-U ")
  }

  it should "support a OneDrive URI for the destination structure with basic auth" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val FolderID = "theFolderID"
    val FileID = "xxxyyyzzz1234567!26990"
    stubResolvePathRequest(FolderID, authFunc = BasicAuthFunc)
    stubOneDriveFolderRequest(FolderID, "folder3.json", authFunc = BasicAuthFunc)
    stubFor(BasicAuthFunc(delete(anyUrl())).willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val options = IndexedSeq(srcFolder.toAbsolutePath.toString, "onedrive:" + DriveID, "--dst-path", ServerPath,
      "--dst-server-uri", serverUri("/"), "--dst-user", UserId, "--dst-password", Password)

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    result.totalOperations should be(1)
    verify(deleteRequestedFor(urlEqualTo(itemUri(FileID))))
  }

  it should "support a OneDrive source with encrypted file names" in {
    val CryptPassword = Password
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val FolderID = "theEncryptedFolder"
    stubResolvePathRequest(FolderID, authFunc = BasicAuthFunc)
    stubOneDriveFolderRequest(FolderID, "root_encrypted.json", authFunc = BasicAuthFunc)
    stubOneDriveFolderRequest("xxxyyyzzz1234567!7193", "folder_encrypted.json", authFunc = BasicAuthFunc)
    stubDownloadRequest("xxxyyyzzz1234567!26990", BasicAuthFunc, bodyFile("encrypted1.dat"))
    stubDownloadRequest("xxxyyyzzz1234567!26988", BasicAuthFunc,
      bodyFile("encrypted2.dat"))
    stubDownloadRequest("xxxyyyzzz1234567!27123", BasicAuthFunc, bodyFile("encrypted3.dat"))
    stubDownloadRequest("xxxyyyzzz1234567!27222", BasicAuthFunc, bodyFile("encrypted4.dat"))
    val options = IndexedSeq("onedrive:" + DriveID, dstFolder.toAbsolutePath.toString, "--src-path", ServerPath,
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
    val options = IndexedSeq("onedrive:" + DriveID, "/some/path")

    checkSyncOutput(options, "src-" + SyncCliStructureConfig.PropOneDrivePath,
      "src-" + SyncCliStructureConfig.PropOneDriveServer, "src-" + SyncCliStructureConfig.PropOneDriveUploadChunkSize,
      "src-" + SyncCliStructureConfig.PropAuthUser, "src-" + SyncCliStructureConfig.PropAuthPassword,
      "src-" + OAuthParameterManager.PasswordOption, "src-" + OAuthParameterManager.StoragePathOption)
  }
