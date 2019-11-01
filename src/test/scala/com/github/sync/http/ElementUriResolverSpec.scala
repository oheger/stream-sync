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

package com.github.sync.http

import akka.http.scaladsl.model.Uri
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''ElementUriResolver''.
  */
class ElementUriResolverSpec extends FlatSpec with Matchers {
  "An ElementUriResolver" should "correctly resolve a URI" in {
    val rootUri = Uri("https://github.com/oheger/stream-sync")
    val elemUri = "/test/some stuff/action.txt"
    val expectedUri = Uri("/oheger/stream-sync/test/some%20stuff/action.txt")
    val resolver = ElementUriResolver(rootUri)

    resolver resolveElementUri elemUri should be(expectedUri)
  }

  it should "handle a root URI with a trailing slash" in {
    val rootUri = Uri("https://github.com/oheger/")
    val elemUri = "/stream-sync"
    val expectedUri = Uri("/oheger/stream-sync")
    val resolver = ElementUriResolver(rootUri)

    resolver resolveElementUri elemUri should be(expectedUri)
  }

  it should "support URIs ending with a slash" in {
    val rootUri = Uri("https://github.com/oheger/stream-sync")
    val elemUri = "/test/some stuff/sub"
    val expectedUri = Uri("/oheger/stream-sync/test/some%20stuff/sub/")
    val resolver = ElementUriResolver(rootUri)

    resolver.resolveElementUri(elemUri, withTrailingSlash = true) should be(expectedUri)
  }
}
