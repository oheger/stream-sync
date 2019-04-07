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

package com.github.sync.util

import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''UriEncodingHelper''.
  */
class UriEncodingHelperSpec extends FlatSpec with Matchers {
  "UriEncodingHelper" should "URL-encode a string" in {
    UriEncodingHelper.encode("test/ + verify") should be("test%2F%20%2B%20verify")
  }

  it should "not modify a string when no encoding is needed" in {
    val PlainUri = "noNeedToEncode"

    UriEncodingHelper.encode(PlainUri) should be theSameInstanceAs PlainUri
  }

  it should "URL-decode a string" in {
    UriEncodingHelper.decode("test/%20+%20verify") should be("test/ + verify")
  }

  it should "not modify a string when no decoding is needed" in {
    val PlainUri = "noNeedToDecode"

    UriEncodingHelper.decode(PlainUri) should be theSameInstanceAs PlainUri
  }

  it should "remove trailing characters from a string" in {
    val Path = "/test-uri"

    UriEncodingHelper.removeTrailing(Path + "////", "/") should be(Path)
  }

  it should "not modify a string if no trailing characters are removed" in {
    val Uri = "/noTrailingSlash"

    UriEncodingHelper.removeTrailing(Uri, "/") should be theSameInstanceAs Uri
  }

  it should "not modify a URI that already ends with a separator" in {
    val Uri = "/my/test/uri/"

    UriEncodingHelper.withTrailingSeparator(Uri) should be theSameInstanceAs Uri
  }

  it should "add a separator character to a URI if necessary" in {
    val Uri = "/not/ending/with/separator"

    UriEncodingHelper.withTrailingSeparator(Uri) should be(Uri + "/")
  }

  it should "not modify a URI if no trailing separator can be removed" in {
    val Uri = "/this/has/no/trailing/separator"

    UriEncodingHelper removeTrailingSeparator Uri should be(Uri)
  }

  it should "remove a trailing separator from a URI" in {
    val Uri = "/trailing/separator/will/be/gone"

    UriEncodingHelper removeTrailingSeparator Uri + "//" should be(Uri)
  }

  it should "not modify a string if no prefix can be removed" in {
    val Uri = "foo"

    UriEncodingHelper.removeLeading(Uri, "bar") should be(Uri)
  }

  it should "remove an existing prefix from a string" in {
    val Prefix = "https://"
    val Host = "test.org"

    UriEncodingHelper.removeLeading(Prefix + Host, Prefix) should be(Host)
  }

  it should "not change a URI that does not start with a leading separator" in {
    val Uri = "uri/without/leading/separator"

    UriEncodingHelper removeLeadingSeparator Uri should be(Uri)
  }

  it should "remove leading separators from a URI" in {
    val Uri = "uri/with/removed/separators"

    UriEncodingHelper removeLeadingSeparator "////" + Uri should be(Uri)
  }

  it should "report that a URI has a parent element" in {
    UriEncodingHelper hasParent "/foo/bar" shouldBe true
  }

  it should "detect a top-level URI without a slash" in {
    UriEncodingHelper hasParent "foo" shouldBe false
  }

  it should "detect a top-level URI with a leading slash" in {
    UriEncodingHelper hasParent "/foo" shouldBe false
  }

  it should "split a URI in a parent and a name component" in {
    val Parent = "/the/parent/uri"
    val Name = "name.txt"

    val (p, n) = UriEncodingHelper splitParent Parent + "/" + Name
    p should be(Parent)
    n should be(Name)
  }

  it should "handle a split operation for a top-level URI starting with a slash" in {
    val Name = "justAName"

    val (p, n) = UriEncodingHelper splitParent "/" + Name
    p should be("")
    n should be(Name)
  }

  it should "handle a split operation for a top-level URI without a slash" in {
    val Name = "nameOnly"

    val (p, n) = UriEncodingHelper splitParent Name
    p should be("")
    n should be(Name)
  }

  it should "handle a split operation if the URI ends with a separator" in {
    val Parent = "/parent"
    val Name = "child"

    val (p, n) = UriEncodingHelper splitParent Parent + "/" + Name + "/"
    p should be(Parent)
    n should be(Name)
  }

  it should "handle a split operation for a top-level URI ending with a slash" in {
    val Name = "top-level"

    val (p, n) = UriEncodingHelper splitParent Name + "/"
    p should be("")
    n should be(Name)
  }
}
