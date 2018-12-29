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
      SupportedArgument(prefix + DavConfig.PropModifiedProperty, mandatory = false),
      SupportedArgument(prefix + DavConfig.PropModifiedNamespace, mandatory = false),
      SupportedArgument(prefix + DavConfig.PropDeleteBeforeOverride, mandatory = true,
        defaultValue = Some(DavConfig.DefaultDeleteBeforeOverride)))

    DavConfig.supportedArgumentsFor(structType) should contain only (expArguments: _*)
  }

  /**
    * Returns a minimum valid map with parameters. This map contains all
    * mandatory parameters.
    *
    * @return the minimum valid map with parameters
    */
  private def minimumParametersMap(): Map[String, String] =
    Map("--" + DestinationStructureType.name + DavConfig.PropUser -> "user",
      "--" + DestinationStructureType.name + DavConfig.PropPassword -> "pwd",
      "--" + DestinationStructureType.name + DavConfig.PropDeleteBeforeOverride -> "false")

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
    val modifiedNamespace = "urn:schemas-test-org:"
    val args = Map("--" + SourceStructureType.name + DavConfig.PropUser -> user,
      "--" + SourceStructureType.name + DavConfig.PropPassword -> password,
      "--" + SourceStructureType.name + DavConfig.PropModifiedProperty -> modifiedProp,
      "--" + SourceStructureType.name + DavConfig.PropModifiedNamespace -> modifiedNamespace,
      "--" + SourceStructureType.name + DavConfig.PropDeleteBeforeOverride -> "true",
      "--" + DestinationStructureType.name + DavConfig.PropUser -> "otherUser")
    val expConfig = DavConfig(uri, user, password, modifiedProp, Some(modifiedNamespace),
      deleteBeforeOverride = true,
      modifiedProperties = List(modifiedProp, DavConfig.DefaultModifiedProperty))

    val config = futureResult(DavConfig(SourceStructureType, uri, args))
    config should be(expConfig)
  }

  it should "fill the list of modified properties correctly" in {
    val args = Map("--" + SourceStructureType.name + DavConfig.PropUser -> "user",
      "--" + SourceStructureType.name + DavConfig.PropPassword -> "password",
      "--" + SourceStructureType.name + DavConfig.PropDeleteBeforeOverride -> "true",
      "--" + DestinationStructureType.name + DavConfig.PropUser -> "otherUser")

    val config = futureResult(DavConfig(SourceStructureType, "someUri", args))
    config.modifiedProperties should contain only DavConfig.DefaultModifiedProperty
  }

  it should "eliminate duplicates in the list of modified properties" in {
    val args = Map("--" + SourceStructureType.name + DavConfig.PropUser -> "user",
      "--" + SourceStructureType.name + DavConfig.PropPassword -> "password",
      "--" + SourceStructureType.name + DavConfig.PropModifiedProperty ->
        DavConfig.DefaultModifiedProperty,
      "--" + SourceStructureType.name + DavConfig.PropDeleteBeforeOverride -> "true",
      "--" + DestinationStructureType.name + DavConfig.PropUser -> "otherUser")

    val config = futureResult(DavConfig(SourceStructureType, "someUri", args))
    config.modifiedProperties should contain only DavConfig.DefaultModifiedProperty
  }

  it should "return a failed future if a mandatory property is missing" in {
    val args = Map(SourceStructureType.name + DavConfig.PropUser -> "user",
      DestinationStructureType.name + DavConfig.PropPassword -> "pwd",
      DestinationStructureType.name + DavConfig.PropModifiedProperty -> "mod")

    expectFailedFuture[NoSuchElementException](DavConfig(DestinationStructureType, "root", args))
  }

  it should "set the default last modified property if undefined" in {
    val args = minimumParametersMap()

    val config = futureResult(DavConfig(DestinationStructureType, "root", args))
    config.lastModifiedProperty should be(DavConfig.DefaultModifiedProperty)
  }

  it should "set an undefined namespace if this property is not set" in {
    val args = minimumParametersMap()

    val config = futureResult(DavConfig(DestinationStructureType, "root", args))
    config.lastModifiedNamespace shouldBe 'empty
  }

  it should "interpret an arbitrary value for the delete-before-override property" in {
    val args = minimumParametersMap() + (DavConfig.PropDeleteBeforeOverride -> "unknown")

    val config = futureResult(DavConfig(DestinationStructureType, "root", args))
    config.deleteBeforeOverride shouldBe false
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
