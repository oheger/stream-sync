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

package com.github.sync.log

import java.nio.file.Path

import akka.stream.scaladsl.{FileIO, Framing, Source}
import akka.util.ByteString
import com.github.sync.SyncOperation

/**
  * A helper class for creating stream components related to serialized
  * elements.
  *
  * The functions defined here are used in streams that need to read sync
  * operations from log files.
  */
object SerializerStreamHelper {
  /** The byte string representing a line end character. */
  private val LineEnd = ByteString("\n")

  /** The maximum length of a line in a log file. */
  private val MaxLineLength = 8192

  /**
    * Creates a source to read the content of a log file containing sync
    * operations. The source emits the single lines of the log file (where each
    * line corresponds to a sync operation).
    *
    * @param file the log file with sync operations
    * @return the source to read sync log files
    */
  def createLogFileSource(file: Path): Source[String, Any] =
    FileIO.fromPath(file)
      .via(Framing.delimiter(LineEnd, MaxLineLength, allowTruncation = true))
      .map(_.utf8String.trim)
      .filterNot(_.isEmpty)

  /**
    * Creates a source to read the content of a log file containing sync
    * operations and to deserialize the single operations.
    *
    * @param file the log file with sync operations
    * @return the source to read sync operations
    */
  def createSyncOperationSource(file: Path): Source[SyncOperation, Any] =
    createLogFileSource(file)
      .map(strOp => ElementSerializer.deserializeOperation(strOp).get)
}
