/*
 * Copyright 2018-2022 The Developers Team.
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

package com.github.sync.cli

import akka.util.Timeout
import com.github.scli.ParameterExtractor
import com.github.scli.ParameterExtractor.ExtractionContext
import com.github.sync.cli.ExtractorTestHelper.{toExtractionContext, toParametersMap}
import com.github.sync.cli.SyncCliStreamConfig.StreamConfig
import com.github.sync.stream.Throttle
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success, Try}
import scala.concurrent.duration.*

object SyncCliStreamConfigSpec:

  /**
    * Executes the extractor for the stream config against the parameters
    * specified and returns the result.
    *
    * @param args the map with arguments
    * @return the result returned by the extractor
    */
  private def runConfigExtractor(args: Map[String, String]): (Try[StreamConfig], ExtractionContext) =
    val paramCtx = toExtractionContext(toParametersMap(args))
    ParameterExtractor.runExtractor(SyncCliStreamConfig.streamConfigExtractor, paramCtx)

  /**
    * Executes the extractor for the stream config against the parameters
    * specified and expects a success result. The resulting configuration is
    * returned. In case of a failure, the test fails.
    *
    * @param args the map with arguments
    * @return the success result returned by the extractor
    */
  private def extractConfig(args: Map[String, String]): StreamConfig =
    val (triedConfig, _) = runConfigExtractor(args)
    triedConfig match
      case Success(config) => config
      case Failure(exception) =>
        throw new AssertionError("Failed to extract structure config", exception)

  /**
    * Executes the extractor for the stream config against the parameters
    * specified and expects a failure result. The exception is returned. An
    * unexpected success result causes the test to fail.
    *
    * @param args the map with arguments
    * @return the exception 
    */
  private def expectFailure(args: Map[String, String]): Throwable =
    val (triedConfig, _) = runConfigExtractor(args)
    triedConfig match
      case Failure(exception) => exception
      case Success(value) =>
        throw new AssertionError("Unexpected success result: " + value)

/**
  * Test class for ''SyncCliStreamConfig''.
  */
class SyncCliStreamConfigSpec extends AnyFlatSpec, Matchers :

  import SyncCliStreamConfigSpec.*

  "SyncCliStreamConfig" should "return a default dry-run mode" in {
    val config = extractConfig(Map.empty)

    config.dryRun shouldBe false
  }

  it should "support enabling the dry-run mode" in {
    val argsMap = Map(SyncCliStreamConfig.DryRunOption -> "true")

    val config = extractConfig(argsMap)
    config.dryRun shouldBe true
  }

  it should "return a default timeout if no timeout option is provided" in {
    val config = extractConfig(Map.empty)

    config.timeout should be(SyncCliStreamConfig.DefaultTimeout)
  }

  it should "return the configured timeout option value" in {
    val TimeoutValue = 42

    val config = extractConfig(Map(SyncCliStreamConfig.TimeoutOption -> TimeoutValue.toString))
    config.timeout should be(Timeout(TimeoutValue.seconds))
  }

  it should "handle an invalid timeout value" in {
    val timeoutStr = "invalidTimeout!"
    val argsMap = Map(SyncCliStreamConfig.TimeoutOption -> timeoutStr)

    val ex = expectFailure(argsMap)
    ex.getMessage should include(timeoutStr)
    ex.getMessage should include(SyncCliStreamConfig.TimeoutOption)
  }

  it should "handle an undefined option for the file times threshold" in {
    val config = extractConfig(Map.empty)

    config.ignoreTimeDelta should be(None)
  }

  it should "evaluate the threshold for file time deltas" in {
    val Delta = 28
    val argsMap = Map(SyncCliStreamConfig.IgnoreTimeDeltaOption -> Delta.toString)

    val config = extractConfig(argsMap)
    config.ignoreTimeDelta.get.deltaSec should be(Delta)
  }

  it should "handle an invalid threshold for file time deltas" in {
    val InvalidValue = "not a threshold for a time delta!"
    val argsMap = Map(SyncCliStreamConfig.IgnoreTimeDeltaOption -> InvalidValue)

    val ex = expectFailure(argsMap)
    ex.getMessage should include(InvalidValue)
    ex.getMessage should include(SyncCliStreamConfig.IgnoreTimeDeltaOption)
  }

  it should "handle an undefined option for the operations per unit" in {
    val config = extractConfig(Map.empty)

    config.opsPerUnit should be(None)
  }

  it should "evaluate the threshold for the operations per unit" in {
    val OpsCount = 17
    val argsMap = Map(SyncCliStreamConfig.OpsPerUnitOption -> OpsCount.toString)

    val config = extractConfig(argsMap)
    config.opsPerUnit should be(Some(OpsCount))
  }

  it should "handle an invalid threshold for the operations per second" in {
    val InvalidValue = "not a valid number of ops per sec"
    val argsMap = Map(SyncCliStreamConfig.OpsPerUnitOption -> InvalidValue)

    val ex = expectFailure(argsMap)
    ex.getMessage should include(InvalidValue)
    ex.getMessage should include(SyncCliStreamConfig.OpsPerUnitOption)
  }

  it should "evaluate the time unit for throttling" in {
    val namesToUnits = Map("s" -> Throttle.TimeUnit.Second, "second" -> Throttle.TimeUnit.Second,
      "m" -> Throttle.TimeUnit.Minute, "MINUTE" -> Throttle.TimeUnit.Minute,
      "H" -> Throttle.TimeUnit.Hour, "hour" -> Throttle.TimeUnit.Hour)

    namesToUnits foreach { (name, unit) =>
      val argsMap = Map(SyncCliStreamConfig.ThrottleUnitOption -> name)
      val config = extractConfig(argsMap)
      config.throttleUnit should be(unit)
    }
  }

  it should "set a default for the time unit for throttling" in {
    val config = extractConfig(Map.empty)

    config.throttleUnit should be(Throttle.TimeUnit.Second)
  }

  it should "assume mirror mode per default" in {
    val config = extractConfig(Map.empty)

    config.modeConfig should be(SyncCliStreamConfig.MirrorStreamConfig)
  }
