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
import com.github.scli.ParameterExtractor.*
import com.github.sync.cli.FilterManager.SyncFilterData
import com.github.sync.cli.SyncCliStructureConfig.StructureAuthConfig
import com.github.sync.stream.{IgnoreTimeDelta, Throttle}
import org.apache.logging.log4j.Level

import java.nio.file.Path
import java.util.Locale
import scala.concurrent.duration.*
import scala.language.implicitConversions
import scala.util.Try

/**
  * A service responsible for parsing command line arguments.
  *
  * This service converts the sequence of command line arguments to a map
  * keyed by known option names. The values are lists with the strings assigned
  * to these options. (Options are allowed to be repeated in the command line
  * and thus can have multiple values; hence their values are represented as
  * lists.) Case does not matter for options; they are always converted to
  * lower case.
  *
  * To specify the source and the destination of a sync process, no options are
  * used. All parameters not assigned to options are grouped under a reserved
  * option key.
  */
object SyncParameterManager:
  /** Name of the input option for the URI of the source structure. */
  final val SourceUriOption = "sourceURI"

  /** Help text for the source URI input parameter. */
  final val SourceUriHelp =
    """The URI defining the source structure of the sync process.
      |The URI can start with a prefix that determines the type of the structure. If no prefix \
      |is provided, it is interpreted as path to a file system (a local directory or a network \
      |share). The prefix "dav:" indicates a WebDav server; "onedrive:" points to a OneDrive server; \
      |"googledrive:" refers to a GoogleDrive server.
      |""".stripMargin

  /** Name of the input option for the URI of the destination structure. */
  final val DestinationUriOption = "destinationURI"

  /** Help text for the destination URI input parameter. */
  final val DestinationUriHelp =
    """The URI defining the destination structure of the sync process. This is analogous to the \
      |source URI, but determines where to apply the changes.
      |""".stripMargin

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

  /** Name of the option that defines the path to the log file. */
  final val LogFileOption: String = "log"

  /** Help text for the log file option. */
  final val LogFileHelp =
    """Defines the path to a log file for the sync operations that are executed. If this option is \
      |not provided, no log file is written.
      |""".stripMargin

  /** Name of the option that defines the path to the error log file. */
  final val ErrorLogFileOption: String = "error-log"

  /** Help text for the error log file option. */
  final val ErrorLogFileHelp =
    """Defines the path to a log file where failed sync operations (together with the corresponding \
      |exceptions are logged. If this option is not provided, no error log file is written.""".stripMargin

  /** Name of the option that defines the path to the sync log file. */
  final val SyncLogOption: String = "sync-log"

  /** Help text for the sync log option. */
  final val SyncLogHelp =
    """Defines the path from where to read the sync log. If specified, the sync process does not \
      |address the differences between the source and the destination structure, but executes the \
      |operations defined in the sync log.
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

  /**
    * Name of the option defining the encryption password for the source
    * structure. If defined, files from the source are decrypted using this
    * password when downloaded.
    */
  final val SourcePasswordOption: String = "src-encrypt-password"

  /**
    * Name of the option defining the encryption password for the destination
    * structure. If defined, files are encrypted using this password when they
    * are copied to the destination.
    */
  final val DestPasswordOption: String = "dst-encrypt-password"

  /** Help text for the crypt password option. */
  final val CryptPasswordHelp =
    """Sets the encryption password for this structure. A password is needed if some sort of \
      |encryption is enabled. It can be provided either via this option or it is read from the \
      |console.
      |""".stripMargin

  /**
    * Name of the option that determines te encryption mode for the source
    * structure. This also determines whether a password must be present.
    */
  final val SourceCryptModeOption: String = "src-crypt-mode"

  /**
    * Name of the option that determines the encryption mode for the
    * destination structure. This also determines whether a password must be
    * present.
    */
  final val DestCryptModeOption: String = "dst-crypt-mode"

  /** Help text for the crypt mode option. */
  final val CryptModeHelp =
    """Determines how encryption is handled for this structure. Possible values are 'NONE' \
      |(encryption is disabled), 'FILES' (the content of files is encrypted), or \
      |'FILESANDNAMES' (both the content of files and the file names are encrypted). If \
      |some sort of encryption is enabled, a password must be provided, either as an \
      |additional command line option, or it is read from the console.
      |""".stripMargin

  /**
    * Name of the option that defines the size of the cache for encrypted
    * names. This option is evaluated if file names are encrypted. In this
    * case, already encrypted or decrypted file names are stored in a cache, so
    * that they can be reused rather than having to compute them again.
    */
  final val CryptCacheSizeOption: String = "crypt-cache-size"

  /** Help text for the crypt cache size option. */
  final val CryptCacheSizeHelp =
    """Defines the size of the cache for encrypted file names. If encryption of file names is \
      |enabled, this option allows setting the size of a cache for names that have been \
      |encrypted; this can reduce the number of encrypt operations.
      |""".stripMargin

  /**
    * Name of the option that switches the source and destination structures.
    * This offers an easy means to let the sync happen in the opposite
    * direction.
    */
  final val SwitchOption = "switch"

  /** The name of the switch that enables debug logging. */
  final val LogLevelDebug = "debug"

  /** The help text for the debug log level switch. */
  final val LogLevelDebugHelp =
    """Sets the log level to DEBUG. This is the most verbose log level. This can be used for instance to find \
      |out in which attributes files differ, so that an override action is triggered.""".stripMargin

  /** The name of the switch that enables info logging. */
  final val LogLevelInfo = "info"

  /** The help text for the info log level switch. */
  final val LogLevelInfoHelp =
    """Sets the log level to INFO. This log level produces more output than the default log level WARN. For instance \
      |information is printed about the folders that are currently processed and the sync actions that are \
      |performed.""".stripMargin

  /** The name of the switch that enables warn logging. */
  final val LogLevelWarn = "warn"

  /** The help text for the warn log level switch. */
  final val LogLevelWarnHelp =
    """Sets the log level to WARN. This is the default log level. Output is only generated if problems occur during \
      |the sync process.""".stripMargin

  /** The name of the switch that enables error logging. */
  final val LogLevelError = "error"

  /** The help text for the error log level switch. */
  final val LogLevelErrorHelp =
    """Sets the log level to ERROR. This is the quietest log level. Output is only generated for fatal errors during \
      |the sync process.""".stripMargin

  /**
    * A map with the names of the supported log levels and their mapping to
    * the corresponding value in log4j. This is also used as the conversion
    * function to the log level option.
    */
  final val LogLevels = Map("debug" -> Level.DEBUG, "info" -> Level.INFO, "warn" -> Level.WARN,
    "error" -> Level.ERROR)

  /** The name of the switch to request help explicitly. */
  final val HelpOption = "help"

  /** Help text for the switch option. */
  final val SwitchOptionHelp =
    """If this flag is provided, the source and destination structures are switched, so that the sync \
      |process basically runs in the opposite direction. This is useful if you occasionally need to \
      |fetch data from the destination; then you do not have to write another sync command, but just \
      |add this flag to the existing one and revert the sync direction.""".stripMargin

  /** The short alias for the help option. */
  final val HelpAlias = "h"

  /** The default timeout for sync operations. */
  final val DefaultTimeout: Timeout = Timeout(1.minute)

  /** The default size of the cache for encrypted file names. */
  final val DefaultCryptCacheSize = 128

  /** The minimum size of the cache for encrypted file names. */
  final val MinCryptCacheSize = 32

  /**
    * A map assigning the supported names for time units to the corresponding
    * objects.
    */
  private final val TimeUnits = Map("h" -> Throttle.TimeUnit.Hour, "hour" -> Throttle.TimeUnit.Hour,
    "m" -> Throttle.TimeUnit.Minute, "minute" -> Throttle.TimeUnit.Minute,
    "s" -> Throttle.TimeUnit.Second, "second" -> Throttle.TimeUnit.Second)

  /**
    * An enumeration defining the usage of encryption for a structure.
    *
    * With a value of this enumeration it is determined if and which parts of
    * a structure are encrypted.
    */
  object CryptMode extends Enumeration :

    protected case class CryptModeVal(requiresPassword: Boolean = true) extends super.Val

    implicit def valueToCryptModeVal(x: Value): CryptModeVal = x.asInstanceOf[CryptModeVal]

    /** Crypt mode indicating that encryption is disabled. */
    val None: CryptModeVal = CryptModeVal(requiresPassword = false)

    /** Crypt mode indicating that the content of files is encrypted. */
    val Files: CryptModeVal = CryptModeVal()

    /**
      * Crypt mode indicating that both the content of files and the names of
      * folders and files are encrypted.
      */
    val FilesAndNames: CryptModeVal = CryptModeVal()

    /**
      * A map which allows retrieving an enum value from a string constant.
      * Strings are stored in upper case.
      */
    final val Literals: Map[String, CryptMode.Value] =
      values.map(v => (v.toString.toUpperCase(Locale.ROOT), v)).toMap

  /**
    * A class that combines the properties related to encryption during a sync
    * process.
    *
    * @param srcPassword    an option with the password for the sync source;
    *                       if set, files from the source are encrypted
    * @param srcCryptMode   the crypt mode for the source structure
    * @param dstPassword    an option with the password for the sync dest;
    *                       if set, files written to dest get encrypted
    * @param dstCryptMode   the crypt mode for the destination structure
    * @param cryptCacheSize the size of the cache for encrypted names
    */
  case class CryptConfig(srcPassword: Option[String],
                         srcCryptMode: CryptMode.Value,
                         dstPassword: Option[String],
                         dstCryptMode: CryptMode.Value,
                         cryptCacheSize: Int)

  /**
    * A configuration class that combines the properties related to logging.
    *
    * @param logFilePath      an option with the path to the log file if defined
    * @param errorLogFilePath an option with the path to an error log file
    * @param syncLogPath      an option with the path to a file containing sync
    *                         operations to be executed
    * @param logLevel         the log level for the sync process
    */
  case class LogConfig(logFilePath: Option[Path],
                       errorLogFilePath: Option[Path],
                       syncLogPath: Option[Path],
                       logLevel: Level)

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
    * A class that holds the configuration options for a sync process.
    *
    * An instance of this class is created from the command line options passed
    * to the program.
    *
    * @param srcUri       the source URI of the sync process
    * @param dstUri       the destination URI of the sync process
    * @param srcConfig    the config for the source structure
    * @param dstConfig    the config for the destination structure
    * @param logConfig    the configuration related to logging
    * @param cryptConfig  the configuration related to encryption
    * @param streamConfig the configuration related to stream parameters
    * @param filterData   an object with information about filters
    * @param switched     a flag whether src and dst configs should be
    *                     switched
    */
  case class SyncConfig(srcUri: String,
                        dstUri: String,
                        srcConfig: StructureAuthConfig,
                        dstConfig: StructureAuthConfig,
                        logConfig: LogConfig,
                        cryptConfig: CryptConfig,
                        streamConfig: StreamConfig,
                        filterData: SyncFilterData,
                        switched: Boolean):
    /**
      * Returns a normalized ''SyncConfig'' for this instance. If the
      * ''switched'' flag is '''false''', the normalized instance is the same
      * as this instance; otherwise, the source and destination URIs and
      * configurations have to be switched.
      *
      * @return the normalized ''SyncConfig''
      */
    def normalized: SyncConfig =
      if switched then
        copy(srcUri = dstUri, dstUri = srcUri, srcConfig = dstConfig, dstConfig = srcConfig,
          cryptConfig = switchCryptConfig(cryptConfig), switched = false)
      else this

  /**
    * Returns an extractor that extracts the ''SyncConfig'' from the command
    * line options.
    *
    * @return the extractor to extract the ''SyncConfig''
    */
  def syncConfigExtractor(): CliExtractor[Try[SyncConfig]] = for
    srcUri <- srcUriExtractor()
    dstUri <- dstUriExtractor()
    srcConfig <- SyncCliStructureConfig.structureConfigExtractor(SyncCliStructureConfig.SourceRoleType, SourceUriOption)
    dstConfig <- SyncCliStructureConfig.structureConfigExtractor(SyncCliStructureConfig.DestinationRoleType,
      DestinationUriOption)
    logConfig <- logConfigExtractor
    cryptConf <- cryptConfigExtractor
    streamConf <- streamConfigExtractor
    filters <- FilterManager.filterDataExtractor
    switched <- switchValue(SwitchOption, optHelp = Some(SwitchOptionHelp)).alias("S")
    _ <- CliActorSystemLifeCycle.FileExtractor
  yield createSyncConfig(srcUri, dstUri, srcConfig, dstConfig, logConfig, cryptConf, streamConf,
    filters, switched) map (_.normalized)

  /**
    * Constructs a ''SyncConfig'' object from the passed in components. If all
    * of the passed in components are valid, the corresponding config object is
    * created. Otherwise, all error messages are collected and returned in a
    * failed ''Try''.
    *
    * @param triedSrcUri      the source URI component
    * @param triedDstUri      the dest URI component
    * @param triedSrcConfig   the source structure config component
    * @param triedDstConfig   the destination structure config component
    * @param triedLogConfig   the configuration related to logging
    * @param triedCryptConfig the component with the crypt config
    * @param triedFilterData  the component with the filter data
    * @param triedSwitch      the component for the switch flag
    * @return a ''Try'' with the config
    */
  private def createSyncConfig(triedSrcUri: Try[String],
                               triedDstUri: Try[String],
                               triedSrcConfig: Try[StructureAuthConfig],
                               triedDstConfig: Try[StructureAuthConfig],
                               triedLogConfig: Try[LogConfig],
                               triedCryptConfig: Try[CryptConfig],
                               triedStreamConfig: Try[StreamConfig],
                               triedFilterData: Try[SyncFilterData],
                               triedSwitch: Try[Boolean]): Try[SyncConfig] =
    createRepresentation(triedSrcUri, triedDstUri, triedSrcConfig, triedDstConfig,
      triedLogConfig, triedCryptConfig, triedStreamConfig, triedFilterData, triedSwitch)(SyncConfig.apply)

  /**
    * Returns an extractor that extracts the source URI from the first input
    * parameter.
    *
    * @return the extractor for the source URI
    */
  private def srcUriExtractor(): CliExtractor[Try[String]] =
    inputValue(0, Some(SourceUriOption), Some(SourceUriHelp))
      .mandatory

  /**
    * Returns an extractor that extracts the destination URI from the 2nd input
    * parameter.
    *
    * @return the extractor for the destination URI
    */
  private def dstUriExtractor(): CliExtractor[Try[String]] =
    inputValue(1, Some(DestinationUriOption), Some(DestinationUriHelp), last = true)
      .mandatory

  /**
    * Returns an extractor that extracts a crypt mode value from a command line
    * option.
    *
    * @param key the key of the option
    * @return the extractor to extract the crypt mode
    */
  private def cryptModeExtractor(key: String): CliExtractor[Try[CryptMode.Value]] =
    optionValue(key, Some(CryptModeHelp))
      .toUpper
      .toEnum(CryptMode.Literals.get)
      .fallbackValue(CryptMode.None.asInstanceOf[CryptMode.Value])
      .mandatory

  /**
    * Returns an extractor that extracts the parameters related to cryptography.
    *
    * @return the extractor for the ''CryptConfig''
    */
  private def cryptConfigExtractor: CliExtractor[Try[CryptConfig]] =
    for
      srcPwd <- cryptPasswordExtractor(SourceCryptModeOption, SourcePasswordOption)
      dstPwd <- cryptPasswordExtractor(DestCryptModeOption, DestPasswordOption)
      srcCrypt <- cryptModeExtractor(SourceCryptModeOption)
      dstCrypt <- cryptModeExtractor(DestCryptModeOption)
      cacheSize <- cryptCacheSizeExtractor()
    yield createCryptConfig(srcPwd, srcCrypt, dstPwd, dstCrypt, cacheSize)

  /**
    * Returns an extractor that extracts the value of the option for the crypt
    * cache size. The string value is converted to an integer, and some
    * validation is performed.
    *
    * @return the extractor for the crypt cache size
    */
  private def cryptCacheSizeExtractor(): CliExtractor[Try[Int]] =
    optionValue(CryptCacheSizeOption, Some(CryptCacheSizeHelp))
      .toInt
      .mapTo { size =>
        if size < MinCryptCacheSize then
          throw new IllegalArgumentException(s"Crypt cache size must be greater or equal $MinCryptCacheSize.")
        else size
      }.fallbackValue(DefaultCryptCacheSize)
      .mandatory

  /**
    * Returns an extractor that obtains the encryption password for one of the
    * sync structures. The password is only obtained if required by the crypt
    * mode configured. If it is not specified in the command line options, it
    * is read from the console.
    *
    * @param keyCryptMode the option key for the crypt mode
    * @param keyPwd       the option key for the password
    * @return the extractor to extract the encryption password
    */
  private def cryptPasswordExtractor(keyCryptMode: String, keyPwd: String):
  CliExtractor[SingleOptionValue[String]] =
    val condExt = cryptModeExtractor(keyCryptMode).map(_.map(mode => mode.requiresPassword))
    val pwdExt = optionValue(keyPwd, Some(CryptPasswordHelp))
      .fallback(consoleReaderValue(keyPwd, password = true))
    val elseExt = constantExtractor(Try(Option[String](null)))
    conditionalValue(condExt, pwdExt, elseExt)

  /**
    * Tries to create a ''CryptConfig'' object from the given components.
    *
    * @param triedSrcPwd         the source password component
    * @param triedSrcCryptMode   the source crypt mode component
    * @param triedDstPwd         the destination password component
    * @param triedDstCryptMode   the destination crypt mode component
    * @param triedCryptCacheSize the crypt cache size component
    * @return a ''Try'' with the ''CryptConfig''
    */
  private def createCryptConfig(triedSrcPwd: Try[Option[String]], triedSrcCryptMode: Try[CryptMode.Value],
                                triedDstPwd: Try[Option[String]], triedDstCryptMode: Try[CryptMode.Value],
                                triedCryptCacheSize: Try[Int]): Try[CryptConfig] =
    createRepresentation(triedSrcPwd, triedSrcCryptMode, triedDstPwd, triedDstCryptMode,
      triedCryptCacheSize)(CryptConfig.apply)

  /**
    * Returns an extractor that that extracts the parameters related to
    * logging.
    *
    * @return the extractor for the log configuration
    */
  private def logConfigExtractor: CliExtractor[Try[LogConfig]] =
    for
      logFile <- optionValue(LogFileOption, Some(LogFileHelp)).alias("l").toPath
      errLog <- optionValue(ErrorLogFileOption, Some(ErrorLogFileHelp)).toPath
      syncLog <- optionValue(SyncLogOption, Some(SyncLogHelp)).toPath
      logLevel <- logLevelExtractor()
    yield createLogConfig(logFile, errLog, syncLog, logLevel)

  /**
    * Returns an extractor for the log level option. The log level can be
    * specified using different switches, like "--info" or "--error". If
    * multiple switches of this group are provided, the last one wins. If there
    * is no switch, default is the level WARN.
    *
    * @return the extractor for the log level
    */
  private def logLevelExtractor(): CliExtractor[Try[Level]] =
    val switchDebug = switchValue(LogLevelDebug, optHelp = Some(LogLevelDebugHelp))
    val switchInfo = switchValue(LogLevelInfo, optHelp = Some(LogLevelInfoHelp))
    val switchWarn = switchValue(LogLevelWarn, optHelp = Some(LogLevelWarnHelp))
    val switchError = switchValue(LogLevelError, optHelp = Some(LogLevelErrorHelp))

    excludingSwitches(allowOverride = true, switchDebug, switchInfo, switchWarn, switchError)
      .toEnum(LogLevels.get)
      .fallbackValue(Level.WARN)
      .mandatory

  /**
    * Tries to create a ''LogConfig'' from the given components
    *
    * @param triedLogFile  the log file component
    * @param triedErrorLog the error log file component
    * @param triedSyncLog  the sync log component
    * @param triedLogLevel the component for the log level
    * @return a ''Try'' with the ''LogConfig''
    */
  private def createLogConfig(triedLogFile: Try[Option[Path]],
                              triedErrorLog: Try[Option[Path]],
                              triedSyncLog: Try[Option[Path]],
                              triedLogLevel: Try[Level]): Try[LogConfig] =
    createRepresentation(triedLogFile, triedErrorLog, triedSyncLog, triedLogLevel)(LogConfig.apply)

  /**
    * Returns an extractor that extracts the parameters related to the stream.
    *
    * @return the extractor for the ''StreamConfig''
    */
  private def streamConfigExtractor: CliExtractor[Try[StreamConfig]] =
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

  /**
    * Switches the fields related to source and destination structures in the
    * ''CryptConfig'' provided. This is used for the implementation of the
    * ''--switch'' parameter.
    *
    * @param config the ''CryptConfig'' to be switched
    * @return the resulting ''CryptConfig''
    */
  private def switchCryptConfig(config: CryptConfig): CryptConfig =
    config.copy(srcCryptMode = config.dstCryptMode, dstCryptMode = config.srcCryptMode,
      srcPassword = config.dstPassword, dstPassword = config.srcPassword)
