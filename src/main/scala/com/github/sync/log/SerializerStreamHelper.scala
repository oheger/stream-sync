/*
 * Copyright 2018-2025 The Developers Team.
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

import com.github.sync.SyncTypes.SyncOperation
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Flow, Framing, Sink, Source}
import org.apache.pekko.util.ByteString

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

/**
  * A helper class for creating stream components related to serialized
  * elements.
  *
  * The functions defined here are used in streams that need to read sync
  * operations from log files, or in general, serialized elements from files.
  */
object SerializerStreamHelper:
  /** The byte string representing a line end character. */
  private val LineEnd = ByteString("\n")

  /** The maximum length of a line in a log file. */
  private val MaxLineLength = 8192

  /**
    * Returns a ''Flow'' that extracts the string representations of serialized
    * elements from the content of a file. The flow basically returns the 
    * single non-empty lines of the file, since each line corresponds to one
    * element in serialized form.
    *
    * @return the flow for the lines representing serialized elements
    */
  def serializedElementFlow: Flow[ByteString, String, Any] =
    Framing.delimiter(LineEnd, MaxLineLength, allowTruncation = true)
      .map(_.utf8String.trim)
      .filterNot(_.isEmpty)

  /**
    * Creates a source to read the content of a log file containing sync
    * operations. The source emits the single lines of the log file (where each
    * line corresponds to a sync operation).
    *
    * @param file the log file with sync operations
    * @return the source to read sync log files
    */
  def createLogFileSource(file: Path): Source[String, Any] = FileIO.fromPath(file).via(serializedElementFlow)

  /**
    * Creates a source to read the content of a log file containing sync
    * operations and to deserialize the single operations.
    *
    * @param file the log file with sync operations
    * @return the source to read sync operations
    */
  def createSyncOperationSource(file: Path): Source[SyncOperation, Any] =
    createDeserializationSource(file)

  /**
    * Creates a source to read a file containing objects in serialized form.
    * The objects are deserialized to the given result type. The stream fails
    * if elements in an unexpected format are encountered.
    *
    * @param file the file with serialized objects
    * @tparam T the result type of deserialization
    * @return the source to read and deserialize the file
    */
  def createDeserializationSource[T: SerializationSupport](file: Path): Source[T, Any] =
    createDeserializationSource(createLogFileSource(file))

  /**
    * Creates a source that deserializes objects obtained from another source
    * that yields serialized objects. The passed in source must produce string
    * representations of objects. Each string is deserialized to an object of
    * the specified type.
    *
    * @param source the source for serialized elements
    * @tparam T the result type of deserialization
    * @return the source producing deserialized objects
    */
  def createDeserializationSource[T: SerializationSupport](source: Source[String, Any]): Source[T, Any] =
    source map { str =>
      ElementSerializer.deserialize[T](str).get
    }

  /**
    * Reads a log file with sync operations and returns the single lines as a
    * set. This is used when running a sync process from a sync log together
    * with a log of already processed operations. In this case, from the sync
    * log the lines are filtered that are already contained in the processed
    * log.
    *
    * @param file   the file with sync operations to be read
    * @param system the actor system
    * @return a future with a set of the single lines read from the file
    */
  def readProcessedLog(file: Path)(implicit system: ActorSystem): Future[Set[String]] =
    val sink = Sink.fold[Set[String], String](Set.empty)(_ + _)
    createLogFileSource(file).runWith(sink)

  /**
    * Creates a source from a sync log file that ignores operations contained
    * in a processed log file. This constellation can be used to process a sync
    * log in multiple turns. Each operation from the sync log that could be
    * executed successfully is written into the processed log. When the process
    * is interrupted and resumed the processed log is read and only operations
    * not found in this log are executed.
    *
    * @param syncLog      the path to the sync log file
    * @param processedLog the path to the processed log file
    * @param ec           the execution context
    * @param system       the actor system
    * @return the source to read sync operations ignoring processed operations
    */
  def createSyncOperationSourceWithProcessedLog(syncLog: Path, processedLog: Path)
                                               (implicit ec: ExecutionContext, system: ActorSystem):
  Future[Source[SyncOperation, Any]] = readProcessedLog(processedLog)
    .fallbackTo(Future.successful(Set.empty[String])) map { log =>
    createDeserializationSource(createLogFileSource(syncLog).filterNot(log.contains))
  }
