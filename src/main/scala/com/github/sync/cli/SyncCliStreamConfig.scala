/*
 * Copyright 2018-2024 The Developers Team.
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

import com.github.scli.ParameterExtractor.{CliExtractor, conditionalGroupValue, constantExtractor, createRepresentation, excludingSwitches, optionValue, switchValue}
import com.github.sync.stream.{IgnoreTimeDelta, Throttle}
import org.apache.pekko.util.Timeout

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import scala.concurrent.duration.*
import scala.util.{Success, Try}

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

  /** Name of the option that defines the path to the sync log file. */
  final val SyncLogOption: String = "sync-log"

  /** Help text for the sync log option. */
  final val SyncLogHelp =
    """Defines the path from where to read the sync log. If specified, the sync process does not \
      |address the differences between the source and the destination structure, but executes the \
      |operations defined in the sync log.
      |""".stripMargin

  /**
    * Name of the option that switches the source and destination structures
    * for mirror streams. This offers an easy means to let the mirror stream
    * work in the opposite direction.
    */
  final val SwitchOption = "switch"

  /** Help text for the switch option. */
  final val SwitchOptionHelp =
    """If this flag is provided, the source and destination structures are switched, so that the sync \
      |process basically runs in the opposite direction. This is useful if you occasionally need to \
      |fetch data from the destination; then you do not have to write another sync command, but just \
      |add this flag to the existing one and revert the sync direction.""".stripMargin

  /**
    * Name of the option that defines the path where to store local state data
    * for sync streams.
    */
  final val StatePathOption = "state-path"

  /** Help text for the state path option. */
  final val StatePathHelp =
    """Sets the path where local state information about sync streams is stored. This is per default a subfolder \
      |of the user's home directory. The path is created automatically if it does not exist yet.""".stripMargin

  /** Name of the option that allows setting a name for a sync stream. */
  final val StreamNameOption = "stream-name"

  /** Help text for the stream name option. */
  final val StreamNameHelp =
    """Allows setting a nam for the current sync stream. This is mainly used to derive the names of files \
      |storing local state information. If no name is specified, a (non-readable) name is generated from the URLs \
      |defining the local and the remote structures.""".stripMargin

  /** Name of the option to import the local state of a sync stream. */
  final val ImportStateOption = "import-state"

  /** Help text for the import state option. */
  final val ImportStateHelp =
    """Specifies that only the local structure is to be imported into the local state. This import should be done \
      |once to setup the sync stream if local data is already available.""".stripMargin

  /** Name of the switch option that enables mirror mode. */
  final val MirrorMode = "mirror"

  /** Help text for the mirror mode option. */
  final val MirrorModeHelp =
    """Enables mirror mode. In this mode, a destination structure is modified to become an exact mirror of a \
      |source structure.""".stripMargin

  /** Name of the switch option that enables sync mode. */
  final val SyncMode = "sync"

  /** Help text for the sync mode option. */
  final val SyncModeHelp =
    """Enables sync mode. In this mode, the changes from a local and a remote structure are synced."""

  /** The default timeout for sync operations. */
  final val DefaultTimeout: Timeout = Timeout(1.minute)

  /**
    * The default name of a folder storing local state information for sync
    * streams. This folder is created in the user's home directory.
    */
  final val DefaultStateSubFolder = ".stream-sync"

  /**
    * A map assigning the supported names for time units to the corresponding
    * objects.
    */
  private final val TimeUnits = Map("h" -> Throttle.TimeUnit.Hour, "hour" -> Throttle.TimeUnit.Hour,
    "m" -> Throttle.TimeUnit.Minute, "minute" -> Throttle.TimeUnit.Minute,
    "s" -> Throttle.TimeUnit.Second, "second" -> Throttle.TimeUnit.Second)

  /** Name of the algorithm to generate hashes for sync stream names. */
  private val NameHashAlgorithm = "SHA-1"

  /**
    * A trait defining a sub-configuration for a stream that depends on the 
    * type of the stream. Concrete subclasses declare options specific to
    * concrete stream types.
    */
  sealed trait StreamModeConfig

  /**
    * A data class defining specific parameters for sync streams.
    *
    * @param statePath   the path where to store files with state information
    * @param streamName  the name of the stream
    * @param stateImport flag whether to import local state only
    */
  case class SyncStreamConfig(statePath: Path,
                              streamName: String,
                              stateImport: Boolean) extends StreamModeConfig

  /**
    * An data class defining specific parameters for mirror streams.
    *
    * @param syncLogPath an option with the path to a file containing sync
    *                    operations to be executed
    * @param switched    a flag whether src and dst configs should be
    *                    switched
    */
  case class MirrorStreamConfig(syncLogPath: Option[Path],
                                switched: Boolean) extends StreamModeConfig

  /**
    * A configuration class that combines all the properties of the sync
    * stream that are not related to a specific category.
    *
    * @param dryRun          flag whether only a dry-run should be done
    * @param timeout         a timeout for sync operations
    * @param ignoreTimeDelta optional threshold for a time difference between
    *                        two files that should be ignored
    * @param opsPerUnit      optional restriction for the number of sync
    *                        operations per time unit
    * @param throttleUnit    defines the time unit for throttling
    * @param modeConfig      the mode-specific config                        
    */
  case class StreamConfig(dryRun: Boolean,
                          timeout: Timeout,
                          ignoreTimeDelta: Option[IgnoreTimeDelta],
                          opsPerUnit: Option[Int],
                          throttleUnit: Throttle.TimeUnit,
                          modeConfig: StreamModeConfig)

  /**
    * Returns an extractor that extracts the parameters related to the stream.
    *
    * @param defaultStreamName a function to generate a stream name in case the
    *                          user did not specify one
    * @return the extractor for the ''StreamConfig''
    */
  def streamConfigExtractor(defaultStreamName: => String): CliExtractor[Try[StreamConfig]] =
    for
      dryRun <- dryRunExtractor()
      timeout <- timeoutExtractor()
      timeDelta <- ignoreTimeDeltaExtractor()
      opsPerUnit <- opsPerUnitExtractor()
      throttleUnit <- throttleTimeUnitExtractor()
      modeConfig <- modeConfigExtractor(defaultStreamName)
    yield createStreamConfig(dryRun, timeout, timeDelta, opsPerUnit, throttleUnit, modeConfig)

  /**
    * Generates a (not readable) name for a sync stream based on the URIs for
    * the local and remote structures.
    *
    * @param localUri  the URI of the local structure
    * @param remoteUri the URI of the remote structure
    * @return the generated stream name
    */
  def streamNameForUris(localUri: String, remoteUri: String): String =
    val uriStr = s"$localUri<=>$remoteUri"
    val digest = MessageDigest.getInstance(NameHashAlgorithm)
    val hash = digest.digest(uriStr.getBytes(StandardCharsets.UTF_8))
    Base64.getUrlEncoder.encodeToString(hash)

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
    * Returns an extractor that extracts the [[StreamModeConfig]] for the
    * current stream. The mode config is determined based on the presence of
    * the ''mirror'' or ''sync'' switches.
    *
    * @param defaultStreamName a default name for sync streams
    * @return the extractor for the mode config
    */
  private def modeConfigExtractor(defaultStreamName: => String): CliExtractor[Try[StreamModeConfig]] =
    val extMirrorMode = switchValue(MirrorMode, Some(MirrorModeHelp)).alias("M")
    val extSyncMode = switchValue(SyncMode, Some(SyncModeHelp))
    val extMode = excludingSwitches(allowOverride = false, extMirrorMode, extSyncMode)
      .fallbackValue(MirrorMode)
      .mandatory

    val extMap = Map(MirrorMode -> mirrorConfigExtractor,
      SyncMode -> syncStreamConfigExtractor(defaultStreamName))
    conditionalGroupValue(extMode, extMap)

  /**
    * Returns an extractor that extracts the configuration of a mirror stream.
    *
    * @return the extractor for the [[MirrorStreamConfig]]
    */
  private def mirrorConfigExtractor: CliExtractor[Try[StreamModeConfig]] =
    for
      syncLog <- optionValue(SyncLogOption, Some(SyncLogHelp)).toPath
      switched <- switchValue(SwitchOption, optHelp = Some(SwitchOptionHelp)).alias("S")
    yield createMirrorStreamConfig(syncLog, switched)

  /**
    * Returns an extractor that extracts the configuration of a sync stream.
    *
    * @param defaultStreamName a default name for a stream
    * @return the extractor for the [[SyncCliStreamConfig]]
    */
  private def syncStreamConfigExtractor(defaultStreamName: => String): CliExtractor[Try[StreamModeConfig]] =
    val extStatePath = optionValue(StatePathOption, Some(StatePathHelp))
      .fallbackValue(s"${System.getProperty("user.home")}/$DefaultStateSubFolder")
      .toPath
      .mandatory
    val extStreamName = optionValue(StreamNameOption, Some(StreamNameHelp))
      .fallbackValue(defaultStreamName)
      .mandatory
    val extStateImport = switchValue(ImportStateOption, Some(ImportStateHelp))

    for
      statePath <- extStatePath
      streamName <- extStreamName
      stateImport <- extStateImport
    yield createSyncStreamConfig(statePath, streamName, stateImport)

  /**
    * Tries to construct a ''StreamConfig'' object from the passed in components.
    *
    * @param triedDryRun       the dry-run component
    * @param triedTimeout      the timeout component
    * @param triedTimeDelta    the ignore file time delta component
    * @param triedOpsPerUnit   the ops per unit component
    * @param triedThrottleUnit the throttle unit component
    * @param triedModeConfig   the mode config component
    * @return a ''Try'' with the ''StreamConfig''
    */
  private def createStreamConfig(triedDryRun: Try[Boolean],
                                 triedTimeout: Try[Timeout],
                                 triedTimeDelta: Try[Option[IgnoreTimeDelta]],
                                 triedOpsPerUnit: Try[Option[Int]],
                                 triedThrottleUnit: Try[Throttle.TimeUnit],
                                 triedModeConfig: Try[StreamModeConfig]): Try[StreamConfig] =
    createRepresentation(triedDryRun, triedTimeout, triedTimeDelta, triedOpsPerUnit,
      triedThrottleUnit, triedModeConfig)(StreamConfig.apply)

  /**
    * Tries to construct a [[SyncStreamConfig]] object from the passed in
    * components.
    *
    * @param triedStatePath  the path to the local state component
    * @param triedStreamName the stream name component
    * @param triedImport     the import state component
    * @return a ''Try'' with the config of the sync stream
    */
  private def createSyncStreamConfig(triedStatePath: Try[Path],
                                     triedStreamName: Try[String],
                                     triedImport: Try[Boolean]): Try[SyncStreamConfig] =
    createRepresentation(triedStatePath, triedStreamName, triedImport)(SyncStreamConfig.apply)

  /**
    * Tries to construct a [[MirrorStreamConfig]] object from the passed in
    * components.
    *
    * @param triedSyncLogPath the sync log path component
    * @param triedSwitched    the switched component
    * @return a ''Try'' with the config of the mirror stream
    */
  private def createMirrorStreamConfig(triedSyncLogPath: Try[Option[Path]],
                                       triedSwitched: Try[Boolean]): Try[MirrorStreamConfig] =
    createRepresentation(triedSyncLogPath, triedSwitched)(MirrorStreamConfig.apply)
