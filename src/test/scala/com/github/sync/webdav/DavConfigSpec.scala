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

package com.github.sync.webdav

import akka.util.Timeout
import com.github.sync._
import org.scalatest.{FlatSpec, Matchers}

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
class DavConfigSpec extends FlatSpec with Matchers with AsyncTestHelper {

  import DavConfigSpec._

  "DavConfig" should "fill the list of modified properties correctly" in {
    val expModifiedProperties = List(ModifiedProp, DavConfig.DefaultModifiedProperty)
    val config = DavConfig(DavUri, Some(ModifiedProp), Some(ModifiedNamespace),
      deleteBeforeOverride = true, DavTimeout)

    config.modifiedProperties should contain theSameElementsInOrderAs expModifiedProperties
  }

  it should "eliminate duplicates in the list of modified properties" in {
    val config = DavConfig(DavUri, Some(DavConfig.DefaultModifiedProperty), Some(ModifiedNamespace),
      deleteBeforeOverride = true, DavTimeout)

    config.modifiedProperties should contain only DavConfig.DefaultModifiedProperty
  }

  it should "set the default last modified property if undefined" in {
    val config = DavConfig(DavUri, None, None, deleteBeforeOverride = false, DavTimeout)

    config.lastModifiedProperty should be(DavConfig.DefaultModifiedProperty)
  }
}
