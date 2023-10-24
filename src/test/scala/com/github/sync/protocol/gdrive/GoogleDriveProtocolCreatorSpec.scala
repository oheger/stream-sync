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

package com.github.sync.protocol.gdrive

import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.gdrive.{GoogleDriveConfig, GoogleDriveFileSystem}
import com.github.sync.protocol.config.GoogleDriveStructureConfig
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.Timeout
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration.*

class GoogleDriveProtocolCreatorSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "GoogleDriveProtocolCreator" should "create a correct file system" in {
    val SyncPath = "/my/sync/path"
    val ServerUri = "https://google-drive.example.org"
    val SyncTimeout = Timeout(43.seconds)
    val structConfig = GoogleDriveStructureConfig(optServerUri = Some(ServerUri))
    val expConfig = GoogleDriveConfig(serverUri = ServerUri, optRootPath = Some(SyncPath.drop(1)),
      timeout = SyncTimeout)

    GoogleDriveProtocolCreator.createFileSystem("googledrive:" + SyncPath, structConfig, SyncTimeout) match
      case fs: GoogleDriveFileSystem =>
        fs.config should be(expConfig)
      case fs => fail("Unexpected file system: " + fs)
  }

  it should "create a correct file system with default values" in {
    val structConfig = GoogleDriveStructureConfig(optServerUri = None)

    GoogleDriveProtocolCreator.createFileSystem("googledrive:", structConfig, Timeout(30.seconds)) match
      case fs: GoogleDriveFileSystem =>
        fs.config.serverUri should be(GoogleDriveConfig.GoogleDriveServerUri)
        fs.config.optRootPath should be(None)
      case fs => fail("Unexpected file system: " + fs)
  }

  it should "handle a missing googledrive: prefix when creating the file system" in {
    val SyncPath = "/the/path"

    GoogleDriveProtocolCreator.createFileSystem(SyncPath, GoogleDriveStructureConfig(None), Timeout(2.seconds)) match
      case fs: GoogleDriveFileSystem =>
        fs.config.optRootPath should be(Some(SyncPath.drop(1)))
      case fs => fail("Unexpected file system: " + fs)
  }

  it should "create a correct HTTP sender actor" in {
    val spawner = mock[Spawner]
    val factory = mock[HttpRequestSenderFactory]
    val senderConfig = mock[HttpRequestSenderConfig]
    val httpSender = mock[ActorRef[HttpRequestSender.HttpCommand]]
    val ServerUri = "https://google.example.org"
    val structConfig = GoogleDriveStructureConfig(optServerUri = Some(ServerUri))
    when(factory.createRequestSender(spawner, ServerUri, senderConfig)).thenReturn(httpSender)

    GoogleDriveProtocolCreator.createHttpSender(spawner, factory, "uri", structConfig,
      senderConfig) should be(httpSender)
  }

  it should "create a correct HTTP sender actor for the default GoogleDrive API" in {
    val spawner = mock[Spawner]
    val factory = mock[HttpRequestSenderFactory]
    val senderConfig = mock[HttpRequestSenderConfig]
    val httpSender = mock[ActorRef[HttpRequestSender.HttpCommand]]
    val structConfig = GoogleDriveStructureConfig(optServerUri = None)
    when(factory.createRequestSender(spawner, GoogleDriveConfig.GoogleDriveServerUri, senderConfig))
      .thenReturn(httpSender)

    GoogleDriveProtocolCreator.createHttpSender(spawner, factory, "uri", structConfig,
      senderConfig) should be(httpSender)
  }

  it should "create a correct converter" in {
    val config = GoogleDriveStructureConfig(None)

    GoogleDriveProtocolCreator.createConverter(config) should be(GoogleDriveProtocolConverter)
  }
