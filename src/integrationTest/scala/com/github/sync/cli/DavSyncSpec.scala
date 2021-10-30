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

package com.github.sync.cli

import akka.http.scaladsl.model.StatusCodes
import akka.util.ByteString
import com.github.cloudfiles.core.http.{HttpRequestSender, UriEncodingHelper}
import com.github.sync.WireMockSupport.*
import com.github.sync.cli.SyncSetup.ProtocolFactorySetupFunc
import com.github.sync.cli.oauth.OAuthParameterManager
import com.github.sync.protocol.{SyncProtocol, SyncProtocolFactory}
import com.github.sync.{FileTestHelper, OAuthMockSupport, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.RequestMethod
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{verify, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

object DavSyncSpec {
  /** A test path to be requested from the server. */
  private val WebDavPath = "/test%20data/folder%20(2)/folder%20(3)"
}

/**
  * Integration test class for sync processes that contains tests related to
  * WebDav servers. The tests typically make use of a WireMock server.
  */
class DavSyncSpec extends BaseSyncSpec with MockitoSugar with WireMockSupport with OAuthMockSupport {

  import DavSyncSpec._
  import OAuthMockSupport._

  override implicit val ec: ExecutionContext = system.dispatcher

  /**
    * Stubs a GET request to access a file from a DAV server.
    *
    * @param uri          the URI
    * @param responseFile the file to be returned
    */
  private def stubFileRequest(uri: String, responseFile: String): Unit = {
    stubFor(BasicAuthFunc(get(urlPathEqualTo(uri))
      .willReturn(aResponse()
        .withStatus(StatusCodes.OK.intValue)
        .withBodyFile(responseFile))))
  }

  /**
    * Adds a stubbing declaration for a request to a folder that is served with
    * the file specified.
    *
    * @param uri          the URI of the folder
    * @param responseFile the file to serve the request
    * @param status       the status code to return from the request
    * @param authFunc     the authorization function
    * @param optDelay     an optional delay for this request
    */
  private def stubFolderRequest(uri: String, responseFile: String,
                                status: Int = StatusCodes.OK.intValue,
                                authFunc: AuthFunc = BasicAuthFunc,
                                optDelay: Option[FiniteDuration] = None): Unit = {
    val reqUri = if (uri.endsWith("/")) uri else uri + "/"
    val delay = optDelay.map(_.toMillis.toInt).getOrElse(0)
    stubFor(authFunc(request("PROPFIND", urlPathEqualTo(reqUri))
      .withHeader("Accept", equalTo("text/xml"))
      .withHeader("Depth", equalTo("1"))
      .willReturn(aResponse()
        .withStatus(status)
        .withFixedDelay(delay)
        .withBodyFile(responseFile))))
  }

  "Sync" should "support a WebDav URI for the source structure" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    stubFolderRequest(WebDavPath, "folder3.xml")
    val logFile = createFileReference()
    val options = Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--log", logFile.toAbsolutePath.toString, "--dry-run", "--src-user", UserId,
      "--src-password", Password)
    val expLine = "CREATE 0 - FILE %2Ftest%2520data%2Ffolder%2520%282%29%2Ffolder%2520%283%29%2Ffile%2520%285%29.mp3" +
      " %2Ffil5.mp3 0 2018-09-19T20:14:00Z 500"

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    val lines = Files.readAllLines(logFile)
    lines.size() should be(1)
    lines.get(0) should be(expLine)
  }

  it should "support a WebDav URI for the source structure with an OAuth IDP" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val storageConfig = prepareIdpConfig()
    stubFolderRequest(WebDavPath, "folder3.xml", status = StatusCodes.Unauthorized.intValue,
      authFunc = TokenAuthFunc(CurrentTokenData.accessToken))
    stubFolderRequest(WebDavPath, "folder3.xml", authFunc = TokenAuthFunc(RefreshedTokenData.accessToken))
    stubTokenRefresh()
    val logFile = createFileReference()
    val options = withOAuthOptions(storageConfig, "--src-",
      "dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--log", logFile.toAbsolutePath.toString, "-d")
    val expLine = "CREATE 0 - FILE %2Ftest%2520data%2Ffolder%2520%282%29%2Ffolder%2520%283%29%2Ffile%2520%285%29.mp3" +
      " %2Ffil5.mp3 0 2018-09-19T20:14:00Z 500"

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    val lines = Files.readAllLines(logFile)
    lines.size() should be(1)
    lines.get(0) should be(expLine)
  }

  it should "support a WebDav URI for the source structure without authentication" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    stubFolderRequest(WebDavPath, "folder3.xml", authFunc = identity)
    val logFile = createFileReference()
    val options = Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--log", logFile.toAbsolutePath.toString, "-d")
    val expLine = "CREATE 0 - FILE %2Ftest%2520data%2Ffolder%2520%282%29%2Ffolder%2520%283%29%2Ffile%2520%285%29.mp3" +
      " %2Ffil5.mp3 0 2018-09-19T20:14:00Z 500"

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    val lines = Files.readAllLines(logFile)
    lines.size() should be(1)
    lines.get(0) should be(expLine)

    import scala.jdk.CollectionConverters._
    getAllServeEvents.asScala foreach { event =>
      event.getRequest.containsHeader("Authorization") shouldBe false
    }
  }

  it should "support a WebDav URI for the destination structure" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    stubFolderRequest(WebDavPath, "folder3_full.xml")
    val logFile = createFileReference()
    val options = Array(srcFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--log", logFile.toAbsolutePath.toString, "--dry-run", "--dst-user", UserId,
      "--dst-password", Password, "--dst-modified-Property", "Win32LastModifiedTime")
    val FileID = "%2Ftest%2520data%2Ffolder%2520%282%29%2Ffolder%2520%283%29%2Ffile%2520%285%29.mp3"
    val expLine = s"REMOVE 0 $FileID FILE $FileID %2Ffil5.mp3 0 2018-09-21T20:14:00Z 500"

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    val lines = Files.readAllLines(logFile)
    lines.size() should be(1)
    lines.get(0) should be(expLine)
  }

  it should "support the --switch parameter to switch source and destination structures" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    stubFolderRequest(WebDavPath, "folder3.xml")
    val logFile = createFileReference()
    val options = Array(dstFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--log", logFile.toAbsolutePath.toString, "-d", "--dst-user", UserId,
      "--dst-password", Password, "--switch")
    val expLine = "CREATE 0 - FILE %2Ftest%2520data%2Ffolder%2520%282%29%2Ffolder%2520%283%29%2Ffile%2520%285%29.mp3" +
      " %2Ffil5.mp3 0 2018-09-19T20:14:00Z 500"

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    val lines = Files.readAllLines(logFile)
    lines.size() should be(1)
    lines.get(0) should be(expLine)
  }

  it should "support switching source and destination parameters with complex authentication" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val storageConfig = prepareIdpConfig()
    stubFolderRequest(WebDavPath, "folder3.xml", status = StatusCodes.Unauthorized.intValue,
      authFunc = TokenAuthFunc(CurrentTokenData.accessToken))
    stubFolderRequest(WebDavPath, "folder3.xml", authFunc = TokenAuthFunc(RefreshedTokenData.accessToken))
    stubTokenRefresh()
    val logFile = createFileReference()
    val options = withOAuthOptions(storageConfig, "--dst-",
      dstFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "-S", "--log", logFile.toAbsolutePath.toString, "--dry-run")
    val expLine = "CREATE 0 - FILE %2Ftest%2520data%2Ffolder%2520%282%29%2Ffolder%2520%283%29%2Ffile%2520%285%29.mp3" +
      " %2Ffil5.mp3 0 2018-09-19T20:14:00Z 500"

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    val lines = Files.readAllLines(logFile)
    lines.size() should be(1)
    lines.get(0) should be(expLine)
  }

  it should "do proper cleanup for a Dav source when using a log file source and apply mode NONE" in {
    def createMockProtocol(): SyncProtocol = {
      val protocol = mock[SyncProtocol]
      when(protocol.readRootFolder()).thenReturn(Future.successful(Nil))
      protocol
    }

    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val procLog = createPathInDirectory("processed.log")
    val operations = List(s"CREATE 0 null FILE id1 /syncFile.txt 0 2019-09-04T21:30:23.00Z 42")
    val syncLogFile = createDataFile(content = operations.mkString("\n"))
    val options = Array("dav:http://irrelevant.host.org/test", dstFolder.toAbsolutePath.toString,
      "--sync-log", syncLogFile.toAbsolutePath.toString, "--log", procLog.toAbsolutePath.toString,
      "-d", "--src-user", UserId, "--src-password", Password)
    val srcProtocol = createMockProtocol()
    val dstProtocol = createMockProtocol()
    val protocolFactory = mock[SyncProtocolFactory]
    when(protocolFactory.createProtocol(anyString(), any())).thenAnswer((invocation: InvocationOnMock) => {
      val uri: String = invocation.getArgument(0)
      if (uri == dstFolder.toString) dstProtocol else srcProtocol
    })
    val protocolSetupFunc: ProtocolFactorySetupFunc = (_, _, _, _) => protocolFactory

    val result = futureResult(runSync(options, optProtocolSetupFunc = Some(protocolSetupFunc)))
    result.successfulOperations should be(operations.size)
    verify(srcProtocol).close()
    verify(dstProtocol).close()
  }

  /**
    * Prepares a sync operation between the test DAV server and the file
    * system. A single file should be downloaded from the server.
    *
    * @param dstFolder the destination folder
    * @return the array with options to run the sync process
    */
  private def prepareSyncFromServer(dstFolder: Path): Array[String] = {
    stubFolderRequest(WebDavPath, "folder3.xml")
    stubFor(BasicAuthFunc(get(urlPathEqualTo(WebDavPath + "/file%20(5).mp3")))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBodyFile("response.txt")))
    Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--src-user", UserId, "--src-password", Password)
  }

  it should "correctly download files from a WebDav source with basic auth" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val options = prepareSyncFromServer(dstFolder)

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    val fileContent = readFileInPath(dstFolder, "fil5.mp3")
    fileContent should startWith(FileTestHelper.TestData take 50)
  }

  it should "correctly download files from a WebDav source with OAuth" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val storageConfig = prepareIdpConfig(optPassword = None)
    stubFolderRequest(WebDavPath, "folder3.xml", status = StatusCodes.Unauthorized.intValue,
      authFunc = TokenAuthFunc(CurrentTokenData.accessToken))
    val authFunc = TokenAuthFunc(RefreshedTokenData.accessToken)
    stubFolderRequest(WebDavPath, "folder3.xml", authFunc = authFunc)
    stubTokenRefresh()
    stubFor(authFunc(get(urlPathEqualTo(WebDavPath + "/file%20(5).mp3")))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBodyFile("response.txt")))
    val options = withOAuthOptions(storageConfig, "--src-",
      "dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString)

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    val fileContent = readFileInPath(dstFolder, "fil5.mp3")
    fileContent should startWith(FileTestHelper.TestData take 50)
  }

  it should "support sync operations targeting a WebDav server" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val WebDavPath = "/destination"
    val FileName = "testDataFile.dat"
    val ModifiedProperty = "Win32LastModifiedTime"
    val ModifiedNamespace = "modified-urn:"
    createTestFile(srcFolder, FileName)
    stubFolderRequest(WebDavPath, "empty_folder.xml")
    stubFor(BasicAuthFunc(put(urlPathEqualTo(WebDavPath + "/" + FileName)))
      .withRequestBody(equalTo(FileName))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    stubFor(request("PROPPATCH", urlPathEqualTo(WebDavPath + "/" + FileName))
      .withRequestBody(matching(".*xmlns:ns0=\"" + ModifiedNamespace + ".*"))
      .withRequestBody(matching(s".*<ns0:$ModifiedProperty>.*"))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val options = Array(srcFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--dst-user", UserId, "--dst-password", Password,
      "--dst-modified-property", ModifiedProperty, "--dst-modified-namespace", ModifiedNamespace)

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
    getAllServeEvents should have size 3
  }

  it should "support sync operations targeting a WebDav server with OAuth" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val WebDavPath = "/destination"
    val FileName = "testDataFile.dat"
    val ModifiedProperty = "Win32LastModifiedTime"
    val ModifiedNamespace = "modified-urn:"
    createTestFile(srcFolder, FileName)
    val storageConfig = prepareIdpConfig()
    val oldAuthFunc = TokenAuthFunc(CurrentTokenData.accessToken)
    val authFunc = TokenAuthFunc(RefreshedTokenData.accessToken)
    stubFolderRequest(WebDavPath, "empty_folder.xml", status = StatusCodes.Unauthorized.intValue,
      authFunc = oldAuthFunc)
    stubTokenRefresh()
    stubFolderRequest(WebDavPath, "empty_folder.xml", authFunc = authFunc)
    stubFor(oldAuthFunc(put(urlPathEqualTo(WebDavPath + "/" + FileName)))
      .willReturn(aResponse().withStatus(StatusCodes.Unauthorized.intValue)))
    stubFor(authFunc(put(urlPathEqualTo(WebDavPath + "/" + FileName)))
      .withRequestBody(equalTo(FileName))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    stubFor(authFunc(request("PROPPATCH", urlPathEqualTo(WebDavPath + "/" + FileName)))
      .withRequestBody(matching(".*xmlns:ssync=\"" + ModifiedNamespace + ".*"))
      .withRequestBody(matching(s".*<ssync:$ModifiedProperty>.*"))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))
    val options = withOAuthOptions(storageConfig, "--dst-",
      srcFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--dst-modified-property", ModifiedProperty, "--dst-modified-namespace", ModifiedNamespace)

    val result = futureResult(runSync(options))
    result.successfulOperations should be(1)
  }

  it should "evaluate the timeout when downloading a file from a WebDav server" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val timeout = 1.second
    stubFolderRequest(WebDavPath, "folder3.xml")
    stubFor(BasicAuthFunc(get(urlPathEqualTo(WebDavPath + "/file%20(5).mp3")))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue).withFixedDelay(2 * timeout.toMillis.toInt)
        .withBodyFile("response.txt")))
    val options = Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--src-user", UserId, "--src-password", Password, "--timeout", timeout.toSeconds.toString)

    createSync()
    val result = futureResult(runSync(options))
    result.successfulOperations should be(0)
  }

  it should "evaluate the timeout for the WebDav element source" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val timeout = 1.second
    stubFolderRequest(WebDavPath, "folder3.xml", optDelay = Some(timeout * 2))
    stubFor(BasicAuthFunc(get(urlPathEqualTo(WebDavPath + "/file%20(5).mp3")))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBodyFile("response.txt")))
    val options = Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--src-user", UserId, "--src-password", Password, "-t", timeout.toSeconds.toString)

    expectFailedFuture[TimeoutException](runSync(options))
  }

  it should "support a WebDav source with encrypted file names" in {
    val CryptPassword = Password
    val WebDavPath = "/encrypted"
    stubFolderRequest(WebDavPath, "root_encrypted.xml")
    stubFolderRequest(WebDavPath + "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/", "folder_encrypted.xml")
    stubFileRequest(WebDavPath + "/HLL2gCNjWKvwRnp4my1U2ex0QLKWpZs=", "encrypted1.dat")
    stubFileRequest(WebDavPath + "/uBQQYWockOWLuCROIHviFhU2XayMtps=", "encrypted2.dat")
    stubFileRequest(WebDavPath + "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN",
      "encrypted3.dat")
    stubFileRequest(WebDavPath + "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Z3BDvmY89rQwUqJ3XzMUWgtBE9bcOCYxiTq-Zfo-sNlIGA==",
      "encrypted4.dat")
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(dstFolder, "foo.txt", content = Some("Test file content"))
    val pathDeleted = createTestFile(dstFolder, "toDelete.txt")
    val options = Array("dav:" + serverUri(WebDavPath), dstFolder.toAbsolutePath.toString,
      "--src-encrypt-password", CryptPassword, "--src-crypt-mode", "filesAndNames", "--src-user", UserId,
      "--src-password", Password)

    val result = futureResult(runSync(options))
    result.successfulOperations should be(result.totalOperations)
    Files.exists(pathDeleted) shouldBe false
    val rootFiles = dstFolder.toFile.listFiles()
    rootFiles.map(_.getName) should contain only("foo.txt", "bar.txt", "sub")
    checkFile(dstFolder, "foo.txt")
    val subFolder = dstFolder.resolve("sub")
    checkFile(subFolder, "subFile.txt")
    checkFile(subFolder, "anotherSubFile.dat")
  }

  it should "support a WebDav destination with encrypted file names" in {
    val CryptPassword = "secretServer"
    val WebDavPath = "/secret"
    stubSuccess()
    stubFolderRequest(WebDavPath, "empty_folder.xml")
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val FileName = "plainFile.txt"
    val Content = "This is the content of the test file ;-)"
    createTestFile(srcFolder, FileName, content = Some(Content))
    val options = Array(srcFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--dst-encrypt-password", CryptPassword, "--dst-crypt-mode", "filesAndNames", "--dst-user", UserId,
      "--dst-password", Password)

    futureResult(runSync(options)).successfulOperations should be(1)
    import scala.jdk.CollectionConverters._
    val events = getAllServeEvents.asScala
    val putRequest = events.find(event => event.getRequest.getMethod == RequestMethod.PUT).get.getRequest
    val (parent, fileUri) = UriEncodingHelper.splitParent(putRequest.getUrl)
    parent should be(WebDavPath)
    decryptName(CryptPassword, fileUri) should be(FileName)
    val bodyPlain = decrypt(CryptPassword, ByteString(putRequest.getBody))
    bodyPlain.utf8String should be(Content)
  }

  it should "support a WebDav destination with encrypted file names together with the switch parameter" in {
    val CryptPassword = Password
    val WebDavPath = "/encrypted"
    stubFolderRequest(WebDavPath, "root_encrypted.xml")
    stubFolderRequest(WebDavPath + "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/", "folder_encrypted.xml")
    stubFileRequest(WebDavPath + "/HLL2gCNjWKvwRnp4my1U2ex0QLKWpZs=", "encrypted1.dat")
    stubFileRequest(WebDavPath + "/uBQQYWockOWLuCROIHviFhU2XayMtps=", "encrypted2.dat")
    stubFileRequest(WebDavPath + "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN",
      "encrypted3.dat")
    stubFileRequest(WebDavPath + "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Z3BDvmY89rQwUqJ3XzMUWgtBE9bcOCYxiTq-Zfo-sNlIGA==",
      "encrypted4.dat")
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(dstFolder, "foo.txt", content = Some("Test file content"))
    val pathDeleted = createTestFile(dstFolder, "toDelete.txt")
    val options = Array(dstFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--dst-encrypt-password", CryptPassword, "--dst-crypt-mode", "filesAndNames", "--dst-user", UserId,
      "--dst-password", Password, "-S")

    val result = futureResult(runSync(options))
    result.successfulOperations should be(result.totalOperations)
    Files.exists(pathDeleted) shouldBe false
    val rootFiles = dstFolder.toFile.listFiles()
    rootFiles.map(_.getName) should contain only("foo.txt", "bar.txt", "sub")
    checkFile(dstFolder, "foo.txt")
    val subFolder = dstFolder.resolve("sub")
    checkFile(subFolder, "subFile.txt")
    checkFile(subFolder, "anotherSubFile.dat")
  }

  it should "support an encrypted WebDav destination with a complex structure" in {
    val CryptPassword = Password
    val WebDavPath = "/encrypted"
    stubSuccess()
    stubFolderRequest(WebDavPath, "root_encrypted.xml")
    stubFolderRequest(WebDavPath + "/Q8Xcluxx2ADWaUAtUHLurqSmvw==/", "folder_encrypted.xml")
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    createTestFile(srcFolder, "foo.txt")
    createTestFile(srcFolder, "bar.txt")
    val subFolder = Files.createDirectory(srcFolder.resolve("sub"))
    createTestFile(subFolder, "subFile.txt")
    createTestFile(subFolder, "anotherSubFile.dat")
    createTestFile(subFolder, "newSubFile.doc")
    val options = Array(srcFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--dst-encrypt-password", CryptPassword, "--dst-crypt-mode", "filesAndNames", "--dst-user", UserId,
      "--dst-password", Password, "--ignore-time-delta", Int.MaxValue.toString)

    futureResult(runSync(options)).successfulOperations should be(1)
  }

  it should "cancel a sync operation if the access token cannot be refreshed" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val WebDavPath = "/destination"
    val FileCount = 16
    (1 to FileCount) map (i => s"testDataFile$i.dat") foreach (name => createTestFile(srcFolder, name))
    val ModifiedProperty = "Win32LastModifiedTime"
    val ModifiedNamespace = "modified-urn:"
    val storageConfig = prepareIdpConfig()
    val authFunc = TokenAuthFunc(CurrentTokenData.accessToken)
    stubFolderRequest(WebDavPath, "empty_folder.xml", status = StatusCodes.Unauthorized.intValue,
      authFunc = authFunc)
    stubFor(post(urlPathEqualTo(TokenEndpoint))
      .willReturn(aResponse().withStatus(StatusCodes.BadRequest.intValue)))
    stubFolderRequest(WebDavPath, "empty_folder.xml", authFunc = authFunc)
    stubFor(authFunc(put(anyUrl()))
      .willReturn(aResponse().withStatus(StatusCodes.Unauthorized.intValue)))
    val options = withOAuthOptions(storageConfig, "--dst-",
      srcFolder.toAbsolutePath.toString, "dav:" + serverUri(WebDavPath),
      "--dst-modified-property", ModifiedProperty, "--dst-modified-namespace", ModifiedNamespace)

    expectFailedFuture[HttpRequestSender.FailedResponseException](runSync(options))
  }

  it should "generate a usage message if invalid parameters are passed in" in {
    val options = Array("/some/path", "dav:" + serverUri("/target"), "--dst-delete-before-override", "invalid")

    checkSyncOutput(options, "dst-" + SyncCliStructureConfig.PropDavDeleteBeforeOverride,
      "dst-" + SyncCliStructureConfig.PropDavModifiedNamespace, "dst-" + SyncCliStructureConfig.PropDavModifiedProperty,
      "dst-" + SyncCliStructureConfig.PropAuthUser, "dst-" + SyncCliStructureConfig.PropAuthPassword,
      "dst-" + OAuthParameterManager.PasswordOption, "dst-" + OAuthParameterManager.StoragePathOption)
  }

  it should "not produce any log output per default" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val args = prepareSyncFromServer(dstFolder)

    val log = runSyncAndCaptureLogs(args)
    log should be("")
  }

  it should "log HTTP requests with log level INFO" in {
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    val args = prepareSyncFromServer(dstFolder) ++ Array("--INFO")

    val log = runSyncAndCaptureLogs(args)
    log should include("GET " + WebDavPath)
    log should include("PROPFIND")
  }
}
