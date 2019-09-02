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

import akka.http.scaladsl.model.IllegalUriException
import akka.util.Timeout
import com.github.sync.SyncTypes.{DestinationStructureType, SourceStructureType, StructureType, SupportedArgument}
import com.github.sync._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object DavConfigSpec {
  /** A timeout value. */
  private val DavTimeout = Timeout(11.seconds)

  /** Test URI of a Dav server. */
  private val DavUri = "https://test.webdav.tst/"

  /** Test user name. */
  private val User = "scott"

  /** Test password. */
  private val Password = "tiger"

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
    //TODO will become obsolete
    checkSupportedArguments(SourceStructureType)
  }

  it should "return supported arguments for the destination structure" in {
    //TODO will become obsolete
    checkSupportedArguments(DestinationStructureType)
  }

  it should "create a new config instance" in {
    //TODO will become obsolete
    val args = Map("--" + SourceStructureType.name + DavConfig.PropUser -> User,
      "--" + SourceStructureType.name + DavConfig.PropPassword -> Password,
      "--" + SourceStructureType.name + DavConfig.PropModifiedProperty -> ModifiedProp,
      "--" + SourceStructureType.name + DavConfig.PropModifiedNamespace -> ModifiedNamespace,
      "--" + SourceStructureType.name + DavConfig.PropDeleteBeforeOverride -> "true",
      "--" + DestinationStructureType.name + DavConfig.PropUser -> "otherUser")
    val expConfig = DavConfig(DavUri, User, Password, ModifiedProp, Some(ModifiedNamespace),
      deleteBeforeOverride = true,
      modifiedProperties = List(ModifiedProp, DavConfig.DefaultModifiedProperty), DavTimeout)

    val config = futureResult(DavConfig(SourceStructureType, DavUri, DavTimeout, args))
    config should be(expConfig)
  }

  it should "fill the list of modified properties correctly" in {
    val expModifiedProperties = List(ModifiedProp, DavConfig.DefaultModifiedProperty)
    val config = DavConfig(DavUri, User, Password, Some(ModifiedProp), Some(ModifiedNamespace),
      deleteBeforeOverride = true, DavTimeout)

    config.modifiedProperties should contain theSameElementsInOrderAs expModifiedProperties
  }

  it should "eliminate duplicates in the list of modified properties" in {
    val config = DavConfig(DavUri, User, Password, Some(DavConfig.DefaultModifiedProperty), Some(ModifiedNamespace),
      deleteBeforeOverride = true, DavTimeout)

    config.modifiedProperties should contain only DavConfig.DefaultModifiedProperty
  }

  it should "return a failed future if a mandatory property is missing" in {
    //TODO will become obsolete
    val args = Map(SourceStructureType.name + DavConfig.PropUser -> "user",
      DestinationStructureType.name + DavConfig.PropPassword -> "pwd",
      DestinationStructureType.name + DavConfig.PropModifiedProperty -> "mod")

    expectFailedFuture[NoSuchElementException](DavConfig(DestinationStructureType, "root", DavTimeout, args))
  }

  it should "set the default last modified property if undefined" in {
    val config = DavConfig(DavUri, User, Password, None, None, deleteBeforeOverride = false, DavTimeout)

    config.lastModifiedProperty should be(DavConfig.DefaultModifiedProperty)
  }

  it should "set an undefined namespace if this property is not set" in {
    //TODO will become obsolete
    val args = minimumParametersMap()

    val config = futureResult(DavConfig(DestinationStructureType, "root", DavTimeout, args))
    config.lastModifiedNamespace shouldBe 'empty
  }

  it should "interpret an arbitrary value for the delete-before-override property" in {
    //TODO will become obsolete
    val args = minimumParametersMap() + (DavConfig.PropDeleteBeforeOverride -> "unknown")

    val config = futureResult(DavConfig(DestinationStructureType, "root", DavTimeout, args))
    config.deleteBeforeOverride shouldBe false
  }

  it should "return a failed future for an invalid URI" in {
    //TODO will become obsolete
    val args = Map(SourceStructureType.name + DavConfig.PropUser -> "user",
      SourceStructureType.name + DavConfig.PropPassword -> "pwd",
      SourceStructureType.name + DavConfig.PropModifiedProperty -> "mod")

    expectFailedFuture[IllegalUriException] {
      DavConfig(SourceStructureType, "https://this is not! a valid URI??", DavTimeout, args)
    }
  }
}
