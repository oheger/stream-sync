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

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import akka.util.Timeout
import com.github.cloudfiles.core.http.Secret
import com.github.sync.AsyncTestHelper
import com.github.sync.cli.FilterManager.SyncFilterData
import com.github.sync.cli.SyncComponentsFactory.SourceComponentsFactory
import com.github.sync.cli.SyncParameterManager.{CryptConfig, SyncConfig}
import com.github.sync.cli.SyncCliStructureConfig.StructureAuthConfig
import com.github.sync.http._
import com.github.sync.local.LocalFsConfig
import com.github.sync.onedrive.OneDriveConfig
import com.github.sync.protocol.config.{DavStructureConfig, FsStructureConfig, OneDriveStructureConfig}
import com.github.sync.webdav.DavConfig
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.time.ZoneId
import scala.concurrent.duration._
import scala.language.implicitConversions

object SyncComponentsFactorySpec {
  /** A timeout value which is required to create sub factories. */
  private val TestTimeout: Timeout = Timeout(10.seconds)

  /** Test URI for a WebDav server. */
  private val DavRootUri = "https://my-test-server.org"

  /** The URI of the test WebDav server with the prefix indicating a Dav URI. */
  private val PrefixDavRootUri = SyncComponentsFactory.PrefixWebDav + DavRootUri

  /** A test authentication configuration. */
  private val TestAuthConfig = SyncBasicAuthConfig("scott", Secret("tiger"))

  /** A test structure config to be used if no special one is set. */
  private val TestStructureConfig = StructureAuthConfig(FsStructureConfig(None), SyncNoAuth)

  /** A test OneDrive drive ID. */
  private val OneDriveID = "my-drive"

  /**
    * Creates a ''SyncConfig'' with default values, but allows adjusting the
    * data related to the sync structures. Test cases typically need to change
    * only the settings for one role type.
    *
    * @param optSrcUri    optional URI of the source structure
    * @param optSrcConfig optional configuration of the source structure
    * @param optDstUri    optional URI of the destination structure
    * @param optDstConfig optional configuration of the destination structure
    * @return the resulting ''SyncConfig''
    */
  private def syncConfig(optSrcUri: Option[String] = None, optSrcConfig: Option[StructureAuthConfig] = None,
                         optDstUri: Option[String] = None, optDstConfig: Option[StructureAuthConfig] = None):
  SyncConfig = {
    val cryptConfig = CryptConfig(srcPassword = None, dstPassword = None,
      srcCryptMode = SyncParameterManager.CryptMode.None, dstCryptMode = SyncParameterManager.CryptMode.None,
      cryptCacheSize = 17)
    SyncConfig(srcUri = optSrcUri.getOrElse("/source"), srcConfig = optSrcConfig.getOrElse(TestStructureConfig),
      dstUri = optDstUri.getOrElse("/target"), dstConfig = optDstConfig.getOrElse(TestStructureConfig),
      dryRun = false, timeout = TestTimeout, logFilePath = None, syncLogPath = None, ignoreTimeDelta = None,
      opsPerSecond = None, cryptConfig = cryptConfig, filterData = SyncFilterData(Map.empty), switched = false)
  }
}

/**
  * Test class for ''SyncComponentsFactory''. This class tests only a limited
  * subset of the functionality provided by the class. The remaining part is
  * tested by integration tests.
  */
class SyncComponentsFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper with MockitoSugar {
  def this() = this(ActorSystem("SyncComponentsFactorySpec"))

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit shutdownActorSystem system
  }

  import SyncComponentsFactorySpec._
  import system.dispatcher

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

  "SyncComponentsFactory" should "create a correct file system config for the source structure" in {
    val TimeZoneId = ZoneId of "UTC+02:00"
    val uri = "/my/sync/dir"
    val config = syncConfig(optSrcUri = Some(uri),
      optSrcConfig = Some(StructureAuthConfig(FsStructureConfig(Some(TimeZoneId)), SyncNoAuth)))
    val syncFactory = new SyncComponentsFactory

    val sourceFactory = futureResult(syncFactory.createSourceComponentsFactory(config))
    extractLocalFsSourceConfig(sourceFactory) should be(LocalFsConfig(Paths.get(uri), Some(TimeZoneId)))
  }

  it should "create a correct file system config for the destination structure" in {
    val TimeZoneId = ZoneId of "UTC-02:00"
    val uri = "/my/sync/target"
    val config = syncConfig(optDstUri = Some(uri),
      optDstConfig = Some(StructureAuthConfig(FsStructureConfig(Some(TimeZoneId)), SyncNoAuth)))
    val syncFactory = new SyncComponentsFactory

    val destFactory = futureResult(syncFactory.createDestinationComponentsFactory(config))
    destFactory match {
      case localFactory: LocalFsDestinationComponentsFactory =>
        localFactory.config should be(LocalFsConfig(Paths.get(uri), Some(TimeZoneId)))
        localFactory.timeout should be(TestTimeout)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "create a correct DavConfig for the source structure" in {
    val davStructConfig = DavStructureConfig(optLastModifiedNamespace = Some("lastModifiedNS"),
      optLastModifiedProperty = Some("lastModifiedProp"), deleteBeforeOverride = true)
    val config = syncConfig(optSrcUri = Some(PrefixDavRootUri),
      optSrcConfig = Some(StructureAuthConfig(davStructConfig, TestAuthConfig)))
    val syncFactory = new SyncComponentsFactory

    val srcFactory = futureResult(syncFactory.createSourceComponentsFactory(config))
    val davConfig = extractDavSourceConfig(srcFactory)
    davConfig.rootUri should be(Uri(DavRootUri))
    davConfig.authConfig should be(TestAuthConfig)
    davConfig.lastModifiedProperty should be(davStructConfig.optLastModifiedProperty.get)
    davConfig.lastModifiedNamespace should be(davStructConfig.optLastModifiedNamespace)
    davConfig.deleteBeforeOverride shouldBe davStructConfig.deleteBeforeOverride
    davConfig.timeout should be(TestTimeout)
  }

  it should "create a correct DavConfig for the source structure with defaults" in {
    val davStructConfig = DavStructureConfig(optLastModifiedNamespace = None, optLastModifiedProperty = None,
      deleteBeforeOverride = false)
    val config = syncConfig(optSrcUri = Some(PrefixDavRootUri),
      optSrcConfig = Some(StructureAuthConfig(davStructConfig, TestAuthConfig)))
    val syncFactory = new SyncComponentsFactory

    val srcFactory = futureResult(syncFactory.createSourceComponentsFactory(config))
    val davConfig = extractDavSourceConfig(srcFactory)
    davConfig.lastModifiedProperty should be(DavConfig.DefaultModifiedProperty)
    davConfig.lastModifiedNamespace should be(None)
    davConfig.deleteBeforeOverride shouldBe false
  }

  it should "handle an invalid structure URI when creating a factory for a DAV structure" in {
    val davStructConfig = DavStructureConfig(optLastModifiedNamespace = None, optLastModifiedProperty = None,
      deleteBeforeOverride = false)
    val config = syncConfig(optSrcUri = Some("not a DAV uri"),
      optSrcConfig = Some(StructureAuthConfig(davStructConfig, TestAuthConfig)))
    val syncFactory = new SyncComponentsFactory

    val ex = expectFailedFuture[IllegalArgumentException](syncFactory.createSourceComponentsFactory(config))
    ex.getMessage should include(config.srcUri)
  }

  it should "create a correct DavConfig for the destination structure" in {
    val davStructConfig = DavStructureConfig(optLastModifiedNamespace = Some("lastModifiedNS"),
      optLastModifiedProperty = Some("lastModifiedProp"), deleteBeforeOverride = true)
    val config = syncConfig(optDstUri = Some(PrefixDavRootUri),
      optDstConfig = Some(StructureAuthConfig(davStructConfig, TestAuthConfig)))
    val syncFactory = new SyncComponentsFactory

    val dstFactory = futureResult(syncFactory.createDestinationComponentsFactory(config))
    dstFactory match {
      case davFactory: DavComponentsDestinationFactory =>
        davFactory.config.rootUri should be(Uri(DavRootUri))
        davFactory.config.authConfig should be(TestAuthConfig)
        davFactory.config.lastModifiedProperty should be(davStructConfig.optLastModifiedProperty.get)
        davFactory.config.lastModifiedNamespace should be(davStructConfig.optLastModifiedNamespace)
        davFactory.config.deleteBeforeOverride shouldBe true
        davFactory.config.timeout should be(TestTimeout)
        davFactory.httpActorFactory.httpRequestActorProps should be(HttpRequestActor(DavRootUri))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "create a correct OneDriveConfig for the source structure" in {
    val SyncPath = "/path/to/sync"
    val oneStructConfig = OneDriveStructureConfig(syncPath = SyncPath, optServerUri = Some(DavRootUri),
      optUploadChunkSizeMB = Some(42))
    val config = syncConfig(optSrcUri = Some(SyncCliStructureConfig.PrefixOneDrive + OneDriveID),
      optSrcConfig = Some(StructureAuthConfig(oneStructConfig, TestAuthConfig)))
    val syncFactory = new SyncComponentsFactory

    val srcFactory = futureResult(syncFactory.createSourceComponentsFactory(config))
    val oneConfig = extractOneDriveSourceConfig(srcFactory)
    oneConfig.rootUri.toString() should be(s"$DavRootUri/$OneDriveID/root:$SyncPath")
    oneConfig.uploadChunkSize should be(oneStructConfig.optUploadChunkSizeMB.get * 1024 * 1024)
    oneConfig.timeout should be(TestTimeout)
    oneConfig.authConfig should be(TestAuthConfig)
  }

  it should "create a correct OneDriveConfig for the source structure with defaults" in {
    val SyncPath = "/path/to/sync"
    val oneStructConfig = OneDriveStructureConfig(syncPath = SyncPath, optServerUri = None,
      optUploadChunkSizeMB = None)
    val config = syncConfig(optSrcUri = Some(SyncCliStructureConfig.PrefixOneDrive + OneDriveID),
      optSrcConfig = Some(StructureAuthConfig(oneStructConfig, TestAuthConfig)))
    val syncFactory = new SyncComponentsFactory

    val srcFactory = futureResult(syncFactory.createSourceComponentsFactory(config))
    val oneConfig = extractOneDriveSourceConfig(srcFactory)
    oneConfig.rootUri.toString() should be(s"${OneDriveConfig.OneDriveServerUri}/$OneDriveID/root:$SyncPath")
    oneConfig.uploadChunkSize should be(OneDriveConfig.DefaultUploadChunkSizeMB * 1024 * 1024)
  }

  it should "handle an invalid structure URI when creating a factory for a OneDrive structure" in {
    val oneStructConfig = OneDriveStructureConfig(syncPath = "somePath", optServerUri = None,
      optUploadChunkSizeMB = None)
    val config = syncConfig(optSrcUri = Some("not a OneDrive URI"),
      optSrcConfig = Some(StructureAuthConfig(oneStructConfig, TestAuthConfig)))
    val syncFactory = new SyncComponentsFactory

    val ex = expectFailedFuture[IllegalArgumentException](syncFactory.createSourceComponentsFactory(config))
    ex.getMessage should include(config.srcUri)
  }

  it should "create a correct OneDriveConfig for the destination structure" in {
    val SyncPath = "/path/to/dest/sync"
    val oneStructConfig = OneDriveStructureConfig(syncPath = SyncPath, optServerUri = Some(DavRootUri),
      optUploadChunkSizeMB = Some(11))
    val config = syncConfig(optDstUri = Some(SyncCliStructureConfig.PrefixOneDrive + OneDriveID),
      optDstConfig = Some(StructureAuthConfig(oneStructConfig, TestAuthConfig)))
    val syncFactory = new SyncComponentsFactory

    val srcFactory = futureResult(syncFactory.createDestinationComponentsFactory(config))
    srcFactory match {
      case oneFactory: OneDriveComponentsDestinationFactory =>
        oneFactory.config.rootUri.toString() should be(s"$DavRootUri/$OneDriveID/root:$SyncPath")
        oneFactory.config.authConfig should be(TestAuthConfig)
        oneFactory.config.uploadChunkSize should be(oneStructConfig.optUploadChunkSizeMB.get * 1024 * 1024)
        oneFactory.config.timeout should be(TestTimeout)
        oneFactory.httpActorFactory.httpRequestActorProps should be(HttpMultiHostRequestActor(
          SyncComponentsFactory.OneDriveHostCacheSize, 1))
      case r => fail("Unexpected result: " + r)
    }
  }
}
