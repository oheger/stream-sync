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

import akka.stream.scaladsl.Source
import akka.util.ByteString

/**
  * A trait that provides access to the content of a specific file.
  *
  * This trait is used when applying sync operations to a destination
  * structure. When it is necessary to copy files from the source structure
  * into the destination structure there must be a way of obtaining the content
  * of the file affected. (The exact way to do this depends on the type of the
  * source structure.) This trait defines an API how a source for a file can
  * be requested. There will be different implementations for the types of
  * structures supported.
  */
trait SourceFileProvider {
  /**
    * Returns a ''Source'' for reading the content of the specified file from
    * the source structure.
    *
    * @param file the file in question
    * @return a ''Source'' for reading this file
    */
  def fileSource(file: FsFile): Source[ByteString, Any]
}