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

package com.github.sync

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.sync.SyncTypes.FsFile

import scala.concurrent.Future

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
    * Returns a future for a ''Source'' for reading the content of the
    * specified file from the source structure. The source may be obtained
    * using an asynchronous operation which may also fail; therefore, a
    * ''Future'' is a proper return value.
    *
    * @param uri the URI to the file in question
    * @return a ''Future'' with a ''Source'' for reading this file
    */
  def fileSource(uri: String): Future[Source[ByteString, Any]]

  /**
    * A method that should be called by clients when this object is no longer
    * needed. Here clean-up logic can be implemented.
    */
  def shutdown(): Unit = {}

  /**
    * Returns the size of a file based on its original size. Because of some
    * processing operations that happen during file upload or download (such as
    * encryption or archiving) the file size may change. This method can be
    * used to determine the number of bytes that are transferred via a source
    * returned by this instance.
    *
    * @param orgSize the original file size
    * @return the size of the source returned by this object
    */
  def fileSize(orgSize: Long): Long = orgSize
}
