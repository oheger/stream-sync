/*
 * Copyright 2018-2022 The Developers Team.
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

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{ActorSystem, typed}
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.ByteString
import com.github.cloudfiles.crypt.alg.aes.Aes
import com.github.cloudfiles.crypt.service.CryptService
import com.github.sync.cli.Sync.SyncResult
import com.github.sync.{AsyncTestHelper, FileTestHelper}
import org.apache.logging.log4j.core.appender.{AbstractOutputStreamAppender, OutputStreamManager}
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.layout.PatternLayout
import org.apache.logging.log4j.core.{LogEvent, LoggerContext}
import org.mockito.Mockito
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.io.ByteArrayOutputStream
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala

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
  with AsyncTestHelper:
  def this() = this(ActorSystem("SyncSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  import system.dispatcher

  /**
    * @inheritdoc Use a higher timeout because of more complex operations.
    */
  override val asyncTimeout: Duration = 10.seconds

  /** A source for randomness for crypt operations. */
  private implicit val secureRandom: SecureRandom = new SecureRandom

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
                               content: Option[String] = None): Path =
    val path = writeFileContent(dir.resolve(name), content getOrElse name)
    fileTime foreach { time =>
      Files.setLastModifiedTime(path, FileTime.from(time))
    }
    path

  /**
    * Reads the content of a file that is located in the given directory.
    *
    * @param dir  the directory
    * @param name the name of the file
    * @return the content of this file as string
    */
  protected def readFileInPath(dir: Path, name: String): String =
    val file = dir.resolve(name)
    readDataFile(file)

  /**
    * Reads the content of a binary file that is located in the given directory
    * and returns it as byte array.
    *
    * @param dir  the directory
    * @param name the name of the file
    * @return the content of this file as byte array
    */
  protected def readBinaryFileInPath(dir: Path, name: String): Array[Byte] =
    val file = dir.resolve(name)
    Files.readAllBytes(file)

  /**
    * Checks whether a file with the given name exists in the directory
    * provided. The content of the file is checked as well.
    *
    * @param dir  the parent directory
    * @param name the name of the file
    */
  protected def checkFile(dir: Path, name: String): Unit =
    readFileInPath(dir, name) should be(name)

  /**
    * Checks that the file specified does not exist.
    *
    * @param dir  the parent directory
    * @param name the name of the file
    */
  protected def checkFileNotPresent(dir: Path, name: String): Unit =
    val file = dir.resolve(name)
    Files.exists(file) shouldBe false

  /**
    * Performs a decrypt operation on the given data and returns the result.
    *
    * @param key  the key for decryption
    * @param data the data to be processed
    * @return the processed data
    */
  protected def decrypt(key: String, data: ByteString): ByteString =
    val source = Source.single(data)
    val decryptSource = CryptService.decryptSource(Aes, Aes.keyFromString(key), source)
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    futureResult(decryptSource.runWith(sink))

  /**
    * Encrypts the given file name.
    *
    * @param key  the key for encryption
    * @param data the name to be encrypted
    * @return the encrypted name
    */
  protected def encryptName(key: String, data: String): String =
    CryptService.encryptTextToBase64(Aes, Aes.keyFromString(key), data)

  /**
    * Decrypts the given file name.
    *
    * @param key  the key for decryption
    * @param name the name to be decrypted
    * @return the decrypted name
    */
  protected def decryptName(key: String, name: String): String =
    CryptService.decryptTextFromBase64(Aes, Aes.keyFromString(key), name).get

  /**
    * Executes a sync process with the given command line options.
    *
    * @param args                 the array with command line options
    * @param optProtocolSetupFunc optional function to setup protocol factories
    * @return the future result of the sync process
    */
  protected def runSync(args: Array[String],
                        optProtocolSetupFunc: Option[SyncSetup.ProtocolFactorySetupFunc] = None):
  Future[SyncResult] =
    CliActorSystemLifeCycle.processCommandLine(args.toSeq, SyncParameterManager.syncConfigExtractor(),
      "HelpHelp") match
      case Left(_) =>
        Future.failed(new AssertionError("Could not parse command line."))
      case Right(value) =>
        implicit val typedActorSystem: typed.ActorSystem[Nothing] = system.toTyped
        val authSetupFunc = SyncSetup.defaultAuthSetupFunc()
        val protocolSetupFunc = optProtocolSetupFunc getOrElse SyncSetup.defaultProtocolFactorySetupFunc
        Sync.syncProcess(value)(authSetupFunc)(protocolSetupFunc)

  /**
    * Executes a sync process with the given command line options and installs
    * a special appender to capture log output. The full output is returned as
    * string. This is used to test the logging-related functionality.
    * Unfortunately, as loggers work asynchronously, it is not trivial to find
    * out when the application is done and get the complete log output. This
    * function therefore polls a queue with a certain timeout. When no more log
    * events are received in this timeout, the output is considered complete.
    *
    * @param args the array with command line options
    * @return the log output generated by the sync process as string
    */
  protected def runSyncAndCaptureLogs(args: Array[String]): String =
    val LogTimeout = 200
    val eventQueue = new LinkedBlockingQueue[LogEvent]
    val appender = new AbstractOutputStreamAppender[OutputStreamManager]("testAppender",
      PatternLayout.createDefaultLayout(), null, false, true,
      Array.empty, Mockito.mock(classOf[OutputStreamManager])) {
      override def append(event: LogEvent): Unit = eventQueue.offer(event.toImmutable)
    }

    @tailrec def gatherLogOutput(output: String): String =
      val event = eventQueue.poll(LogTimeout, TimeUnit.MILLISECONDS)
      if event == null then output
      else
        val msg = s"${event.getLoggerName} ${event.getLevel.name()} ${event.getMessage.getFormattedMessage} " +
          event.getThrown + "\n"
        gatherLogOutput(output + msg)

    val context = LoggerContext.getContext(false)
    val config = context.getConfiguration
    try
      config.getLoggers.values().asScala.foreach(_.addAppender(appender, null, null))
      appender.start()
      runSync(args)
      gatherLogOutput("")
    finally
      Configurator.reconfigure()

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
        if overrideDispatcher then system.dispatcher
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
  protected def checkSyncOutput(options: Array[String], expFragments: String*): String =
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
