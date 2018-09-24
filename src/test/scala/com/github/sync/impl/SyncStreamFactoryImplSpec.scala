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

package com.github.sync.impl

import com.github.sync.webdav.DavConfig
import com.github.sync.{AsyncTestHelper, DestinationStructureType, SourceStructureType,
  StructureType}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Test class for ''SyncStreamFactoryImpl''. This class only tests basic
  * functionality. Advanced features are tested by integration test classes.
  */
class SyncStreamFactoryImplSpec extends FlatSpec with Matchers with AsyncTestHelper {
  "SyncStreamFactoryImpl" should "return additional arguments for a local path" in {
    val args = futureResult(SyncStreamFactoryImpl.additionalArguments(
      "/local/path", SourceStructureType))

    args should have size 0
  }

  /**
    * Helper method for checking whether correct additional arguments for a
    * WebDav URI are returned.
    *
    * @param structType the structure type
    */
  private def checkWebDavArguments(structType: StructureType): Unit = {
    val uri = SyncStreamFactoryImpl.PrefixWebDav + "https://webdav.test.com/foo"

    val args = futureResult(SyncStreamFactoryImpl.additionalArguments(uri, structType))
    args should contain theSameElementsAs DavConfig.supportedArgumentsFor(structType)
  }

  it should "return additional arguments for a WebDav source structure" in {
    checkWebDavArguments(SourceStructureType)
  }

  it should "return additional arguments for a WebDav destination structure" in {
    checkWebDavArguments(DestinationStructureType)
  }
}
