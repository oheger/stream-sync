/*
 * Copyright 2018-2019 The Developers Team.
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

package com.github.sync.local

import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.time.Instant

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorRef, ActorSystem, OneForOneStrategy, Props, SupervisorStrategy}
import akka.testkit.{ImplicitSender, TestKit}
import com.github.sync.SyncTypes._
import com.github.sync._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec
import scala.util.Random

object LocalSyncOperationActorSpec {
  /** A suffix to be appended to URIs to represent an original URI. */
  private val SuffixOrgUri = "_org"

  /**
    * Generates an original URI based on the given URI string.
    *
    * @param uri the URI
    * @return the corresponding original URI
    */
  private def orgUri(uri: String): String = uri + SuffixOrgUri

  /**
    * Convenience function to create a file from the given URI.
    *
    * @param uri the file URI
    * @return the file object
    */
  private def createFile(uri: String): FsFile =
    FsFile(uri, 1, Instant.parse("2018-08-18T19:08:41.00Z"), 42L)

  /**
    * Convenience method to create a sync operation that sets a hard-coded
    * level. (The level is not relevant for these tests.)
    *
    * @param element   the element
    * @param action    the sync action
    * @param optSrcUri an ''Option'' for the original source URI
    * @param optDstUri an ''Option'' for the original destination URI
    * @return the ''SyncOperation''
    */
  private def createSyncOp(element: FsElement, action: SyncAction, optSrcUri: Option[String] = None,
                           optDstUri: Option[String] = None): SyncOperation =
    SyncOperation(element, action, 1, optSrcUri getOrElse orgUri(element.relativeUri),
      optDstUri getOrElse orgUri(element.relativeUri))
}

/**
  * Test class for ''FsSyncOperationActor''.
  */
class LocalSyncOperationActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper {
  def this() = this(ActorSystem("FsSyncOperationActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  import LocalSyncOperationActorSpec._

  "A LocalSyncOperationActor" should "create a folder in the destination structure" in {
    val FolderName = "newTestFolder"
    val op = createSyncOp(FsFolder(FolderName, 1), ActionCreate)
    val helper = new LocalActorTestHelper

    val folderPath = helper.sendOperationAndExpectResponse(op)
      .destinationPath(orgUri(FolderName))
    Files.exists(folderPath) shouldBe true
    Files.isDirectory(folderPath) shouldBe true
  }

  it should "handle errors when creating a folder" in {
    val op1 = createSyncOp(FsFolder("non/existing/folder/path", 1), ActionCreate)
    val op2 = createSyncOp(FsFolder("nextAttempt", 1), ActionCreate)
    val helper = new LocalActorTestHelper

    helper.sendOperation(op1)
      .sendOperationAndExpectResponse(op2)
  }

  it should "remove a folder from the destination structure" in {
    val FolderName = "toBeRemoved"
    val op = createSyncOp(FsFolder(FolderName, 1), ActionRemove)
    val helper = new LocalActorTestHelper
    val path = helper.destinationPath(orgUri(FolderName))
    Files createDirectory path

    helper.sendOperationAndExpectResponse(op)
    Files exists path shouldBe false
  }

  it should "remove a file from the destination structure" in {
    val FileUri = "fileToBeRemoved.tmp"
    val helper = new LocalActorTestHelper
    val path = writeFileContent(helper.destinationPath(orgUri(FileUri)),
      FileTestHelper.TestData)
    val op = createSyncOp(createFile(FileUri), ActionRemove)

    helper.sendOperationAndExpectResponse(op)
    Files exists path shouldBe false
  }

  it should "handle errors when removing a folder" in {
    val op1 = createSyncOp(FsFolder("nonExistingFolder", 1), ActionRemove)
    val op2 = createSyncOp(FsFolder("anotherFolder", 1), ActionCreate)
    val helper = new LocalActorTestHelper

    helper.sendOperation(op1)
      .sendOperationAndExpectResponse(op2)
  }

  it should "create a file in the destination structure" in {
    val FileName = "copy.dat"
    val DestName = FileName + ".dst"
    val helper = new LocalActorTestHelper
    writeFileContent(helper.sourcePath(orgUri(FileName)), FileTestHelper.TestData)
    val file = createFile(FileName)
    val op = createSyncOp(file, ActionCreate, optDstUri = Some(DestName))

    val destPath = helper.sendOperationAndExpectResponse(op)
      .destinationPath(DestName)
    readDataFile(destPath) should be(FileTestHelper.TestData)
    val timeDest = Files getLastModifiedTime destPath
    timeDest should be(FileTime.from(file.lastModified))
  }

  it should "override a file in the destination structure" in {
    val FileName = "override.dat"
    val DestName = FileName + ".target"
    val helper = new LocalActorTestHelper
    writeFileContent(helper.sourcePath(orgUri(FileName)), FileTestHelper.TestData)
    val destPath = writeFileContent(helper.destinationPath(DestName), "some old data")
    val op = createSyncOp(createFile(FileName), ActionCreate, optDstUri = Some(DestName))

    helper.sendOperationAndExpectResponse(op)
    readDataFile(destPath) should be(FileTestHelper.TestData)
  }

  it should "handle errors in the source when copying a file" in {
    val FileName = "fileNotInSource.txt"
    val helper = new LocalActorTestHelper
    writeFileContent(helper.sourcePath(FileName), FileTestHelper.TestData)
    val op1 = createSyncOp(createFile(FileName), ActionCreate, optSrcUri = Some("nonExistingFile.xxx"))
    val op2 = createSyncOp(FsFolder("afterFailedCopy", 1), ActionCreate)

    helper.sendOperation(op1)
      .sendOperationAndExpectResponse(op2)
  }

  it should "handle errors in the sink when copying a file" in {
    val FileName = "toCopy.txt"
    val helper = new LocalActorTestHelper
    val sourcePath = helper.sourcePath(orgUri(FileName))
    writeFileContent(sourcePath, FileTestHelper.TestData)
    val op1 = createSyncOp(createFile(FileName), ActionCreate, optDstUri = Some("/deeply/nested/" + FileName))
    val op2 = createSyncOp(FsFolder("afterFailedSinkCopy", 1), ActionCreate)

    helper.sendOperation(op1)
      .sendOperationAndExpectResponse(op2)
  }

  /**
    * Test helper class managing a test actor and some dependencies.
    */
  private class LocalActorTestHelper {
    /** Object for generating random numbers. */
    private val random = new Random

    /** The path for the source structure. */
    private val sourcePath = createDirectory()

    /** The path for the destination structure. */
    private val destinationPath = createDirectory()

    /** The test actor instance. */
    private val syncActor = createSyncActor()

    /**
      * Sends the given sync operation to the test actor.
      *
      * @param op the operation to send
      * @return this test helper
      */
    def sendOperation(op: SyncOperation): LocalActorTestHelper = {
      syncActor ! op
      this
    }

    /**
      * Sends the given sync operation to the test actor and expects the
      * actor ACKs this by replying with the same operation.
      *
      * @param op the operation to send
      * @return this test helper
      */
    def sendOperationAndExpectResponse(op: SyncOperation): LocalActorTestHelper = {
      sendOperation(op)
      expectMsg(op)
      this
    }

    /**
      * Resolves the given path name against the test destination directory.
      * This can be used to check whether the test actor has created corrected
      * elements.
      *
      * @param name the path name
      * @return the resolved path in the destination folder
      */
    def destinationPath(name: String): Path = destinationPath resolve name

    /**
      * Resolves the given path name against the test source directory. This
      * can be used to create source files for copy operations.
      *
      * @param name the path name
      * @return the resolved path in the source folder
      */
    def sourcePath(name: String): Path = sourcePath resolve name

    /**
      * Creates the test actor instance.
      *
      * @return the test actor
      */
    private def createSyncActor(): ActorRef =
      system.actorOf(Props(classOf[SupervisorActor], new LocalUriResolver(sourcePath),
        LocalFsConfig(destinationPath, None), "blocking-dispatcher"))

    /**
      * Creates a directory in the temporary folder with a unique name.
      *
      * @return the path to the directory
      */
    private def createDirectory(): Path = {
      val path = uniqueFileName()
      Files.createDirectory(path)
    }

    /**
      * Generates a unique file name in the test directory.
      *
      * @return the unique file path
      */
    @tailrec private def uniqueFileName(): Path = {
      val name = "test" + random.nextLong()
      val path = testDirectory resolve name
      if (Files.exists(path)) uniqueFileName()
      else path
    }
  }

}

/**
  * An actor that implements supervision on a test instance.
  *
  * This is needed to check whether exceptions are correctly handled. This
  * actor creates a [[LocalSyncOperationActor]] as child and sets a supervision
  * strategy the stops this child actor for all exceptions.
  *
  * @param srcProvider            the provider for source files
  * @param config                 the configuration of the actor
  * @param blockingDispatcherName name of the blocking dispatcher
  */
class SupervisorActor(srcProvider: SourceFileProvider, config: LocalFsConfig,
                      blockingDispatcherName: String) extends Actor {
  /** Reference to the test instance. */
  private var syncActor: ActorRef = _

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _ => Stop
  }

  override def preStart(): Unit = {
    syncActor = context.actorOf(Props(classOf[LocalSyncOperationActor], srcProvider, config,
      blockingDispatcherName))
  }

  override def receive: Receive = {
    case op: SyncOperation =>
      syncActor forward op
  }
}
