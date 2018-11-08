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

package com.github.sync.local

import java.nio.file.Paths
import java.time.{DateTimeException, ZoneId}

import com.github.sync._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Test class for ''LocalFsConfig''.
  */
class LocalFsConfigSpec extends FlatSpec with Matchers with AsyncTestHelper {
  "LocalFsConfigSpec" should "create an instance without a time zone" in {
    val RootPath = "my/sync/root"
    val config = futureResult(LocalFsConfig(SourceStructureType, RootPath, Map.empty))

    config.rootPath should be(Paths get RootPath)
    config.optTimeZone shouldBe 'empty
  }

  it should "evaluate a zone ID in the properties" in {
    val zoneId = "UTC+02:00"
    val props = Map("--" + DestinationStructureType.name + LocalFsConfig.PropTimeZone -> zoneId)
    val config = futureResult(LocalFsConfig(DestinationStructureType, "somePath", props))

    config.optTimeZone should be(Some(ZoneId.of(zoneId)))
  }

  it should "return a failed future for an invalid zone ID" in {
    val props = Map("--" + SourceStructureType.name + LocalFsConfig.PropTimeZone -> "invalid")

    expectFailedFuture[DateTimeException](LocalFsConfig(SourceStructureType, "path", props))
  }

  /**
    * Checks whether correct supported arguments are returned for the given
    * structure type.
    *
    * @param structType the structure type
    */
  private def checkSupportedArguments(structType: StructureType): Unit = {
    val prefix = "--" + structType.name
    val expArguments = List(SupportedArgument(prefix + LocalFsConfig.PropTimeZone,
      mandatory = false))

    LocalFsConfig.supportedArgumentsFor(structType) should contain only (expArguments: _*)
  }

  it should "return a sequence with supported arguments for the source structure type" in {
    checkSupportedArguments(SourceStructureType)
  }

  it should "return a sequence with supported arguments for the dest structure type" in {
    checkSupportedArguments(DestinationStructureType)
  }
}
