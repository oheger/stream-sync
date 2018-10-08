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

package com.github.sync.webdav

import akka.http.scaladsl.model.IllegalUriException
import com.github.sync._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Test class for ''DavConfig''.
  */
class DavConfigSpec extends FlatSpec with Matchers with AsyncTestHelper {
  /**
    * Checks whether correct supported arguments are returned for the given
    * structure type.
    *
    * @param structType the structure type
    */
  private def checkSupportedArguments(structType: StructureType): Unit = {
    val prefix = "--" + structType.name
    val expArguments = List(SupportedArgument(prefix + DavConfig.PropUser, mandatory = true),
      SupportedArgument(prefix + DavConfig.PropPassword, mandatory = true),
      SupportedArgument(prefix + DavConfig.PropModifiedProperty, mandatory = false))

    DavConfig.supportedArgumentsFor(structType) should contain only (expArguments: _*)
  }

  "DavConfig" should "return supported arguments for the source structure" in {
    checkSupportedArguments(SourceStructureType)
  }

  it should "return supported arguments for the destination structure" in {
    checkSupportedArguments(DestinationStructureType)
  }

  it should "create a new config instance" in {
    val uri = "https://test.webdav.tst/"
    val user = "scott"
    val password = "tiger"
    val modifiedProp = "myModifiedTime"
    val args = Map("--" + SourceStructureType.name + DavConfig.PropUser -> user,
      "--" + SourceStructureType.name + DavConfig.PropPassword -> password,
      "--" + SourceStructureType.name + DavConfig.PropModifiedProperty -> modifiedProp,
      "--" + DestinationStructureType.name + DavConfig.PropUser -> "otherUser")
    val expConfig = DavConfig(uri, user, password, modifiedProp)

    val config = futureResult(DavConfig(SourceStructureType, uri, args))
    config should be(expConfig)
  }

  it should "return a failed future if a mandatory property is missing" in {
    val args = Map(SourceStructureType.name + DavConfig.PropUser -> "user",
      DestinationStructureType.name + DavConfig.PropPassword -> "pwd",
      DestinationStructureType.name + DavConfig.PropModifiedProperty -> "mod")

    expectFailedFuture[NoSuchElementException](DavConfig(DestinationStructureType, "root", args))
  }

  it should "set the default last modified property if undefined" in {
    val args = Map("--" + DestinationStructureType.name + DavConfig.PropUser -> "user",
      "--" + DestinationStructureType.name + DavConfig.PropPassword -> "pwd")

    val config = futureResult(DavConfig(DestinationStructureType, "root", args))
    config.lastModifiedProperty should be(DavConfig.DefaultModifiedProperty)
  }

  it should "return a failed future for an invalid URI" in {
    val args = Map(SourceStructureType.name + DavConfig.PropUser -> "user",
      SourceStructureType.name + DavConfig.PropPassword -> "pwd",
      SourceStructureType.name + DavConfig.PropModifiedProperty -> "mod")

    expectFailedFuture[IllegalUriException] {
      DavConfig(SourceStructureType, "https://this is not! a valid URI??", args)
    }
  }
}
