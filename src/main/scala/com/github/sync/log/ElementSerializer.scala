/*
 * Copyright 2018-2021 The Developers Team.
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

import java.time.Instant
import akka.util.ByteString
import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.sync.SyncTypes._

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
  val TagFolder = "FOLDER"

  /** Tag to mark the serialized form of a file element. */
  val TagFile = "FILE"

  /**
    * A mapping from action types to corresponding strings. This is used to
    * generate the action tag for a serialized operation.
    */
  private val ActionTagMapping: Map[SyncAction, String] = Map(ActionCreate -> "CREATE",
    ActionOverride -> "OVERRIDE", ActionRemove -> "REMOVE")

  /**
    * A mapping from tag names to corresponding sync actions. This is used to
    * reconstruct actions from the serialized form.
    */
  private val TagActionMapping: Map[String, SyncAction] =
    ActionTagMapping map (_.swap)

  /** Constant for the line-ending characters. */
  private val CR = ByteString(System.lineSeparator())

  /**
    * Generates a string representation for the given element.
    *
    * @param elem the element to be serialized
    * @return the string representation for this element
    */
  def serializeElement(elem: FsElement): ByteString = ByteString {
    elem match
      case folder: FsFolder =>
        serializeBaseProperties(TagFolder, folder)
      case file@FsFile(_, _, _, lastModified, size) =>
        s"${serializeBaseProperties(TagFile, file)} $lastModified $size"
  }

  /**
    * Generates a string representation for the given sync operation. This
    * string contains both a tag to represent the action and the element
    * affected as well.
    *
    * @param operation the operation to serialize
    * @return the string representation for this operation
    */
  def serializeOperation(operation: SyncOperation): ByteString =
    ByteString(s"${ActionTagMapping(operation.action)} ${operation.level} ${encode(operation.dstID)} ") ++
      serializeElement(operation.element) ++ CR

  /**
    * Tries to create an ''FsElement'' from its serialized form. Note that the
    * serialized form contains a variable number of properties depending on the
    * fact whether an original URI is present or not.
    *
    * @param parts the single parts of the serialized form
    * @return a ''Try'' with the resulting element
    */
  def deserializeElement(parts: Seq[String]): Try[FsElement] = Try {
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

  /**
    * Tries to create a ''SyncOperation'' from its serialized form.
    *
    * @param raw the raw data with the serialized form of the operation
    * @return a ''Try'' with the resulting operation
    */
  def deserializeOperation(raw: String): Try[SyncOperation] = for
    actionData <- deserializeAction(raw)
    elem <- deserializeElement(actionData._6)
  yield SyncOperation(elem, actionData._1, actionData._2, dstID = actionData._3)

  /**
    * Encode the given string, so that it can be safely serialized.
    *
    * @param s the string to encode
    * @return the encoded string
    */
  private def encode(s: String): String =
  //TODO Remove the null check when CloudFiles has been fully integrated
    if s == null then "" else UriEncodingHelper.encode(s)

  /**
    * Generates a string representation for the given element with the given
    * tag (indicating the element type) and the element's basic properties.
    * Note that the element's URI needs to be encoded; otherwise, it may
    * contain space characters which would break deserialization.
    *
    * @param tag  the tag
    * @param elem the element
    * @return the basic string representation for this element
    */
  private def serializeBaseProperties(tag: String, elem: FsElement): String =
    s"$tag ${encode(elem.id)} ${encode(elem.relativeUri)} ${elem.level}"

  /**
    * Extracts the properties of a ''SyncAction'' from the serialized
    * representation of a ''SyncOperation''. If successful, the return value
    * can be used to further process the serialized element.
    *
    * @param raw the raw data with the serialized form of the operation
    * @return a ''Try'' with elements that could be parsed
    */
  private def deserializeAction(raw: String):
  Try[(SyncAction, Int, String, Option[String], Option[String], Seq[String])] =
    Try {
      val parts = raw.split("\\s").toSeq
      val indexTag = parts.indexWhere(p => TagFile == p || TagFolder == p)
      if indexTag <= 3 then
        (TagActionMapping(parts.head), parts(1).toInt, UriEncodingHelper decode parts(2), None, None, parts drop 3)
      else (TagActionMapping(parts.head), parts(1).toInt, UriEncodingHelper decode parts(2),
        Some(UriEncodingHelper decode parts(3)), Some(UriEncodingHelper decode parts(4)), parts drop 5)
    }
