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

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import com.github.sync.{FsElement, FsFile, SourceFileProvider}

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object LocalUriResolver {
  /** The '+' character that requires a special handling. */
  private val CharPlus = "+"

  /** The replacement for the '+' character. */
  private val ReplPlus = "%2b"

  /**
    * URL-decodes the specified string.
    *
    * There seems to be some incompatibilities between the encoding done by the
    * URI class and the decoding performed by ''URLDecoder''. The former does
    * not encode '+' characters, but the latter transforms them to space. So a
    * special treatment is necessary here.
    *
    * @param s the string to be dcoded
    * @return the decoded string
    */
  private def decode(s: String): String =
    URLDecoder.decode(s.replace(CharPlus, ReplPlus), StandardCharsets.UTF_8.name())
}

/**
  * A class that is able to resolve ''FsElement'' objects in a local file
  * system defined by its root path.
  *
  * The relative URIs of elements are resolved based on the root directory.
  * Also some sanity checks are implemented.
  *
  * @param rootPath the root path of the structure
  */
class LocalUriResolver(val rootPath: Path) extends SourceFileProvider {

  import LocalUriResolver._

  /**
    * Resolves the given ''FsElement'' relatively to the root path set for
    * this object. If the element's URI is invalid this may fail. The function
    * also checks whether the resulting path is a child of the root path.
    *
    * @param element the element to be resolved
    * @return a ''Try'' with the resolved path
    */
  def resolve(element: FsElement): Try[Path] = Try {
    val resolvedPath =
      rootPath.resolve(decode(element.relativeUri.dropWhile(_ == '/'))).normalize()
    if (!verifyInRootPath(resolvedPath))
      throw new IllegalArgumentException(
        s"Invalid element URI: ${element.relativeUri}! Not in root path.")
    resolvedPath
  }

  /**
    * @inheritdoc This implementation resolves the given local file path. If
    *             successful, a source for reading this file is returned.
    *             Otherwise, result is a source that fails immediately.
    */
  override def fileSource(file: FsFile): Source[ByteString, Any] =
    resolve(file) match {
      case Success(value) =>
        FileIO.fromPath(value)
      case Failure(exception) =>
        Source.failed(exception)
    }

  /**
    * Checks whether the given path is actually contained in the folder
    * structure spawned by the root path.
    *
    * @param p the path to be checked
    * @return '''true''' if the path is valid; '''false''' otherwise
    */
  @tailrec private def verifyInRootPath(p: Path): Boolean =
    if (p == null) false
    else if (rootPath == p) true
    else verifyInRootPath(p.getParent)
}
