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

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
import java.nio.file.Path

import scala.annotation.tailrec

/**
  * An object providing functionality to encode and normalize URIs.
  *
  * There are obviously some differences in the way how URIs are encoded
  * between the local file system and URIs from web servers (such as using
  * uppercase or lowercase hex characters, encoding special characters or not).
  * For the sync algorithm it is crucial that URIs of elements are encoded in
  * the same way; therefore, they have to be normalized before they are
  * compared. This object provides a corresponding function.
  */
object UriEncodingHelper {
  /** Constant for the path separator character in URIs. */
  val UriSeparator = "/"

  /**
    * Constant for a plus character. This character is problematic because it
    * is used by ''URLEncoder'' to encode space while web servers expect a
    * ''%20'' encoding. When decoding the character has to be treated in a
    * special way as well.
    */
  private val Plus = "+"

  /** Constant for the encoding of a space character. */
  private val SpaceEncoded = "%20"

  /** Constant for the encoding of a plus character. */
  private val PlusEncoded = "%2B"

  /** Name of the charset to be used for encoding/decoding. */
  private val EncodeCharset = StandardCharsets.UTF_8.name()

  /** Prefix of a file URI. */
  private val PrefixFile = "file:///"

  /**
    * URL-encodes the specified string. This is very similar to what Java's
    * ''URLEncoder'' does; however, space characters are encoded using %20.
    *
    * @param s the string to be encoded
    * @return the encoded string
    */
  def encode(s: String): String =
    URLEncoder.encode(s, EncodeCharset).replace(Plus, SpaceEncoded)

  /**
    * URL-decodes the specified string. This function assumes that ''encode()''
    * has been used for the encoding.
    *
    * @param s the string to be decoded
    * @return the decoded string
    */
  def decode(s: String): String = URLDecoder.decode(s.replace(Plus, PlusEncoded), EncodeCharset)

  /**
    * Removes the given character from the string if it is the last one. If
    * the string does not end with this character, it is not changed.
    *
    * @param s the string
    * @param c the character to remove
    * @return the resulting string
    */
  @tailrec def removeTrailing(s: String, c: String): String =
    if (s.endsWith(c)) removeTrailing(s.dropRight(c.length), c)
    else s

  /**
    * Makes sure that the passed in URI ends with a separator. A separator is
    * added if and only if the passed in string does not already end with one.
    *
    * @param uri the URI to be checked
    * @return the URI ending with a separator
    */
  def withTrailingSeparator(uri: String): String =
    if (hasTrailingSeparator(uri)) uri else uri + UriSeparator

  /**
    * Returns a flag whether the passed in URI string ends with a separator
    * character.
    *
    * @param uri the URI to be checked
    * @return '''true''' if the URI ends with a separator; '''false'''
    *         otherwise
    */
  def hasTrailingSeparator(uri: String): Boolean = uri.endsWith(UriSeparator)

  /**
    * Converts the given path to a URI. This function does some more encoding
    * than the ''Path.toUri()'' method, which leaves a lot of special
    * characters untouched.
    *
    * @param p the path to be converted
    * @return the URI for this path
    */
  def pathToUri(p: Path): String = {
    def pathComponents(current: Path, components: List[String]): List[String] =
      if (current == null) components
      else pathComponents(current.getParent, extractName(current) :: components)

    pathComponents(p, Nil).map(encode)
      .mkString(PrefixFile, UriSeparator, "")
  }

  /**
    * Extracts the name from a path. The root component requires a special
    * treatment.
    *
    * @param p the path
    * @return the name of this path
    */
  private def extractName(p: Path): String = {
    val name = p.getFileName
    if (name != null) name.toString else removeTrailing(p.toString, "\\")
  }
}
