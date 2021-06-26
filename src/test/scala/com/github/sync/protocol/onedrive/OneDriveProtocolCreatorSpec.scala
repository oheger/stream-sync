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

package com.github.sync.protocol.onedrive

import akka.actor.typed.ActorRef
import akka.util.Timeout
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.onedrive.{OneDriveConfig, OneDriveFileSystem}
import com.github.sync.protocol.config.OneDriveStructureConfig
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

/**
  * Test class for ''OneDriveProtocolCreator''.
  */
class OneDriveProtocolCreatorSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "OneDriveProtocolCreator" should "create a correct file system" in {
    val DriveId = "myDriveID"
    val SyncPath = "/path/to/sync"
    val ServerUri = "https://alternative.server.example.org"
    val ChunkSize = 8
    val SyncTimeout = Timeout(27.seconds)
    val structConfig = OneDriveStructureConfig(syncPath = SyncPath, optServerUri = Some(ServerUri),
      optUploadChunkSizeMB = Some(ChunkSize))
    val expConfig = OneDriveConfig(driveID = DriveId, serverUri = ServerUri, optRootPath = Some(SyncPath),
      uploadChunkSize = ChunkSize * 1024 * 1024, timeout = SyncTimeout)

    OneDriveProtocolCreator.createFileSystem(DriveId, structConfig, SyncTimeout) match {
      case fs: OneDriveFileSystem =>
        fs.config should be(expConfig)
      case fs => fail("Unexpected file system: " + fs)
    }
  }

  it should "create a correct file system with default properties" in {
    val structConfig = OneDriveStructureConfig(syncPath = "/some/path", optServerUri = None,
      optUploadChunkSizeMB = None)

    OneDriveProtocolCreator.createFileSystem("SomeDriveID", structConfig, Timeout(1.minute)) match {
      case fs: OneDriveFileSystem =>
        fs.config.serverUri should be(OneDriveConfig.OneDriveServerUri)
        fs.config.uploadChunkSize should be(OneDriveConfig.DefaultUploadChunkSize)
      case fs => fail("Unexpected file system: " + fs)
    }
  }

  it should "create a correct file system if the driveID has the onedrive prefix" in {
    val structConfig = OneDriveStructureConfig(syncPath = "/some/path", optServerUri = None,
      optUploadChunkSizeMB = None)
    val DriveID = "theRealDriveID"

    OneDriveProtocolCreator.createFileSystem("onedrive:" + DriveID, structConfig, Timeout(1.minute)) match {
      case fs: OneDriveFileSystem =>
        fs.config.driveID should be(DriveID)
      case fs => fail("Unexpected file system: " + fs)
    }
  }

  it should "create a correct HTTP sender actor" in {
    val spawner = mock[Spawner]
    val factory = mock[HttpRequestSenderFactory]
    val senderConfig = mock[HttpRequestSenderConfig]
    val httpSender = mock[ActorRef[HttpRequestSender.HttpCommand]]
    val uri = "OneDriveUri"
    val structConfig = OneDriveStructureConfig(syncPath = "/some/path", optServerUri = None,
      optUploadChunkSizeMB = None)
    when(factory.createMultiHostRequestSender(spawner, senderConfig)).thenReturn(httpSender)

    OneDriveProtocolCreator.createHttpSender(spawner, factory, uri, structConfig, senderConfig) should be(httpSender)
  }

  it should "create a correct converter" in {
    OneDriveProtocolCreator.createConverter(null) should be(OneDriveProtocolConverter)
  }
}
