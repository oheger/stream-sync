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

package com.github.sync.protocol

import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.core.{FileSystem, Model}
import com.github.cloudfiles.crypt.alg.aes.Aes
import com.github.cloudfiles.crypt.fs.{CryptConfig, CryptContentFileSystem, CryptNamesFileSystem}
import com.github.sync.protocol.config.{StructureConfig, StructureCryptConfig}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.util.Timeout
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

object FileSystemSyncProtocolFactorySpec:
  /** The protocol-specific URI used by tests. */
  private val ProtocolUri = "https://test.protocol.example.org"

  /** A test configuration for the HTTP sender actor. */
  private val SenderConfig = HttpRequestSenderConfig(queueSize = 42)

  /** A test password to use for encryption. */
  private val CryptPassword = "#CryptPwd!"

  /** The key for encrypt operations derived from the test password. */
  private val CryptKey = Aes.keyFromString(CryptPassword)

  /** Constant for a file system timeout. */
  private val FsTimeout = Timeout(2.minutes)

/**
  * Test class for ''FileSystemSyncProtocolFactory''.
  */
class FileSystemSyncProtocolFactorySpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with MockitoSugar:

  import FileSystemSyncProtocolFactorySpec.*

  /**
    * Checks whether the given config contains the expected values.
    *
    * @param config the config to be checked
    */
  private def checkCryptConfig(config: CryptConfig): Unit =
    config.algorithm should be(Aes)
    config.keyDecrypt should be(CryptKey)
    config.keyEncrypt should be(CryptKey)
    config.secRandom should not be null

  "FileSystemSyncProtocolFactory" should "create a correct protocol without encryption" in {
    val cryptConfig = StructureCryptConfig(password = None, cryptNames = true, cryptCacheSize = 0)
    val helper = new ProtocolFactoryHelper

    val protocol = helper.createAndCheckProtocol(cryptConfig)
    helper.checkFileSystem(protocol.fileSystem)
    helper.numberOfSpawns should be(0)
  }

  it should "create a file system supporting encrypted file content" in {
    val cryptConfig = StructureCryptConfig(password = Some(CryptPassword), cryptNames = false, cryptCacheSize = 100)
    val helper = new ProtocolFactoryHelper

    val protocol = helper.createAndCheckProtocol(cryptConfig)
    protocol.fileSystem match
      case fs: CryptContentFileSystem[_, _, _] =>
        checkCryptConfig(fs.config)
        helper.checkFileSystem(fs.delegate)
      case o => fail("Unexpected file system: " + o)
    helper.numberOfSpawns should be(0)
  }

  it should "create a file system supporting both encrypted content and element names" in {
    val cryptConfig = StructureCryptConfig(password = Some(CryptPassword), cryptNames = true, cryptCacheSize = 100)
    val helper = new ProtocolFactoryHelper

    val protocol = helper.createAndCheckProtocol(cryptConfig)
    protocol.fileSystem match
      case fsn: CryptNamesFileSystem[_, _, _] =>
        checkCryptConfig(fsn.namesConfig.cryptConfig)
        fsn.namesConfig.ignoreUnencrypted shouldBe true
        fsn.delegate match
          case fsc: CryptContentFileSystem[_, _, _] =>
            checkCryptConfig(fsc.config)
            helper.checkFileSystem(fsc.delegate)
          case o => fail("Unexpected delegate of CryptNamesFileSystem: " + o)
      case o => fail("Unexpected file system: " + o)
    helper.numberOfSpawns should be(1)
  }

  /**
    * A test helper class managing an object to test and its dependencies.
    */
  private class ProtocolFactoryHelper:
    /** A counter for spawn operations. */
    private val spawnCounter = new AtomicInteger

    /** The spawner object. */
    private val spawner = createSpawner()

    /** Mock for the HTTP sender factory. */
    private val senderFactory = mock[HttpRequestSenderFactory]

    /** Mock for the protocol-specific configuration. */
    private val protocolConfig = mock[StructureConfig]

    /** Mock for the file system returned by the creator. */
    private val fileSystem = mock[ExtensibleFileSystem[String, Model.File[String], Model.Folder[String],
      Model.FolderContent[String, Model.File[String], Model.Folder[String]]]]

    /** Mock for the converter returned by the creator. */
    private val converter = mock[FileSystemProtocolConverter[String, Model.File[String], Model.Folder[String]]]

    /** The HTTP sender actor returned by the creator. */
    private val httpSender = testKit.createTestProbe[HttpRequestSender.HttpCommand]().ref

    /** The mock for the protocol creator. */
    private val creator = createCreator()

    /** The factory to be tested. */
    private val factory =
      new FileSystemSyncProtocolFactory[String, Model.File[String], Model.Folder[String],
        StructureConfig](creator, protocolConfig, SenderConfig, FsTimeout, spawner, senderFactory)

    /**
      * Invokes the test factory to create a protocol based on the managed mock
      * dependencies. Checks whether the result contains the expected
      * components, except for the file system, which may be decorated.
      *
      * @param cryptConfig the cryptography-related config to be passed
      * @return the validated protocol
      */
    def createAndCheckProtocol(cryptConfig: StructureCryptConfig):
    FileSystemSyncProtocol[_, _, _] =
      factory.createProtocol(ProtocolUri, cryptConfig) match
        case p: FileSystemSyncProtocol[_, _, _] =>
          p.converter should be(converter)
          p.httpSender should be(httpSender)
          p
        case o => fail("Unexpected protocol: " + o)

    /**
      * Checks whether the passed in file system is the expected base file
      * system for the protocol created by the test factory.
      *
      * @param fs the actual file system to be tested
      */
    def checkFileSystem(fs: FileSystem[_, _, _, _]): Unit =
      fs should be(fileSystem)

    /**
      * Returns the number of actors that have been created via the test
      * ''Spawner''.
      *
      * @return the number of created actors
      */
    def numberOfSpawns: Int = spawnCounter.get()

    /**
      * Creates a new ''Spawner'' instance. This is actually a functional
      * implementation based on the test kit, which also counts invocations.
      * This is done to test whether a resolver for encrypted paths is created.
      * (Unfortunately, there is no access to the actor and its parameters that
      * is created.)
      *
      * @return the ''Spawner'' instance
      */
    private def createSpawner(): Spawner = new Spawner {
      override def spawn[T](behavior: Behavior[T], optName: Option[String], props: Props): ActorRef[T] =
        spawnCounter.incrementAndGet()
        optName should be(None)
        testKit.spawn(behavior)
    }

    /**
      * Creates the mock for the protocol creator and prepares it to handle
      * the expected invocations.
      *
      * @return the ''FileSystemProtocolCreator'' mock
      */
    private def createCreator():
    FileSystemProtocolCreator[String, Model.File[String], Model.Folder[String], StructureConfig] =
      val c = mock[FileSystemProtocolCreator[String, Model.File[String], Model.Folder[String], StructureConfig]]
      when(c.createFileSystem(ProtocolUri, protocolConfig, FsTimeout)).thenReturn(fileSystem)
      when(c.createHttpSender(spawner, senderFactory, ProtocolUri, protocolConfig, SenderConfig))
        .thenReturn(httpSender)
      when(c.createConverter(protocolConfig)).thenReturn(converter)
      c
