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

package com.github.sync.protocol.webdav

import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.webdav.{DavConfig, DavFileSystem, DavModel, DavParser}
import com.github.sync.protocol.config.DavStructureConfig
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.Timeout
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration.*

object DavProtocolCreatorSpec:
  /** The URI of the dav server used by tests. */
  private val TestUri = "https://dav.example.org/data"

  /** A test configuration for a DAV server. */
  private val TestConfig = DavStructureConfig(deleteBeforeOverride = true, optLastModifiedProperty = None,
    optLastModifiedNamespace = None)

  /** A test timeout value. */
  private val TestTimeout = Timeout(44.seconds)

/**
  * Test class for ''DavProtocolCreator''.
  */
class DavProtocolCreatorSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import DavProtocolCreatorSpec.*

  "DavProtocolCreator" should "create a correct file system if no modified property is set" in {
    val expDavConfig = DavConfig(rootUri = TestUri, deleteBeforeOverride = TestConfig.deleteBeforeOverride,
      timeout = TestTimeout)

    DavProtocolCreator.createFileSystem(TestUri, TestConfig, TestTimeout) match
      case fs: DavFileSystem =>
        fs.config should be(expDavConfig)
      case fs => fail("Unexpected file system: " + fs)
  }

  it should "create a correct file system if a modified property is set" in {
    val ModifiedProperty = "itWasModifiedAt"
    val config = TestConfig.copy(optLastModifiedProperty = Some(ModifiedProperty))
    val expDavConfig = DavConfig(rootUri = TestUri, deleteBeforeOverride = TestConfig.deleteBeforeOverride,
      timeout = TestTimeout, additionalAttributes = List(DavModel.AttributeKey(DavParser.NS_DAV, ModifiedProperty)))

    DavProtocolCreator.createFileSystem(TestUri, config, TestTimeout) match
      case fs: DavFileSystem =>
        fs.config should be(expDavConfig)
      case fs => fail("Unexpected file system: " + fs)
  }

  it should "create a correct file system if a modified property and namespace are set" in {
    val ModifiedProperty = "itWasModifiedAt"
    val ModifiedNamespace = "modifications"
    val config = TestConfig.copy(optLastModifiedProperty = Some(ModifiedProperty),
      optLastModifiedNamespace = Some(ModifiedNamespace))
    val expDavConfig = DavConfig(rootUri = TestUri, deleteBeforeOverride = TestConfig.deleteBeforeOverride,
      timeout = TestTimeout, additionalAttributes = List(DavModel.AttributeKey(ModifiedNamespace, ModifiedProperty)))

    DavProtocolCreator.createFileSystem(TestUri, config, TestTimeout) match
      case fs: DavFileSystem =>
        fs.config should be(expDavConfig)
      case fs => fail("Unexpected file system: " + fs)
  }

  it should "remove the dav: prefix from the URI when creating the file system" in {
    val expDavConfig = DavConfig(rootUri = TestUri, deleteBeforeOverride = TestConfig.deleteBeforeOverride,
      timeout = TestTimeout)

    DavProtocolCreator.createFileSystem("dav:" + TestUri, TestConfig, TestTimeout) match
      case fs: DavFileSystem =>
        fs.config should be(expDavConfig)
      case fs => fail("Unexpected file system: " + fs)
  }

  it should "create a correct HTTP sender actor" in {
    val spawner = mock[Spawner]
    val factory = mock[HttpRequestSenderFactory]
    val senderConfig = mock[HttpRequestSenderConfig]
    val sender = mock[ActorRef[HttpRequestSender.HttpCommand]]
    when(factory.createRequestSender(spawner, TestUri, senderConfig)).thenReturn(sender)

    DavProtocolCreator.createHttpSender(spawner, factory, TestUri, TestConfig, senderConfig) should be(sender)
  }

  it should "remove the dav: prefix from the URI when creating the HTTP sender actor" in {
    val spawner = mock[Spawner]
    val factory = mock[HttpRequestSenderFactory]
    val senderConfig = mock[HttpRequestSenderConfig]
    val sender = mock[ActorRef[HttpRequestSender.HttpCommand]]
    when(factory.createRequestSender(spawner, TestUri, senderConfig)).thenReturn(sender)

    DavProtocolCreator.createHttpSender(spawner, factory, "dav:" + TestUri, TestConfig,
      senderConfig) should be(sender)
  }

  it should "create a correct protocol converter if no modified property is set" in {
    DavProtocolCreator.createConverter(TestConfig) match
      case davConverter: DavProtocolConverter =>
        davConverter.davConfig should be(TestConfig)
        davConverter.optModifiedAttribute shouldBe empty
      case c => fail("Unexpected converter: " + c)
  }

  it should "create a correct protocol converter if a modified property is set" in {
    val ModifiedProperty = "modifiedAt"
    val config = TestConfig.copy(optLastModifiedProperty = Some(ModifiedProperty))

    DavProtocolCreator.createConverter(config) match
      case davConverter: DavProtocolConverter =>
        davConverter.optModifiedAttribute should be(Some(DavModel.AttributeKey(DavParser.NS_DAV, ModifiedProperty)))
      case c => fail("Unexpected converter: " + c)
  }

  it should "create a correct protocol converter if a modified property and namespace are set" in {
    val ModifiedNamespace = "modifiedNS"
    val ModifiedProperty = "modifiedAt"
    val config = TestConfig.copy(optLastModifiedProperty = Some(ModifiedProperty),
      optLastModifiedNamespace = Some(ModifiedNamespace))

    DavProtocolCreator.createConverter(config) match
      case davConverter: DavProtocolConverter =>
        davConverter.optModifiedAttribute should be(Some(DavModel.AttributeKey(ModifiedNamespace, ModifiedProperty)))
      case c => fail("Unexpected converter: " + c)
  }
