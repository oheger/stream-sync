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

package com.github.sync.webdav

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import com.github.sync._
import com.github.sync.http.SyncNoAuth
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

object DavConfigSpec {
  /** A timeout value. */
  private val DavTimeout = Timeout(11.seconds)

  /** Test URI of a Dav server. */
  private val DavUri = "https://test.webdav.tst/"

  /** Test modified property name. */
  private val ModifiedProp = "myModifiedTime"

  /** Test modified namespace. */
  private val ModifiedNamespace = "urn:schemas-test-org:"
}

/**
  * Test class for ''DavConfig''.
  */
class DavConfigSpec extends AnyFlatSpec with Matchers with AsyncTestHelper {

  import DavConfigSpec._

  "DavConfig" should "fill the list of modified properties correctly" in {
    val expModifiedProperties = List(ModifiedProp, DavConfig.DefaultModifiedProperty)
    val config = DavConfig(DavUri, Some(ModifiedProp), Some(ModifiedNamespace),
      deleteBeforeOverride = true, DavTimeout, SyncNoAuth)

    config.modifiedProperties should contain theSameElementsInOrderAs expModifiedProperties
  }

  it should "eliminate duplicates in the list of modified properties" in {
    val config = DavConfig(DavUri, Some(DavConfig.DefaultModifiedProperty), Some(ModifiedNamespace),
      deleteBeforeOverride = true, DavTimeout, SyncNoAuth)

    config.modifiedProperties should contain only DavConfig.DefaultModifiedProperty
  }

  it should "set the default last modified property if undefined" in {
    val config = DavConfig(DavUri, None, None, deleteBeforeOverride = false, DavTimeout, SyncNoAuth)

    config.lastModifiedProperty should be(DavConfig.DefaultModifiedProperty)
  }

  it should "remove a trailing slash from the root URI" in {
    val config = DavConfig(DavUri, None, None, deleteBeforeOverride = false, DavTimeout, SyncNoAuth)

    config.rootPath should be("")
  }

  it should "correctly resolve a URI" in {
    val rootUri = Uri("https://github.com/oheger/stream-sync")
    val elemUri = "/test/some stuff/action.txt"
    val expectedUri = Uri("/oheger/stream-sync/test/some%20stuff/action.txt")
    val config = DavConfig(rootUri, None, None, deleteBeforeOverride = false, DavTimeout, SyncNoAuth)

    config resolveRelativeUri elemUri should be(expectedUri)
  }

  it should "handle a root URI with a trailing slash" in {
    val elemUri = "/stream-sync"
    val expectedUri = Uri("/stream-sync")
    val config = DavConfig(DavUri, None, None, deleteBeforeOverride = false, DavTimeout, SyncNoAuth)

    config resolveRelativeUri elemUri should be(expectedUri)
  }

  it should "support URIs ending with a slash" in {
    val rootUri = Uri("https://github.com/oheger/stream-sync")
    val elemUri = "/test/some stuff/sub"
    val expectedUri = Uri("/oheger/stream-sync/test/some%20stuff/sub/")
    val config = DavConfig(rootUri, None, None, deleteBeforeOverride = false, DavTimeout, SyncNoAuth)

    config.resolveRelativeUri(elemUri, withTrailingSlash = true) should be(expectedUri)
  }

  it should "support an alternative prefix for relative URIs" in {
    val prefix = "/a%20path"
    val elemUri = "/foo/elem.txt"
    val expectedUri = Uri(prefix + elemUri)
    val config = DavConfig(DavUri, None, None, deleteBeforeOverride = false, DavTimeout, SyncNoAuth)

    config.resolveRelativeUri(elemUri, prefix = prefix) should be(expectedUri)
  }
}
