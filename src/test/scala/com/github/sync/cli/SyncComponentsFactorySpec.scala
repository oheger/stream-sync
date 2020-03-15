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

import java.nio.file.Paths
import java.time.ZoneId

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import akka.util.Timeout
import com.github.sync.AsyncTestHelper
import com.github.sync.cli.ParameterManager.Parameters
import com.github.sync.cli.SyncComponentsFactory.{DestinationStructureType, SourceComponentsFactory, SourceStructureType}
import com.github.sync.cli.oauth.OAuthParameterManager
import com.github.sync.crypt.Secret
import com.github.sync.http.{HttpMultiHostRequestActor, HttpRequestActor, OAuthStorageConfig}
import com.github.sync.http.oauth.{OAuthConfig, OAuthStorageService, OAuthTokenData}
import com.github.sync.local.LocalFsConfig
import com.github.sync.onedrive.OneDriveConfig
import com.github.sync.webdav.DavConfig
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.implicitConversions

object SyncComponentsFactorySpec {
  /** A timeout value which is required to create sub factories. */
  private val TestTimeout: Timeout = Timeout(10.seconds)

  /** Test URI for a WebDav server. */
  private val DavRootUri = "https://my-test-server.org"

  /** The URI of the test WebDav server with the prefix indicating a Dav URI. */
  private val PrefixDavRootUri = SyncComponentsFactory.PrefixWebDav + DavRootUri

  /** Test user name. */
  private val User = "scott"

  /** Test password. */
  private val Password = "tiger"

  /** Test last-modified property. */
  private val LastModifiedProperty = "lastModifiedProp"

  /** Test namespace for the last-modified property. */
  private val LastModifiedNamespace = "testNamespace"

  /** Test storage path. */
  private val StoragePath = "/data/idp-providers"

  /** Test IDP name. */
  private val IdpName = "MyTestIDP"

  /** A test OneDrive drive ID. */
  private val OneDriveID = "my-drive"

  /** The OneDrive drive ID with the corresponding OneDrive prefix. */
  private val PrefixOneDriveID = SyncComponentsFactory.PrefixOneDrive + OneDriveID

  /**
    * A conversion function for parameter maps. This makes it possible to use
    * simple maps (with only one value per parameter).
    *
    * @param argsMap the simple map with arguments
    * @return the parameters map
    */
  implicit def toParameters(argsMap: Map[String, String]): Parameters =
    argsMap map (e => (e._1, List(e._2)))
}

/**
  * Test class for ''SyncComponentsFactory''. This class tests only a limited
  * subset of the functionality provided by the class. The remaining part is
  * tested by integration tests.
  */
class SyncComponentsFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper with MockitoSugar {
  def this() = this(ActorSystem("SyncComponentsFactorySpec"))

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit shutdownActorSystem system
  }

  import SyncComponentsFactorySpec._
  import system.dispatcher

  /** An object to materialize streams; needed as implicit parameter. */
  implicit val mat: ActorMaterializer = ActorMaterializer()

  /** A mock for a console reader; needed as implicit parameter. */
  implicit val consoleReader: ConsoleReader = mock[ConsoleReader]

  /**
    * Returns the local file system config from a source factory.
    *
    * @param sourceFactory the source factory
    * @return the local FS config from this factory
    */
  private def extractLocalFsSourceConfig(sourceFactory: SourceComponentsFactory): LocalFsConfig =
    sourceFactory match {
      case localFactory: LocalFsSourceComponentsFactory => localFactory.config
      case f => fail("Unexpected source factory: " + f)
    }

  /**
    * Returns the DAV config from a source factory.
    *
    * @param sourceFactory the source factory
    * @return the DAV config from this factory
    */
  private def extractDavSourceConfig(sourceFactory: SourceComponentsFactory): DavConfig =
    sourceFactory match {
      case davFactory: DavComponentsSourceFactory =>
        davFactory.httpActorFactory.httpRequestActorProps should be(HttpRequestActor(DavRootUri))
        davFactory.config
      case f => fail("Unexpected source factory: " + f)
    }

  /**
    * Returns the OneDrive config from a source factory.
    *
    * @param sourceFactory the source factory
    * @return the OneDrive config from this factory
    */
  private def extractOneDriveSourceConfig(sourceFactory: SourceComponentsFactory): OneDriveConfig =
    sourceFactory match {
      case oneFactory: OneDriveComponentsSourceFactory =>
        oneFactory.httpActorFactory.httpRequestActorProps should be(HttpMultiHostRequestActor(
          SyncComponentsFactory.OneDriveHostCacheSize, 1))
        oneFactory.config
      case f => fail("Unexpected source factory: " + f)
    }

  /**
    * Creates a mock storage service that is prepared to expect some
    * invocations of load methods.
    *
    * @return the initialized service mock
    */
  private def createMockOAuthStorageService():
  OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData] = {
    val service = mock[OAuthStorageService[OAuthStorageConfig, OAuthConfig, Secret, OAuthTokenData]]
    when(service.loadConfig(any())(any(), any())).thenReturn(Future.successful(mock[OAuthConfig]))
    when(service.loadClientSecret(any())(any(), any())).thenReturn(Future.successful(mock[Secret]))
    when(service.loadTokens(any())(any(), any())).thenReturn(Future.successful(mock[OAuthTokenData]))
    service
  }

  "SyncComponentsFactory" should "create a correct file system config for the source structure" in {
    val TimeZoneId = "UTC+02:00"
    val uri = "/my/sync/dir"
    val args = Map(SourceStructureType.configPropertyName(SyncComponentsFactory.PropLocalFsTimeZone) ->
      TimeZoneId)
    val syncFactory = new SyncComponentsFactory

    val (processedArgs, sourceFactory) =
      futureResult(syncFactory.createSourceComponentsFactory(uri, TestTimeout, args))
    processedArgs.accessedParameters should contain only SourceStructureType.configPropertyName(
      SyncComponentsFactory.PropLocalFsTimeZone)
    extractLocalFsSourceConfig(sourceFactory) should be(LocalFsConfig(Paths.get(uri), Some(ZoneId.of(TimeZoneId))))
  }

  it should "create a correct file system config for the source structure with defaults" in {
    val uri = "/my/sync/dir"
    val syncFactory = new SyncComponentsFactory

    val (processedArgs, sourceFactory) =
      futureResult(syncFactory.createSourceComponentsFactory(uri, TestTimeout, Map.empty[String, Iterable[String]]))
    processedArgs.accessedParameters should contain only SourceStructureType.configPropertyName(
      SyncComponentsFactory.PropLocalFsTimeZone)
    extractLocalFsSourceConfig(sourceFactory) should be(LocalFsConfig(Paths.get(uri), None))
  }

  it should "generate failure messages for all invalid parameters of a local FS config" in {
    val args = Map(SourceStructureType.configPropertyName(SyncComponentsFactory.PropLocalFsTimeZone) ->
      "invalid zone ID!")
    val syncFactory = new SyncComponentsFactory

    val exception =
      expectFailedFuture[IllegalArgumentException] {
        syncFactory.createSourceComponentsFactory("\u0000", TestTimeout, args)
      }
    exception.getMessage should include(SourceStructureType.configPropertyName(SyncComponentsFactory.PropLocalFsPath))
    exception.getMessage should include(SourceStructureType.configPropertyName(
      SyncComponentsFactory.PropLocalFsTimeZone))
  }

  it should "create a correct file system config for the destination structure" in {
    val TimeZoneId = "UTC-02:00"
    val uri = "/my/sync/target"
    val args = Map(DestinationStructureType.configPropertyName(SyncComponentsFactory.PropLocalFsTimeZone) ->
      TimeZoneId)
    val syncFactory = new SyncComponentsFactory

    val (processedArgs, destFactory) =
      futureResult(syncFactory.createDestinationComponentsFactory(uri, TestTimeout, args))
    processedArgs.accessedParameters should contain only DestinationStructureType.configPropertyName(
      SyncComponentsFactory.PropLocalFsTimeZone)
    destFactory match {
      case localFactory: LocalFsDestinationComponentsFactory =>
        localFactory.config should be(LocalFsConfig(Paths.get(uri), Some(ZoneId.of(TimeZoneId))))
        localFactory.timeout should be(TestTimeout)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "create a correct DavConfig for the source structure if all basic auth properties are defined" in {
    val args = Map(SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavUser) -> User,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavPassword) -> Password,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavModifiedProperty) -> LastModifiedProperty,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavModifiedNamespace) -> LastModifiedNamespace,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavDeleteBeforeOverride) -> "true")
    val expAccessedKeys = args.keySet + SourceStructureType.configPropertyName(OAuthParameterManager.NameOptionName)
    val syncFactory = new SyncComponentsFactory

    val (processedArgs, srcFactory) = futureResult(syncFactory.createSourceComponentsFactory(PrefixDavRootUri,
      TestTimeout, args))
    processedArgs.accessedParameters should be(expAccessedKeys)
    val config = extractDavSourceConfig(srcFactory)
    config.rootUri should be(Uri(DavRootUri))
    config.optBasicAuthConfig.get.user should be(User)
    config.optBasicAuthConfig.get.password.secret should be(Password)
    config.lastModifiedProperty should be(LastModifiedProperty)
    config.lastModifiedNamespace should be(Some(LastModifiedNamespace))
    config.deleteBeforeOverride shouldBe true
    config.timeout should be(TestTimeout)
    config.optOAuthConfig should be(None)
  }

  it should "create a correct DavConfig for the source structure with defaults" in {
    val args = Map(SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavUser) -> User,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavPassword) -> Password)
    val syncFactory = new SyncComponentsFactory

    val (_, srcFactory) = futureResult(syncFactory.createSourceComponentsFactory(PrefixDavRootUri,
      TestTimeout, args))
    val config = extractDavSourceConfig(srcFactory)
    config.lastModifiedProperty should be(DavConfig.DefaultModifiedProperty)
    config.lastModifiedNamespace should be(None)
    config.deleteBeforeOverride shouldBe false
  }

  it should "generate failure messages for all invalid parameters of a DavConfig" in {
    val args = Map(SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavDeleteBeforeOverride) -> "xx",
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavModifiedNamespace) -> LastModifiedNamespace,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavPassword) -> Password)
    val DavUri = SyncComponentsFactory.PrefixWebDav + "?not a Valid URI!"
    val syncFactory = new SyncComponentsFactory

    val exception = expectFailedFuture[IllegalArgumentException] {
      syncFactory.createSourceComponentsFactory(DavUri, TestTimeout, args)
    }
    exception.getMessage should include(SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavUri))
    exception.getMessage should include(SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavUser))
    exception.getMessage should include(SourceStructureType.configPropertyName(
      SyncComponentsFactory.PropDavDeleteBeforeOverride))
  }

  it should "create a correct DavConfig for the source structure if OAuth properties are defined" in {
    val args = Map(
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavModifiedProperty) -> LastModifiedProperty,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavModifiedNamespace) -> LastModifiedNamespace,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavDeleteBeforeOverride) -> "true",
      SourceStructureType.configPropertyName(OAuthParameterManager.StoragePathOptionName) -> StoragePath,
      SourceStructureType.configPropertyName(OAuthParameterManager.NameOptionName) -> IdpName,
      SourceStructureType.configPropertyName(OAuthParameterManager.PasswordOptionName) -> Password
    )
    val expAccessedKeys = args.keySet +
      SourceStructureType.configPropertyName(OAuthParameterManager.EncryptOptionName)
    val syncFactory = new SyncComponentsFactory(createMockOAuthStorageService())

    val (processedArgs, srcFactory) = futureResult(syncFactory.createSourceComponentsFactory(PrefixDavRootUri,
      TestTimeout, args))
    processedArgs.accessedParameters should be(expAccessedKeys)
    val config = extractDavSourceConfig(srcFactory)
    config.optBasicAuthConfig should be(None)
    val oauthConfig = config.optOAuthConfig.get
    oauthConfig.baseName should be(IdpName)
    oauthConfig.optPassword.get.secret should be(Password)
    oauthConfig.rootDir.toString should be(StoragePath)
  }

  it should "fail parsing parameters if properties for both basic auth and OAuth are set" in {
    val args = Map(
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavModifiedProperty) -> LastModifiedProperty,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavModifiedNamespace) -> LastModifiedNamespace,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavDeleteBeforeOverride) -> "true",
      SourceStructureType.configPropertyName(OAuthParameterManager.StoragePathOptionName) -> StoragePath,
      SourceStructureType.configPropertyName(OAuthParameterManager.NameOptionName) -> IdpName,
      SourceStructureType.configPropertyName(OAuthParameterManager.PasswordOptionName) -> Password,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavUser) -> User
    )
    val syncFactory = new SyncComponentsFactory(createMockOAuthStorageService())

    val exception = expectFailedFuture[IllegalArgumentException] {
      for {
        (params, _) <- syncFactory.createSourceComponentsFactory(PrefixDavRootUri, TestTimeout, args)
        params2 <- ParameterManager.checkParametersConsumed(params)
      } yield params2
    }
    exception.getMessage should include(SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavUser))
  }

  it should "create a correct DavConfig for the destination structure" in {
    val args = Map(DestinationStructureType.configPropertyName(SyncComponentsFactory.PropDavUser) -> User,
      DestinationStructureType.configPropertyName(SyncComponentsFactory.PropDavPassword) -> Password,
      DestinationStructureType.configPropertyName(SyncComponentsFactory.PropDavModifiedProperty) ->
        LastModifiedProperty,
      DestinationStructureType.configPropertyName(SyncComponentsFactory.PropDavModifiedNamespace) ->
        LastModifiedNamespace,
      DestinationStructureType.configPropertyName(SyncComponentsFactory.PropDavDeleteBeforeOverride) -> "true")
    val expAccessedKeys = args.keySet +
      DestinationStructureType.configPropertyName(OAuthParameterManager.NameOptionName)
    val syncFactory = new SyncComponentsFactory

    val (processedArgs, srcFactory) = futureResult(syncFactory.createDestinationComponentsFactory(PrefixDavRootUri,
      TestTimeout, args))
    processedArgs.accessedParameters should be(expAccessedKeys)
    srcFactory match {
      case davFactory: DavComponentsDestinationFactory =>
        davFactory.config.rootUri should be(Uri(DavRootUri))
        davFactory.config.optBasicAuthConfig.get.user should be(User)
        davFactory.config.optBasicAuthConfig.get.password.secret should be(Password)
        davFactory.config.lastModifiedProperty should be(LastModifiedProperty)
        davFactory.config.lastModifiedNamespace should be(Some(LastModifiedNamespace))
        davFactory.config.deleteBeforeOverride shouldBe true
        davFactory.config.timeout should be(TestTimeout)
        davFactory.httpActorFactory.httpRequestActorProps should be(HttpRequestActor(DavRootUri))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "read the Dav password from the console if it is not specified" in {
    val args = Map(SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavUser) -> User)
    val Password = "$ecretPwd"
    val reader = mock[ConsoleReader]
    val propPwd = SyncComponentsFactory.SourceStructureType.configPropertyName(
      SyncComponentsFactory.PropDavPassword)
    when(reader.readOption(propPwd, password = true)).thenReturn(Password)
    val syncFactory = new SyncComponentsFactory

    val (processedArgs, srcFactory) = futureResult(syncFactory.createSourceComponentsFactory(PrefixDavRootUri,
      TestTimeout, args)(system, mat, system.dispatcher, reader))
    processedArgs.accessedParameters should contain(propPwd)
    val config = extractDavSourceConfig(srcFactory)
    config.optBasicAuthConfig.get.user should be(User)
    config.optBasicAuthConfig.get.password.secret should be(Password)
  }

  it should "create a correct OneDriveConfig for the source structure with basic auth properties" in {
    val ChunkSize = 42
    val args = Map(SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavUser) -> User,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavPassword) -> Password,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropOneDriveServer) -> DavRootUri,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropOneDrivePath) -> StoragePath,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropOneDriveUploadChunkSize) -> ChunkSize.toString)
    val expAccessedKeys = args.keySet + SourceStructureType.configPropertyName(OAuthParameterManager.NameOptionName)
    val syncFactory = new SyncComponentsFactory

    val (processedArgs, srcFactory) = futureResult(syncFactory.createSourceComponentsFactory(PrefixOneDriveID,
      TestTimeout, args))
    processedArgs.accessedParameters should be(expAccessedKeys)
    val config = extractOneDriveSourceConfig(srcFactory)
    config.rootUri.toString() should be(s"$DavRootUri/$OneDriveID/root:$StoragePath")
    config.uploadChunkSize should be(ChunkSize * 1024 * 1024)
    config.timeout should be(TestTimeout)
    config.optBasicAuthConfig.get.user should be(User)
    config.optBasicAuthConfig.get.password.secret should be(Password)
    config.optOAuthConfig should be(None)
  }

  it should "create a correct OneDriveConfig for the source structure with defaults" in {
    val args = Map(SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavUser) -> User,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavPassword) -> Password,
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropOneDrivePath) -> StoragePath)
    val syncFactory = new SyncComponentsFactory

    val (_, srcFactory) = futureResult(syncFactory.createSourceComponentsFactory(PrefixOneDriveID,
      TestTimeout, args))
    val config = extractOneDriveSourceConfig(srcFactory)
    config.rootUri.toString() should be(s"${OneDriveConfig.OneDriveServerUri}/$OneDriveID/root:$StoragePath")
    config.uploadChunkSize should be(OneDriveConfig.DefaultUploadChunkSizeMB * 1024 * 1024)
  }

  it should "generate failure messages for all invalid properties of a OneDrive configuration" in {
    val args = Map(SourceStructureType.configPropertyName(SyncComponentsFactory.PropOneDriveUploadChunkSize) -> "xx",
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavPassword) -> Password)
    val syncFactory = new SyncComponentsFactory

    val exception = expectFailedFuture[IllegalArgumentException] {
      syncFactory.createSourceComponentsFactory(PrefixOneDriveID, TestTimeout, args)
    }
    exception.getMessage should include(SourceStructureType.configPropertyName(
      SyncComponentsFactory.PropOneDriveUploadChunkSize))
    exception.getMessage should include(SourceStructureType.configPropertyName(SyncComponentsFactory.PropDavUser))
    exception.getMessage should include(SourceStructureType.configPropertyName(
      SyncComponentsFactory.PropOneDrivePath))
  }

  it should "create a correct OneDriveConfig for the source structure if OAuth properties are defined" in {
    val args = Map(
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropOneDrivePath) -> "/a-path",
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropOneDriveUploadChunkSize) -> "4",
      SourceStructureType.configPropertyName(OAuthParameterManager.StoragePathOptionName) -> StoragePath,
      SourceStructureType.configPropertyName(OAuthParameterManager.NameOptionName) -> IdpName,
      SourceStructureType.configPropertyName(OAuthParameterManager.PasswordOptionName) -> Password
    )
    val expAccessedKeys = args.keySet +
      SourceStructureType.configPropertyName(OAuthParameterManager.EncryptOptionName) +
      SourceStructureType.configPropertyName(SyncComponentsFactory.PropOneDriveServer)
    val syncFactory = new SyncComponentsFactory(createMockOAuthStorageService())

    val (processedArgs, srcFactory) = futureResult(syncFactory.createSourceComponentsFactory(PrefixOneDriveID,
      TestTimeout, args))
    processedArgs.accessedParameters should be(expAccessedKeys)
    val config = extractOneDriveSourceConfig(srcFactory)
    config.optBasicAuthConfig should be(None)
    val oauthConfig = config.optOAuthConfig.get
    oauthConfig.baseName should be(IdpName)
    oauthConfig.optPassword.get.secret should be(Password)
    oauthConfig.rootDir.toString should be(StoragePath)
  }

  it should "create a correct OneDriveConfig for the destination structure" in {
    val ChunkSize = 11
    val args = Map(DestinationStructureType.configPropertyName(SyncComponentsFactory.PropDavUser) -> User,
      DestinationStructureType.configPropertyName(SyncComponentsFactory.PropDavPassword) -> Password,
      DestinationStructureType.configPropertyName(SyncComponentsFactory.PropOneDriveServer) ->
        DavRootUri,
      DestinationStructureType.configPropertyName(SyncComponentsFactory.PropOneDrivePath) ->
        StoragePath,
      DestinationStructureType.configPropertyName(SyncComponentsFactory.PropOneDriveUploadChunkSize) ->
        ChunkSize.toString)
    val expAccessedKeys = args.keySet +
      DestinationStructureType.configPropertyName(OAuthParameterManager.NameOptionName)
    val syncFactory = new SyncComponentsFactory

    val (processedArgs, srcFactory) = futureResult(syncFactory.createDestinationComponentsFactory(PrefixOneDriveID,
      TestTimeout, args))
    processedArgs.accessedParameters should be(expAccessedKeys)
    srcFactory match {
      case oneFactory: OneDriveComponentsDestinationFactory =>
        oneFactory.config.rootUri.toString() should be(s"$DavRootUri/$OneDriveID/root:$StoragePath")
        oneFactory.config.optBasicAuthConfig.get.user should be(User)
        oneFactory.config.optBasicAuthConfig.get.password.secret should be(Password)
        oneFactory.config.uploadChunkSize should be(ChunkSize * 1024 * 1024)
        oneFactory.config.timeout should be(TestTimeout)
        oneFactory.httpActorFactory.httpRequestActorProps should be(HttpMultiHostRequestActor(
          SyncComponentsFactory.OneDriveHostCacheSize, 1))
      case r => fail("Unexpected result: " + r)
    }
  }
}
