/*
 * Copyright 2018-2022 The Developers Team.
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
import com.github.cloudfiles.core.http.UriEncodingHelper.{decode, encode}
import com.github.sync.SyncTypes.SyncAction.{ActionCreate, ActionLocalCreate, ActionLocalOverride, ActionLocalRemove, ActionOverride, ActionRemove}
import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder, SyncAction, SyncOperation}

import java.time.Instant
import scala.collection.mutable
import scala.util.Try

object SerializationSupport:
  /** Tag to mark the serialized form of a folder element. */
  final val TagFolder = "FOLDER"

  /** Tag to mark the serialized form of a file element. */
  final val TagFile = "FILE"

  /**
    * Concrete instance to support serialization of [[FsElement]] instances.
    */
  given SerializationSupport[FsElement] with
    override def deserialize(parts: IndexedSeq[String]): Try[FsElement] = Try {
      lazy val elemID = UriEncodingHelper decode parts(1)
      lazy val elemUri = UriEncodingHelper decode parts(2)
      parts.head match
        case TagFolder =>
          FsFolder(elemID, elemUri, parts(3).toInt)
        case TagFile =>
          FsFile(elemID, elemUri, parts(3).toInt, Instant.parse(parts(4)), parts(5).toLong)
        case tag =>
          throw new IllegalArgumentException("Unknown element tag: " + tag)
    }

    extension (elem: FsElement)
      def serialize(builder: mutable.ArrayBuilder[String]): Unit =
        elem match
          case folder: FsFolder =>
            serializeBaseProperties(TagFolder, folder, builder)
          case file@FsFile(_, _, _, lastModified, size) =>
            serializeBaseProperties(TagFile, file, builder)
            builder += lastModified.toString
            builder += size.toString

  /**
    * Generates the serialized representation for the basic properties of the
    * given element with the given tag (indicating the element type). Note that
    * the element's URI needs to be encoded; otherwise, it may contain space
    * characters which would break deserialization.
    *
    * @param tag     the tag
    * @param elem    the element
    * @param builder the builder for constructing the array with properties
    */
  private def serializeBaseProperties(tag: String, elem: FsElement, builder: mutable.ArrayBuilder[String]): Unit =
    builder += tag
    builder += encode(elem.id)
    builder += encode(elem.relativeUri)
    builder += elem.level.toString

/**
  * A type class that defines support for serializing and deserializing objects
  * related to sync streams.
  *
  * For some use cases, different kinds of elements floating through a sync
  * stream need to be persisted and later read from persistent storage. This
  * type class defines the protocol used for this purpose.
  *
  * The serialization format used here is pretty simple: An object is written
  * on a single text line. The single properties are written in a defined
  * order, separated by whitespace. If a property can contain whitespace, it
  * needs to be URL-encoded.
  *
  * @tparam T the type for which to add serialization support
  */
trait SerializationSupport[T]:
  /**
    * Tries to deserialize an instance of the represented type from the given
    * sequence of properties.
    *
    * @param parts the sequence with properties
    * @return a ''Try'' with the deserialized instance
    */
  def deserialize(parts: IndexedSeq[String]): Try[T]

  extension (t: T)

  /**
    * Transforms this instance to its serialized form.
    */
    def serialize(builder: mutable.ArrayBuilder[String]): Unit
