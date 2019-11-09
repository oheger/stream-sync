package com.github.sync.util

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets

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
    * Removes the given prefix from the string if it exists. If the string does
    * not start with this prefix, it is not changed.
    *
    * @param s      the string
    * @param prefix the prefix to remove
    * @return the resulting string
    */
  @tailrec def removeLeading(s: String, prefix: String): String =
    if (s startsWith prefix) removeLeading(s.substring(prefix.length), prefix)
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
    * Makes sure that the passed in URI starts with a separator. A separator is
    * added in front if and only if the passed in string does not already start
    * with one.
    *
    * @param uri the URI to be checked
    * @return the URI with a leading separator
    */
  def withLeadingSeparator(uri: String): String =
    if (hasLeadingSeparator(uri)) uri else UriSeparator + uri

  /**
    * Removes a trailing separator from the passed in URI if it is present. If
    * the URI does not end with a separator, it is returned as is.
    *
    * @param uri the URI
    * @return the URI with a trailing separator removed
    */
  def removeTrailingSeparator(uri: String): String =
    removeTrailing(uri, UriSeparator)

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
    * Returns a flag whether the passed in URI string starts with a separator
    * character.
    *
    * @param uri the URI to be checked
    * @return '''true''' if the URI starts with a seprator; '''false'''
    *         otherwise
    */
  def hasLeadingSeparator(uri: String): Boolean = uri startsWith UriSeparator

  /**
    * Removes a leading separator from the given URI if it is present.
    * Otherwise, the URI is returned as is.
    *
    * @param uri the URI
    * @return the URI with a leading separator removed
    */
  def removeLeadingSeparator(uri: String): String =
    removeLeading(uri, UriSeparator)

  /**
    * Checks whether the given URI has a parent element. If this function
    * returns '''false''' the URI points to a top-level element in the
    * iteration.
    *
    * @param uri the URI in question
    * @return a flag whether this URI has a parent element
    */
  def hasParent(uri: String): Boolean =
    findNameComponentPos(uri) > 0

  /**
    * Splits the given URI in a parent URI and a name. This function
    * determines the position of the last name component in the given URI. The
    * URI is split at this position, and both strings are returned. The
    * separator is not contained in any of these components, i.e. the parent
    * URI does not end with a separator nor does the name start with one. If
    * the URI has no parent, the resulting parent string is empty.
    *
    * @param uri the URI to be split
    * @return a tuple with the parent URI and the name component
    */
  def splitParent(uri: String): (String, String) = {
    val canonicalUri = removeTrailingSeparator(uri)
    val pos = findNameComponentPos(canonicalUri)
    if (pos >= 0) (canonicalUri.substring(0, pos), canonicalUri.substring(pos + 1))
    else ("", canonicalUri)
  }

  /**
    * Splits the given URI into its components separated by the URI separator.
    *
    * @param uri the URI to be split
    * @return an array with the single components
    */
  def splitComponents(uri: String): Array[String] =
    removeLeadingSeparator(uri) split UriSeparator

  /**
    * Creates a URI string from the given components. The components are
    * combined using the URI separator.
    *
    * @param components the sequence with components
    * @return the resulting URI
    */
  def fromComponents(components: Seq[String]): String =
    UriSeparator + components.mkString(UriSeparator)

  /**
    * Transforms a URI by applying the given mapping function to all its
    * components. The URI is split into components, then the function is
    * executed on each component, and finally the components are combined
    * again.
    *
    * @param uri the URI
    * @param f   the mapping function for components
    * @return the resulting URI
    */
  def mapComponents(uri: String)(f: String => String): String = {
    val components = splitComponents(uri)
    val mappedComponents = components map f
    fromComponents(mappedComponents)
  }

  /**
    * Encodes all the components of the given URI. Note that it is typically
    * not possible to encode the URI as a whole because then the separators
    * will be encoded as well. This function splits the URI into its components
    * first, then applies the encoding, and finally combines the parts to the
    * resulting URI.
    *
    * @param uri the URI
    * @return the URI with its components encoded
    */
  def encodeComponents(uri: String): String =
    mapComponents(uri)(encode)

  /**
    * Decodes all the components of the given URI. Works like
    * ''encodeComponents()'', but applies decoding to the single components.
    *
    * @param uri the URI
    * @return the URI with its components decoded
    */
  def decodeComponents(uri: String): String =
    mapComponents(uri)(decode)

  /**
    * Returns the number of components of the given URI. This is defined as the
    * number of separator characters contained in the URI.
    *
    * @param uri the URI
    * @return the number of components the URI consists of
    */
  def componentCount(uri: String): Int = {
    @tailrec def findAndCountSeparator(startIdx: Int, count: Int): Int = {
      val pos = uri.indexOf(UriSeparator, startIdx)
      if (pos < 0) count
      else findAndCountSeparator(pos + 1, count + 1)
    }

    findAndCountSeparator(0, 0)
  }

  /**
    * Searches for the position of the name component in the given URI. If
    * found, its index is returned; otherwise, result is -1.
    *
    * @param uri the URI
    * @return the position of the name component or -1
    */
  private def findNameComponentPos(uri: String): Int =
    uri lastIndexOf UriSeparator
}
