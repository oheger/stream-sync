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
import com.github.scli.ParameterExtractor.{CliExtractor, createRepresentation, optionValue, switchValue}
import com.github.sync.stream.{IgnoreTimeDelta, Throttle}

import scala.util.Try
import scala.concurrent.duration.*

/**
  * A module defining CLI-related configuration parameters for the sync stream
  * as a whole.
  *
  * The module implements the part of the CLI that determines whether a sync or
  * a mirror stream should be run, with the corresponding settings. it defines
  * the required extractors and configuration data structures. In addition,
  * global stream-related settings are supported.
  */
object SyncCliStreamConfig:
  /** Name of the option for the dry-run mode. */
  final val DryRunOption: String = "dry-run"

  /** Help text for the dry-run mode option. */
  final val DryRunHelp =
    """Enables a special dry-run mode. In this mode, no changes are applied to the destination structure. \
      |From the log, it can be determined, which operations would have been performed.
      |""".stripMargin

  /** Name of the option that defines a timeout for sync operations. */
  final val TimeoutOption: String = "timeout"

  /** Help text for the timeout option. */
  final val TimeoutHelp =
    """Sets a timeout for sync operations (in seconds). Operations taking longer than this time are \
      |aborted and considered as failures.
      |""".stripMargin

  /**
    * Name of the option that defines the threshold for time deltas to be
    * ignored. When comparing the timestamps of two files the files are
    * considered different only if the difference of their timestamps is
    * greater than this value (in seconds).
    */
  final val IgnoreTimeDeltaOption: String = "ignore-time-delta"

  /** Help text for the ignore time delta option. */
  final val IgnoreTimeDeltaHelp =
    """Sets a threshold in seconds to be applied when comparing the timestamps of files. Only if the \
      |delta of the timestamps is greater than this threshold, the files are considered different. \
      |This can be used to deal with file systems with different granularities in their last modified \
      |timestamps.
      |""".stripMargin

  /**
    * Name of the option that restricts the number of sync operations that can
    * be executed within a time unit. This is useful for instance when syncing
    * to a server that accepts only a limit number of requests per time unit.
    */
  final val OpsPerUnitOption: String = "throttle"

  /** Help text for the operations per unit option. */
  final val OpsPerUnitHelp =
    """Allows limiting the number of sync operations executed per time unit. The option value is the \
      |number of operations that are allowed in a specific time unit. The time unit itself is defined by \
      |the 'throttle-unit' option. This can be used to protected servers from too much \
      |load. Per default, there is no restriction for the operations that are executed.
      |""".stripMargin

  /**
    * Name of the option that defines the time unit in which throttling is
    * applied.
    */
  final val ThrottleUnitOption: String = "throttle-unit"

  /** Help text for the throttle unit option. */
  final val ThrottleUnitHelp =
    """Defines the time unit for throttling. This is used together with --throttle to define in a fine-granular \
      |way the allowed number of operations in a specific time frame. Valid values are 'S' or 'Second', \
      |'M' or 'Minute', 'H' or 'Hour' (case does not matter). For instance, the options '--throttle 1000 \
      |--throttle-unit Hour' would define a threshold of 1000 operations per hour. Defaults to 'Second'.""".stripMargin

  /** The default timeout for sync operations. */
  final val DefaultTimeout: Timeout = Timeout(1.minute)

  /**
    * A map assigning the supported names for time units to the corresponding
    * objects.
    */
  private final val TimeUnits = Map("h" -> Throttle.TimeUnit.Hour, "hour" -> Throttle.TimeUnit.Hour,
    "m" -> Throttle.TimeUnit.Minute, "minute" -> Throttle.TimeUnit.Minute,
    "s" -> Throttle.TimeUnit.Second, "second" -> Throttle.TimeUnit.Second)

  /**
    * A configuration class that combines all the properties of the sync
    * stream that are not related to a specific category, such as logging or
    * cryptography.
    *
    * @param dryRun          flag whether only a dry-run should be done
    * @param timeout         a timeout for sync operations
    * @param ignoreTimeDelta optional threshold for a time difference between
    *                        two files that should be ignored
    * @param opsPerUnit      optional restriction for the number of sync
    *                        operations per time unit
    * @param throttleUnit    defines the time unit for throttling
    */
  case class StreamConfig(dryRun: Boolean,
                          timeout: Timeout,
                          ignoreTimeDelta: Option[IgnoreTimeDelta],
                          opsPerUnit: Option[Int],
                          throttleUnit: Throttle.TimeUnit)

  /**
    * Returns an extractor that extracts the parameters related to the stream.
    *
    * @return the extractor for the ''StreamConfig''
    */
  def streamConfigExtractor: CliExtractor[Try[StreamConfig]] =
    for
      dryRun <- dryRunExtractor()
      timeout <- timeoutExtractor()
      timeDelta <- ignoreTimeDeltaExtractor()
      opsPerUnit <- opsPerUnitExtractor()
      throttleUnit <- throttleTimeUnitExtractor()
    yield createStreamConfig(dryRun, timeout, timeDelta, opsPerUnit, throttleUnit)

  /**
    * Returns an extractor for the dry-run switch.
    *
    * @return the extractor for the dry-run mode switch
    */
  private def dryRunExtractor(): CliExtractor[Try[Boolean]] =
    switchValue(DryRunOption, Some(DryRunHelp)).alias("d")

  /**
    * Returns an extractor that extracts the timeout from the command line.
    * This extractor extracts an int value, which is interpreted as timeout in
    * seconds. A default timeout is set if the option is undefined.
    *
    * @return the extractor to extract the timeout value
    */
  private def timeoutExtractor(): CliExtractor[Try[Timeout]] =
    optionValue(TimeoutOption, Some(TimeoutHelp))
      .alias("t")
      .toInt
      .mapTo(time => Timeout(time.seconds))
      .fallbackValue(DefaultTimeout)
      .mandatory

  /**
    * Returns an extractor that extracts the value of the option for ignoring
    * file time deltas. This extractor is based on the extractor for an
    * optional parameter, but the result has to be mapped to an integer.
    *
    * @return the extractor for the ignore time delta option
    */
  private def ignoreTimeDeltaExtractor(): CliExtractor[Try[Option[IgnoreTimeDelta]]] =
    optionValue(IgnoreTimeDeltaOption, Some(IgnoreTimeDeltaHelp))
      .toInt
      .mapTo(sec => IgnoreTimeDelta(sec.seconds))

  /**
    * Returns an extractor that extracts the value of the option for the number
    * of sync operations per time unit.
    *
    * @return the extractor for the ops per time unit option
    */
  private def opsPerUnitExtractor(): CliExtractor[Try[Option[Int]]] =
    optionValue(OpsPerUnitOption, Some(OpsPerUnitHelp))
      .toInt

  /**
    * Returns an extractor that extracts the time unit for the throttling of
    * operations.
    *
    * @return the extractor for the time unit for throttling
    */
  private def throttleTimeUnitExtractor(): CliExtractor[Try[Throttle.TimeUnit]] =
    optionValue(ThrottleUnitOption, Some(ThrottleUnitHelp))
      .toLower
      .toEnum(TimeUnits.get)
      .fallbackValue(Throttle.TimeUnit.Second)
      .mandatory

  /**
    * Tries to construct a ''StreamConfig'' object from the passed in components.
    *
    * @param triedDryRun       the dry-run component
    * @param triedTimeout      the timeout component
    * @param triedTimeDelta    the ignore file time delta component
    * @param triedOpsPerUnit   the ops per unit component
    * @param triedThrottleUnit the throttle unit component
    * @return a ''Try'' with the ''StreamConfig''
    */
  private def createStreamConfig(triedDryRun: Try[Boolean],
                                 triedTimeout: Try[Timeout],
                                 triedTimeDelta: Try[Option[IgnoreTimeDelta]],
                                 triedOpsPerUnit: Try[Option[Int]],
                                 triedThrottleUnit: Try[Throttle.TimeUnit]): Try[StreamConfig] =
    createRepresentation(triedDryRun, triedTimeout, triedTimeDelta, triedOpsPerUnit,
      triedThrottleUnit)(StreamConfig.apply)

