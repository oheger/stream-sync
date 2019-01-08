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

import java.nio.file.{Files, Paths}
import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import com.github.sync._
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

object LocalUriResolverSpec {
  /** A test root path. */
  private val RootPath = Paths get "testRootPath"

  /**
    * Convenience function to create an element with a specific URI.
    *
    * @param uri the element's URI
    * @return the element
    */
  private def createElement(uri: String): FsElement = FsFolder(uri, 1)
}

/**
  * Test class for ''LocalUriResolver''.
  */
class LocalUriResolverSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfter with Matchers with FileTestHelper
  with AsyncTestHelper {
  def this() = this(ActorSystem("LocalUriResolverSpec"))

  import LocalUriResolverSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  after {
    tearDownTestFile()
  }

  /**
    * Reads the content of a data file defined by the specified source.
    *
    * @param source the source pointing to the file
    * @return a future with the string content of this file
    */
  private def readFileSource(source: Source[ByteString, Any]): Future[String] = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    implicit val ec: ExecutionContext = system.dispatcher
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    source.runWith(sink) map (_.utf8String)
  }

  "A LocalUriResolver" should "resolve a normal element" in {
    val SubFolderName = "sub-dir"
    val resolver = new LocalUriResolver(RootPath)

    resolver resolve createElement("/" + SubFolderName) match {
      case Success(path) =>
        path should be(RootPath resolve SubFolderName)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "resolve an element whose URI does not start with a /" in {
    val SubFolderName = "no-slash-sub-dir"
    val resolver = new LocalUriResolver(RootPath)

    resolver resolve createElement(SubFolderName) match {
      case Success(path) =>
        path should be(RootPath resolve SubFolderName)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "resolve an element whose URI starts with multiple /s" in {
    val SubFolderName = "multi-slash-sub-dir"
    val resolver = new LocalUriResolver(RootPath)

    resolver resolve createElement("/////" + SubFolderName) match {
      case Success(path) =>
        path should be(RootPath resolve SubFolderName)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "resolve an element with special characters in its URI" in {
    val Components = List("my work", "in+out", "new document.txt")
    val relPath = Paths.get(Components.head, Components.tail: _*)
    val path = RootPath resolve relPath
    val element = createElement("/" + Components.mkString("/"))
    val resolver = new LocalUriResolver(RootPath)

    resolver resolve element match {
      case Success(p) =>
        p should be(path)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "verify that the resolved element is a child of the root dir" in {
    val resolver = new LocalUriResolver(RootPath.toAbsolutePath)

    val result = resolver resolve createElement("../../up")
    result.isFailure shouldBe true
  }

  it should "return a source to a valid file URI" in {
    val subDir = createPathInDirectory("sub")
    Files createDirectory subDir
    writeFileContent(subDir.resolve("data.txt"), FileTestHelper.TestData)
    val resolver = new LocalUriResolver(testDirectory)
    val file = FsFile("/sub/data.txt", 1, Instant.now(), 42)

    val source = futureResult(resolver fileSource file)
    val content = futureResult(readFileSource(source))
    content should be(FileTestHelper.TestData)
  }

  it should "return a future source that fails for an invalid file" in {
    val file = FsFile("../../up", 2, Instant.now, 111)
    val resolver = new LocalUriResolver(testDirectory)

    expectFailedFuture[IllegalArgumentException](resolver fileSource file)
  }
}
