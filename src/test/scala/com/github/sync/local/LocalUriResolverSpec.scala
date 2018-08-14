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

package com.github.sync.local

import java.nio.file.Paths

import com.github.sync.{FsElement, FsFolder}
import org.scalatest.{FlatSpec, Matchers}

import scala.util.Success

object LocalUriResolverSpec {
  /** A test root path. */
  private val RootPath = Paths get "testRootPath"

  /** String representation of the root path. */
  private val RootUri = RootPath.toUri.toString + "/"

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
class LocalUriResolverSpec extends FlatSpec with Matchers {

  import LocalUriResolverSpec._

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
    val relPath = Paths.get("my work", "in+out", "new document.txt")
    val path = RootPath resolve relPath
    val element = createElement(path.toUri.toString.substring(RootUri.length - 1))
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
}
