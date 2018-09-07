/*
 * Copyright 2018 The Developers Team.
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

package com.github.sync

import java.nio.file.{Files, Path}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.github.sync.cli.Sync
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpecLike, Matchers}

/**
  * Integration test class for sync processes.
  */
class SyncSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with
  BeforeAndAfterAll with BeforeAndAfter with Matchers with FileTestHelper with AsyncTestHelper {
  def this() = this(ActorSystem("SyncSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  after {
    tearDownTestFile()
  }

  /**
    * Creates a test file with the given name in the directory specified. The
    * content of the file is the name in plain text.
    *
    * @param dir  the parent directory
    * @param name the name of the file
    * @return the path to the newly created file
    */
  private def createTestFile(dir: Path, name: String): Path =
    writeFileContent(dir.resolve(name), name)

  /**
    * Checks whether a file with the given name exists in the directory
    * provided. The content of the file is checked as well.
    *
    * @param dir  the parent directory
    * @param name the name of the file
    */
  private def checkFile(dir: Path, name: String): Unit = {
    val file = dir.resolve(name)
    readDataFile(file) should be(name)
  }

  /**
    * Checks that the file specified does not exist.
    *
    * @param dir  the parent directory
    * @param name the name of the file
    */
  private def checkFileNotPresent(dir: Path, name: String): Unit = {
    val file = dir.resolve(name)
    Files.exists(file) shouldBe false
  }

  "Sync" should "synchronize two directory structures" in {
    val srcFolder = Files.createDirectory(createPathInDirectory("source"))
    val dstFolder = Files.createDirectory(createPathInDirectory("dest"))
    createTestFile(srcFolder, "test1.txt")
    createTestFile(srcFolder, "test2.txt")
    createTestFile(srcFolder, "ignored.tmp")
    createTestFile(dstFolder, "toBeRemoved.txt")
    val options = Array(srcFolder.toAbsolutePath.toString, dstFolder.toAbsolutePath.toString,
      "--filter", "exclude:*.tmp")

    val result = futureResult(Sync.syncProcess(options))
    result.totalOperations should be(result.successfulOperations)
    checkFile(dstFolder, "test1.txt")
    checkFileNotPresent(dstFolder, "toBeRemoved.txt")
    checkFileNotPresent(dstFolder, "ignored.tmp")
  }
}
