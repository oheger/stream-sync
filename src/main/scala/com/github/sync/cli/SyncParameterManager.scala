/*
 * Copyright 2018-2020 The Developers Team.
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

import java.nio.file.Path
import java.util.Locale

import akka.util.Timeout
import com.github.sync.cli.FilterManager.SyncFilterData
import com.github.sync.cli.ParameterExtractor.{CliExtractor, SingleOptionValue}
import com.github.sync.cli.SyncStructureConfig.StructureConfig

import scala.concurrent.duration._
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
object SyncParameterManager {
  /** Name of the input option for the URI of the source structure. */
  final val SourceUriOption = "sourceURI"

  /** Help text for the source URI input parameter. */
  final val SourceUriHelp =
    """The URI defining the source structure of the sync process.
      |The URI can start with a prefix that determines the type of the structure. If no prefix \
      |is provided, it is interpreted as path to a file system (a local directory or a network \
      |share). The prefix "dav:" indicates a WebDav server. "onedrive:" points to a OneDrive server.
      |""".stripMargin

  /** Name of the input option for the URI of the destination structure. */
  final val DestinationUriOption = "destinationURI"

  /** Help text for the destination URI input parameter. */
  final val DestinationUriHelp =
    """The URI defining the destination structure of the sync process. This is analogous to the \
      |source URI, but determines where to apply the changes.
      |""".stripMargin

  /** Name of the option for the apply mode. */
  final val ApplyModeOption: String = "apply"

  /** Help text for the apply mode option. */
  final val ApplyModeHelp =
    """Determines how and where changes are applied. The value can be one of the following:
      |TARGET - Changes are applied to the target structure (the default)
      |TARGET:<uri> - Changes are applied to the structure defined by the given URI (which can be \
      |different from the destination URI); this only works for file system URIs
      |NONE - No changes are applied; this can be used as a dry run to check which changes would \
      |be applied
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
    * be executed within a second. This is useful for instance when syncing to
    * a server that accepts only a limit number of requests per time unit.
    */
  final val OpsPerSecondOption: String = "ops-per-second"

  final val OpsPerSecondHelp =
    """Allows limiting the number of sync operations executed per second. The option value is the \
      |number of operations that are allowed. This can be used to protected servers for too much \
      |load. Per default, there is no restriction for the operations that are executed.
      |""".stripMargin

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

  /** The default timeout for sync operations. */
  val DefaultTimeout: Timeout = Timeout(1.minute)

  /** The default size of the cache for encrypted file names. */
  val DefaultCryptCacheSize = 128

  /** The minimum size of the cache for encrypted file names. */
  val MinCryptCacheSize = 32

  /**
    * A trait representing the mode how sync operations are to be applied.
    *
    * Per default, sync operations are executed against the destination
    * structure. By specifying specific options, this can be changed, e.g. to
    * write operations only to a log file. Concrete sub classes represent such
    * special apply modes and contain additional data.
    */
  sealed trait ApplyMode

  /**
    * A concrete apply mode meaning that sync operations are applied against
    * a target structure.
    *
    * @param targetUri the URI of the target structure
    */
  case class ApplyModeTarget(targetUri: String) extends ApplyMode

  /**
    * A concrete apply mode meaning that sync operations are not to be
    * executed. This is useful for instance if only a log file is written.
    */
  case object ApplyModeNone extends ApplyMode

  /**
    * An enumeration defining the usage of encryption for a structure.
    *
    * With a value of this enumeration it is determined if and which parts of
    * a structure are encrypted.
    */
  object CryptMode extends Enumeration {

    protected case class Val(requiresPassword: Boolean = true) extends super.Val

    implicit def valueToCryptModeVal(x: Value): Val = x.asInstanceOf[Val]

    /** Crypt mode indicating that encryption is disabled. */
    val None: Val = Val(requiresPassword = false)

    /** Crypt mode indicating that the content of files is encrypted. */
    val Files: Val = Val()

    /**
      * Crypt mode indicating that both the content of files and the names of
      * folders and files are encrypted.
      */
    val FilesAndNames: Val = Val()

    /**
      * A map which allows retrieving an enum value from a string constant.
      * Strings are stored in upper case.
      */
    final val Literals: Map[String, CryptMode.Value] =
      values.map(v => (v.toString.toUpperCase(Locale.ROOT), v)).toMap
  }

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
    * A class that holds the configuration options for a sync process.
    *
    * An instance of this class is created from the command line options passed
    * to the program.
    *
    * @param srcUri          the source URI of the sync process
    * @param dstUri          the destination URI of the sync process
    * @param srcConfig       the config for the source structure
    * @param dstConfig       the config for the destination structure
    * @param applyMode       the apply mode
    * @param timeout         a timeout for sync operations
    * @param logFilePath     an option with the path to the log file if defined
    * @param syncLogPath     an option with the path to a file containing sync
    *                        operations to be executed
    * @param ignoreTimeDelta optional threshold for a time difference between
    *                        two files that should be ignored
    * @param cryptConfig     the configuration related to encryption
    * @param opsPerSecond    optional restriction for the number of sync
    *                        operations per second
    * @param filterData      an object with information about filters
    */
  case class SyncConfig(srcUri: String,
                        dstUri: String,
                        srcConfig: StructureConfig,
                        dstConfig: StructureConfig,
                        applyMode: ApplyMode,
                        timeout: Timeout,
                        logFilePath: Option[Path],
                        syncLogPath: Option[Path],
                        ignoreTimeDelta: Option[Int],
                        cryptConfig: CryptConfig,
                        opsPerSecond: Option[Int],
                        filterData: SyncFilterData)

  /** Prefix for regular expressions related to the target apply mode. */
  private val RegApplyTargetPrefix = "(?i)TARGET"

  /**
    * Regular expression for parsing the apply mode ''Target'' with a
    * destination URI.
    */
  private val RegApplyTargetUri = (RegApplyTargetPrefix + ":(.+)").r

  /**
    * Regular expression for parsing the apply mode ''Target'' without an URI.
    * In this case, the destination URI is used as target URI.
    */
  private val RegApplyTargetDefault = RegApplyTargetPrefix.r

  /**
    * Regular expression for parsing the apply mode ''None''.
    */
  private val RegApplyLog =
    """(?i)NONE""".r

  /**
    * Returns an extractor that extracts the ''SyncConfig'' from the command
    * line options.
    *
    * @return the extractor to extract the ''SyncConfig''
    */
  def syncConfigExtractor(): CliExtractor[Try[SyncConfig]] = for {
    srcUri <- srcUriExtractor()
    dstUri <- dstUriExtractor()
    srcConfig <- SyncStructureConfig.structureConfigExtractor(SyncStructureConfig.SourceRoleType, SourceUriOption)
    dstConfig <- SyncStructureConfig.structureConfigExtractor(SyncStructureConfig.DestinationRoleType,
      DestinationUriOption)
    mode <- applyModeExtractor(dstUri.getOrElse(""))
    timeout <- timeoutExtractor()
    logFile <- ParameterExtractor.optionValue(LogFileOption, Some(LogFileHelp)).toPath.single
    syncLog <- ParameterExtractor.optionValue(SyncLogOption, Some(SyncLogHelp)).toPath.single
    timeDelta <- ignoreTimeDeltaExtractor()
    opsPerSec <- opsPerSecondExtractor()
    cryptConf <- cryptConfigExtractor
    filters <- FilterManager.filterDataExtractor
  } yield createSyncConfig(srcUri, dstUri, srcConfig, dstConfig, mode, timeout, logFile, syncLog, timeDelta,
    opsPerSec, cryptConf, filters)

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
    * @param triedApplyMode   the apply mode component
    * @param triedTimeout     the timeout component
    * @param triedLogFile     the log file component
    * @param triedSyncLog     the sync log component
    * @param triedTimeDelta   the ignore file time delta component
    * @param triedOpsPerSec   the ops per second component
    * @param triedCryptConfig the component with the crypt config
    * @param triedFilterData  the component with the filter data
    * @return a ''Try'' with the config
    */
  private def createSyncConfig(triedSrcUri: Try[String],
                               triedDstUri: Try[String],
                               triedSrcConfig: Try[StructureConfig],
                               triedDstConfig: Try[StructureConfig],
                               triedApplyMode: Try[ApplyMode],
                               triedTimeout: Try[Timeout],
                               triedLogFile: Try[Option[Path]],
                               triedSyncLog: Try[Option[Path]],
                               triedTimeDelta: Try[Option[Int]],
                               triedOpsPerSec: Try[Option[Int]],
                               triedCryptConfig: Try[CryptConfig],
                               triedFilterData: Try[SyncFilterData]): Try[SyncConfig] =
    ParameterExtractor.createRepresentation(triedSrcUri, triedDstUri, triedSrcConfig, triedDstConfig,
      triedApplyMode, triedTimeout, triedLogFile, triedSyncLog, triedTimeDelta, triedCryptConfig,
      triedOpsPerSec, triedFilterData)(SyncConfig)

  /**
    * Returns an extractor that extracts the source URI from the first input
    * parameter.
    *
    * @return the extractor for the source URI
    */
  private def srcUriExtractor(): CliExtractor[Try[String]] =
    ParameterExtractor.inputValue(0, Some(SourceUriOption), Some(SourceUriHelp))
      .multiplicity(atLeast = 1, atMost = 1)
      .single
      .mandatory

  /**
    * Returns an extractor that extracts the destination URI from the 2nd input
    * parameter.
    *
    * @return the extractor for the destination URI
    */
  private def dstUriExtractor(): CliExtractor[Try[String]] =
    ParameterExtractor.inputValue(1, Some(DestinationUriOption), Some(DestinationUriHelp), last = true)
      .multiplicity(atLeast = 1, atMost = 1)
      .single
      .mandatory

  /**
    * Returns an extractor that extracts the value of the option for the
    * apply mode.
    *
    * @param destUri the destination URI
    * @return the extractor to extract the apply mode
    */
  private def applyModeExtractor(destUri: String): CliExtractor[Try[ApplyMode]] =
    ParameterExtractor.optionValue(ApplyModeOption, Some(ApplyModeHelp))
      .mapTo[ApplyMode] {
        case RegApplyTargetUri(uri) =>
          ApplyModeTarget(uri)
        case RegApplyTargetDefault(_*) =>
          ApplyModeTarget(destUri)
        case RegApplyLog(_*) =>
          ApplyModeNone
        case s =>
          throw new IllegalArgumentException(s"Invalid apply mode: '$s'!")
      }.fallbackValuesWithDesc(Some("TARGET"), ApplyModeTarget(destUri))
      .single
      .mandatory

  /**
    * Returns an extractor that extracts a crypt mode value from a command line
    * option.
    *
    * @param key the key of the option
    * @return the extractor to extract the crypt mode
    */
  private def cryptModeExtractor(key: String): CliExtractor[Try[CryptMode.Value]] =
    ParameterExtractor.optionValue(key, Some(CryptModeHelp))
      .toUpper
      .toEnum(CryptMode.Literals.get)
      .fallbackValues(CryptMode.None.asInstanceOf[CryptMode.Value])
      .single
      .mandatory

  /**
    * Returns an extractor that extracts the value of the option for ignoring
    * file time deltas. This extractor is based on the extractor for an
    * optional parameter, but the result has to be mapped to an integer.
    *
    * @return the extractor for the ignore time delta option
    */
  private def ignoreTimeDeltaExtractor(): CliExtractor[Try[Option[Int]]] =
    ParameterExtractor.optionValue(IgnoreTimeDeltaOption, Some(IgnoreTimeDeltaHelp))
      .toInt
      .single

  /**
    * Returns an extractor that extracts he value of the option for the number
    * of sync operations per second.
    *
    * @return the extractor for the ops per second option
    */
  private def opsPerSecondExtractor(): CliExtractor[Try[Option[Int]]] =
    ParameterExtractor.optionValue(OpsPerSecondOption, Some(OpsPerSecondHelp))
      .toInt
      .single

  /**
    * Returns an extractor that extracts the parameters related to cryptography.
    *
    * @return the extractor for the ''CryptConfig''
    */
  private def cryptConfigExtractor: CliExtractor[Try[CryptConfig]] =
    for {
      srcPwd <- cryptPasswordExtractor(SourceCryptModeOption, SourcePasswordOption)
      dstPwd <- cryptPasswordExtractor(DestCryptModeOption, DestPasswordOption)
      srcCrypt <- cryptModeExtractor(SourceCryptModeOption)
      dstCrypt <- cryptModeExtractor(DestCryptModeOption)
      cacheSize <- cryptCacheSizeExtractor()
    } yield createCryptConfig(srcPwd, srcCrypt, dstPwd, dstCrypt, cacheSize)

  /**
    * Returns an extractor that extracts the value of the option for the crypt
    * cache size. The string value is converted to an integer, and some
    * validation is performed.
    *
    * @return the extractor for the crypt cache size
    */
  private def cryptCacheSizeExtractor(): CliExtractor[Try[Int]] =
    ParameterExtractor.optionValue(CryptCacheSizeOption, Some(CryptCacheSizeHelp))
      .toInt
      .mapTo { size =>
        if (size < MinCryptCacheSize)
          throw new IllegalArgumentException(s"Crypt cache size must be greater or equal $MinCryptCacheSize.")
        else size
      }.fallbackValues(DefaultCryptCacheSize)
      .single
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
  CliExtractor[SingleOptionValue[String]] = {
    val condExt = cryptModeExtractor(keyCryptMode).map(_.map(mode => mode.requiresPassword))
    val pwdExt = ParameterExtractor.optionValue(keyPwd, Some(CryptPasswordHelp))
      .fallback(ParameterExtractor.consoleReaderValue(keyPwd, password = true))
    ParameterExtractor.conditionalValue(condExt, pwdExt).single
  }

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
    ParameterExtractor.createRepresentation(triedSrcPwd, triedSrcCryptMode, triedDstPwd, triedDstCryptMode,
      triedCryptCacheSize)(CryptConfig)

  /**
    * Returns an extractor that extracts the timeout from the command line.
    * This extractor extracts an int value, which is interpreted as timeout in
    * seconds. A default timeout is set if the option is undefined.
    *
    * @return the extractor to extract the timeout value
    */
  private def timeoutExtractor(): CliExtractor[Try[Timeout]] =
    ParameterExtractor.optionValue(TimeoutOption, Some(TimeoutHelp))
      .toInt
      .mapTo(time => Timeout(time.seconds))
      .fallbackValues(DefaultTimeout)
      .single
      .mandatory
}
