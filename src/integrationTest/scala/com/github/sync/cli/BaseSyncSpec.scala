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

import java.io.ByteArrayOutputStream
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.ByteString
import com.github.sync.cli.Sync.SyncResult
import com.github.sync.cli.SyncParameterManager.SyncConfig
import com.github.sync.crypt.{CryptOpHandler, CryptService, CryptStage}
import com.github.sync.{AsyncTestHelper, DelegateSourceComponentsFactory, FileTestHelper, SourceFileProvider}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * A base class for integration test classes for the sync functionality.
  *
  * This class provides some useful functionality that is typically required to
  * test sync operations. There can be multiple sub classes testing different
  * modules, e.g. local sync operations or WebDav servers.
  *
  * The original ''SyncSpec'' class became so large that it slowed down the
  * IDE. So splitting this up is beneficial.
  */
abstract class BaseSyncSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with FileTestHelper
  with AsyncTestHelper {
  def this() = this(ActorSystem("SyncSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  override protected def afterEach(): Unit = {
    tearDownTestFile()
    super.afterEach()
  }

  import system.dispatcher

  /**
    * @inheritdoc Use a higher timeout because of more complex operations.
    */
  override val timeout: Duration = 10.seconds

  /**
    * Creates a test file with the given name in the directory specified. The
    * content of the file is the name in plain text.
    *
    * @param dir      the parent directory
    * @param name     the name of the file
    * @param fileTime an optional timestamp for the file
    * @param content  optional content of the file
    * @return the path to the newly created file
    */
  protected def createTestFile(dir: Path, name: String, fileTime: Option[Instant] = None,
                               content: Option[String] = None): Path = {
    val path = writeFileContent(dir.resolve(name), content getOrElse name)
    fileTime foreach { time =>
      Files.setLastModifiedTime(path, FileTime.from(time))
    }
    path
  }

  /**
    * Reads the content of a file that is located in the given directory.
    *
    * @param dir  the directory
    * @param name the name of the file
    * @return the content of this file as string
    */
  protected def readFileInPath(dir: Path, name: String): String = {
    val file = dir.resolve(name)
    readDataFile(file)
  }

  /**
    * Reads the content of a binary file that is located in the given directory
    * and returns it as byte array.
    *
    * @param dir  the directory
    * @param name the name of the file
    * @return the content of this file as byte array
    */
  protected def readBinaryFileInPath(dir: Path, name: String): Array[Byte] = {
    val file = dir.resolve(name)
    Files.readAllBytes(file)
  }

  /**
    * Checks whether a file with the given name exists in the directory
    * provided. The content of the file is checked as well.
    *
    * @param dir  the parent directory
    * @param name the name of the file
    */
  protected def checkFile(dir: Path, name: String): Unit = {
    readFileInPath(dir, name) should be(name)
  }

  /**
    * Checks that the file specified does not exist.
    *
    * @param dir  the parent directory
    * @param name the name of the file
    */
  protected def checkFileNotPresent(dir: Path, name: String): Unit = {
    val file = dir.resolve(name)
    Files.exists(file) shouldBe false
  }

  /**
    * Returns a ''SyncStreamFactory'' that always returns the given
    * ''SourceFileProvider''.
    *
    * @param provider the ''SourceFileProvider'' to be returned
    * @return the factory
    */
  protected def factoryWithMockSourceProvider(provider: SourceFileProvider): SyncComponentsFactory =
    new SyncComponentsFactory {
      override def createSourceComponentsFactory(config: SyncConfig)
                                                (implicit system: ActorSystem, ec: ExecutionContext):
      Future[SyncComponentsFactory.SourceComponentsFactory] =
        super.createSourceComponentsFactory(config) map { t =>
          new DelegateSourceComponentsFactory(t) {
            override def createSourceFileProvider(): SourceFileProvider = provider
          }
        }
    }

  /**
    * Performs a crypt operation on the given data and returns the result.
    *
    * @param key     the key for encryption / decryption
    * @param handler the crypt handler
    * @param data    the data to be processed
    * @return the processed data
    */
  protected def crypt(key: String, handler: CryptOpHandler, data: ByteString): ByteString = {
    val source = Source.single(data)
    val stage = new CryptStage(handler, CryptStage.keyFromString(key))
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    futureResult(source.via(stage).runWith(sink))
  }

  /**
    * Encrypts the given file name.
    *
    * @param key  the key for encryption
    * @param data the name to be encrypted
    * @return the encrypted name
    */
  protected def encryptName(key: String, data: String): String =
    futureResult(CryptService.encryptName(CryptStage.keyFromString(key), data))

  /**
    * Decrypts the given file name.
    *
    * @param key  the key for decryption
    * @param name the name to be decrypted
    * @return the decrypted name
    */
  protected def decryptName(key: String, name: String): String =
    futureResult(CryptService.decryptName(CryptStage.keyFromString(key), name))

  /**
    * Executes a sync process with the given command line options.
    *
    * @param args    the array with command line options
    * @param factory the factory for sync components
    * @return the future result of the sync process
    */
  protected def runSync(args: Array[String], factory: SyncComponentsFactory = new SyncComponentsFactory):
  Future[SyncResult] = {
    CliActorSystemLifeCycle.processCommandLine(args.toSeq, SyncParameterManager.syncConfigExtractor(),
      "HelpHelp") match {
      case Left(_) =>
        Future.failed(new AssertionError("Could not parse command line."))
      case Right(value) =>
        Sync.syncProcess(factory, value)
    }
  }

  /**
    * Creates a new ''Sync'' instance that is configured to use the actor
    * system of this test class.
    *
    * @param overrideDispatcher flag whether the dispatcher should be replaced
    * @return the special ''Sync'' instance
    */
  protected def createSync(overrideDispatcher: Boolean = false): Sync =
    new Sync {
      override implicit def actorSystem: ActorSystem = system

      override implicit def ec: ExecutionContext =
        if (overrideDispatcher) system.dispatcher
        else super.ec
    }

  /**
    * Executes a Sync process with the provided command line arguments and
    * captures the output. Checks whether the output contains all of the passed
    * in text fragments.
    *
    * @param options      the command line options
    * @param expFragments text fragments that must be contained in the output
    * @return the output as string
    */
  protected def checkSyncOutput(options: Array[String], expFragments: String*): String = {
    val sync = createSync(overrideDispatcher = true)
    val out = new ByteArrayOutputStream
    Console.withOut(out) {
      sync.run(options)
    }

    val output = new String(out.toByteArray)
    expFragments foreach { fragment =>
      output should include(fragment)
    }
    output
  }
}
