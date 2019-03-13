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

import java.nio.file.Path

import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes.{FsElement, FsFile}
import com.github.sync.util.UriEncodingHelper

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.Try

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

  /**
    * Resolves the given ''FsElement'' relatively to the root path set for
    * this object. If the element's URI is invalid this may fail. The function
    * also checks whether the resulting path is a child of the root path.
    *
    * @param element the element to be resolved
    * @return a ''Try'' with the resolved path
    */
  def resolve(element: FsElement): Try[Path] = Try {
    val relativeUri = element.relativeUri.dropWhile(_ == UriEncodingHelper.UriSeparator.charAt(0))
    val resolvedPath = rootPath.resolve(relativeUri).normalize()
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
  override def fileSource(file: FsFile): Future[Source[ByteString, Any]] =
    Future.fromTry(resolve(file).map(path => FileIO.fromPath(path)))

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
