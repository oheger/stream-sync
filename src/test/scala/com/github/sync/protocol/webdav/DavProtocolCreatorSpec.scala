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

package com.github.sync.protocol.webdav

import akka.actor.typed.ActorRef
import akka.util.Timeout
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.webdav.{DavConfig, DavFileSystem}
import com.github.sync.protocol.config.DavStructureConfig
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

object DavProtocolCreatorSpec {
  /** The URI of the dav server used by tests. */
  private val TestUri = "https://dav.example.org/data"

  /** A test configuration for a DAV server. */
  private val TestConfig = DavStructureConfig(deleteBeforeOverride = true, optLastModifiedProperty = Some("modified"),
    optLastModifiedNamespace = None)

  /** A test timeout value. */
  private val TestTimeout = Timeout(44.seconds)
}

/**
  * Test class for ''DavProtocolCreator''.
  */
class DavProtocolCreatorSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import DavProtocolCreatorSpec._

  "DavProtocolCreator" should "create a correct file system" in {
    val expDavConfig = DavConfig(rootUri = TestUri, deleteBeforeOverride = TestConfig.deleteBeforeOverride,
      timeout = TestTimeout)

    DavProtocolCreator.createFileSystem(TestUri, TestConfig, TestTimeout) match {
      case fs: DavFileSystem =>
        fs.config should be(expDavConfig)
      case fs => fail("Unexpected file system: " + fs)
    }
  }

  it should "create a correct HTTP sender actor" in {
    val spawner = mock[Spawner]
    val factory = mock[HttpRequestSenderFactory]
    val senderConfig = mock[HttpRequestSenderConfig]
    val sender = mock[ActorRef[HttpRequestSender.HttpCommand]]
    when(factory.createRequestSender(spawner, TestUri, senderConfig)).thenReturn(sender)

    DavProtocolCreator.createHttpSender(spawner, factory, TestUri, TestConfig, senderConfig) should be(sender)
  }

  it should "create a correct protocol converter" in {
    DavProtocolCreator.createConverter(TestConfig) match {
      case davConverter: DavProtocolConverter =>
        davConverter.davConfig should be(TestConfig)
      case c => fail("Unexpected converter: " + c)
    }
  }
}
