/*
 * Copyright 2018-2024 The Developers Team.
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

import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.cloudfiles.core.http.UriEncodingHelper.encode
import com.github.sync.SyncTypes.*
import com.github.sync.SyncTypes.SyncAction.*
import org.apache.pekko.util.ByteString

import java.io.{ByteArrayOutputStream, PrintWriter, StringWriter}
import java.time.Instant
import scala.collection.mutable
import scala.util.Try

/**
  * A service that is able to generate string representations for elements that
  * take part in sync processes and to create such elements again from their
  * string representations.
  *
  * This service is needed in "log" mode: Here sync actions are not directly
  * applied to the destination structure, but only written to a log file. The
  * logged actions can then later be executed on a target structure.
  */
object ElementSerializer:
  /** Tag to mark the serialized form of a folder element. */
  final val TagFolder = "FOLDER"

  /** Tag to mark the serialized form of a file element. */
  final val TagFile = "FILE"

  /** The separator between the properties of an object. */
  private val PROPERTY_SEPARATOR = " "

  /** The line separator. */
  private val LINE_SEPARATOR = System.lineSeparator()

  /**
    * Serializes the given [[SerializationSupport]] object to a ''ByteString''.
    *
    * @param obj the object to serialize
    * @tparam T the type of the object
    * @return the serialized form of this object
    */
  def serialize[T: SerializationSupport](obj: T): ByteString =
    val builder = mutable.ArrayBuilder.make[String]
    obj.serialize(builder)
    ByteString(builder.result().mkString("", PROPERTY_SEPARATOR, LINE_SEPARATOR))

  /**
    * Tries to deserialize the given string representation to an object of the
    * given type.
    *
    * @param line the string representation to deserialize
    * @tparam T the type of the target object
    * @return a ''Try'' with the deserialized object
    */
  def deserialize[T](line: String)(using ser: SerializationSupport[T]): Try[T] =
    val parts = line.split("\\s").toIndexedSeq
    ser.deserialize(parts)

  /**
    * Generates a string representation for a failed ''SyncOperation''. The
    * operation is serialized in the normal way; then the exception with its
    * stacktrace is written on a new line.
    *
    * @param operation the operation
    * @param exception the exception caused by the operation
    * @return the string representation of this failed operation
    */
  def serializeFailedOperation(operation: SyncOperation, exception: Throwable): ByteString =
    val serializedOp = serialize(operation)
    val stringWriter = new StringWriter()
    val out = new PrintWriter(stringWriter)
    exception.printStackTrace(out)
    out.flush()
    serializedOp ++ ByteString(stringWriter.toString)
