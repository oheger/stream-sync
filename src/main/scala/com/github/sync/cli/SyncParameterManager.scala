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

package com.github.sync.cli

import java.nio.file.Path

import akka.util.Timeout
import com.github.sync.cli.ParameterManager.{CliProcessor, Parameters, SingleOptionValue}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
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
  /** Name of the option for the apply mode. */
  val ApplyModeOption: String = ParameterManager.OptionPrefix + "apply"

  /** Name of the option that defines a timeout for sync operations. */
  val TimeoutOption: String = ParameterManager.OptionPrefix + "timeout"

  /** Name of the option that defines the path to the log file. */
  val LogFileOption: String = ParameterManager.OptionPrefix + "log"

  /** Name of the option that defines the path to the sync log file. */
  val SyncLogOption: String = ParameterManager.OptionPrefix + "sync-log"

  /**
    * Name of the option that defines the threshold for time deltas to be
    * ignored. When comparing the timestamps of two files the files are
    * considered different only if the difference of their timestamps is
    * greater than this value (in seconds).
    */
  val IgnoreTimeDeltaOption: String = ParameterManager.OptionPrefix + "ignore-time-delta"

  /**
    * Name of the option that restricts the number of sync operations that can
    * be executed within a second. This is useful for instance when syncing to
    * a server that accepts only a limit number of requests per time unit.
    */
  val OpsPerSecondOption: String = ParameterManager.OptionPrefix + "ops-per-second"

  /**
    * Name of the option defining the encryption password for the source
    * structure. If defined, files from the source are decrypted using this
    * password when downloaded.
    */
  val SourcePasswordOption: String = ParameterManager.OptionPrefix + "src-encrypt-password"

  /**
    * Name of the option defining the encryption password for the destination
    * structure. If defined, files are encrypted using this password when they
    * are copied to the destination.
    */
  val DestPasswordOption: String = ParameterManager.OptionPrefix + "dst-encrypt-password"

  /**
    * Name of the option that determines te encryption mode for the source
    * structure. This also determines whether a password must be present.
    */
  val SourceCryptModeOption: String = ParameterManager.OptionPrefix + "src-crypt-mode"

  /**
    * Name of the option that determines the encryption mode for the
    * destination structure. This also determines whether a password must be
    * present.
    */
  val DestCryptModeOption: String = ParameterManager.OptionPrefix + "dst-crypt-mode"

  /**
    * Name of the option that defines the size of the cache for encrypted
    * names. This option is evaluated if file names are encrypted. In this
    * case, already encrypted or decrypted file names are stored in a cache, so
    * that they can be reused rather than having to compute them again.
    */
  val CryptCacheSizeOption: String = ParameterManager.OptionPrefix + "crypt-cache-size"

  /** The default timeout for sync operations. */
  val DefaultTimeout = Timeout(1.minute)

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
    val None = Val(requiresPassword = false)

    /** Crypt mode indicating that the content of files is encrypted. */
    val Files = Val()

    /**
      * Crypt mode indicating that both the content of files and the names of
      * folders and files are encrypted.
      */
    val FilesAndNames = Val()
  }

  /**
    * A class that holds the configuration options for a sync process.
    *
    * An instance of this class is created from the command line options passed
    * to the program.
    *
    * @param syncUris        the URIs to be synced (source and destination)
    * @param applyMode       the apply mode
    * @param timeout         a timeout for sync operations
    * @param logFilePath     an option with the path to the log file if defined
    * @param syncLogPath     an option with the path to a file containing sync
    *                        operations to be executed
    * @param ignoreTimeDelta optional threshold for a time difference between
    *                        two files that should be ignored
    * @param srcPassword     an option with the password for the sync source;
    *                        if set, files from the source are encrypted
    * @param srcCryptMode    the crypt mode for the source structure
    * @param dstPassword     an option with the password for the sync dest;
    *                        if set, files written to dest get encrypted
    * @param dstCryptMode    the crypt mode for the destination structure
    * @param cryptCacheSize  the size of the cache for encrypted names
    * @param opsPerSecond    optional restriction for the number of sync
    *                        operations per second
    */
  case class SyncConfig(syncUris: (String, String),
                        applyMode: ApplyMode,
                        timeout: Timeout,
                        logFilePath: Option[Path],
                        syncLogPath: Option[Path],
                        ignoreTimeDelta: Option[Int],
                        srcPassword: Option[String],
                        srcCryptMode: CryptMode.Value,
                        dstPassword: Option[String],
                        dstCryptMode: CryptMode.Value,
                        cryptCacheSize: Int,
                        opsPerSecond: Option[Int])

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
    * Returns a processor that extracts the ''SyncConfig'' from the command
    * line options.
    *
    * @return the processor to extract the ''SyncConfig''
    */
  def syncConfigProcessor(): CliProcessor[Try[SyncConfig]] = for {
    uris <- syncUrisProcessor()
    mode <- applyModeProcessor(uris.getOrElse(("", ""))._2)
    timeout <- timeoutProcessor()
    logFile <- ParameterManager.asPathOptionValue(LogFileOption, ParameterManager.singleOptionValue(LogFileOption))
    syncLog <- ParameterManager.asPathOptionValue(SyncLogOption, ParameterManager.singleOptionValue(SyncLogOption))
    timeDelta <- ignoreTimeDeltaProcessor()
    opsPerSec <- opsPerSecondProcessor()
    srcPwd <- cryptPasswordProcessor(SourceCryptModeOption, SourcePasswordOption)
    dstPwd <- cryptPasswordProcessor(DestCryptModeOption, DestPasswordOption)
    srcCrypt <- cryptModeProcessor(SourceCryptModeOption)
    dstCrypt <- cryptModeProcessor(DestCryptModeOption)
    cacheSize <- cryptCacheSizeProcessor()
  } yield createSyncConfig(uris, mode, timeout, logFile, syncLog, timeDelta, opsPerSec,
    srcPwd, srcCrypt, dstPwd, dstCrypt, cacheSize)

  /**
    * Extracts an object with configuration options for the sync process from
    * the map with command line arguments. This object contains all relevant
    * options. The options are validated (the future fails if invalid arguments
    * are detected). All options that have been consumed are removed from the
    * updated map; that way it can be found out whether the user has provided
    * unknown options.
    *
    * @param argsMap       the map with arguments
    * @param ec            the execution context
    * @param consoleReader the object for reading from the console
    * @return a future with the extracted config and the updated arguments map
    */
  def extractSyncConfig(argsMap: Parameters)(implicit ec: ExecutionContext, consoleReader: ConsoleReader):
  Future[(Parameters, SyncConfig)] =
    Future.fromTry(ParameterManager.tryProcessor(syncConfigProcessor(), argsMap).map(_.swap))

  /**
    * Constructs a ''SyncConfig'' object from the passed in components. If all
    * of the passed in components are valid, the corresponding config object is
    * created. Otherwise, all error messages are collected and returned in a
    * failed ''Try''.
    *
    * @param triedUris           the sync URIs component
    * @param triedApplyMode      the apply mode component
    * @param triedTimeout        the timeout component
    * @param triedLogFile        the log file component
    * @param triedSyncLog        the sync log component
    * @param triedTimeDelta      the ignore file time delta component
    * @param triedOpsPerSec      the ops per second component
    * @param triedSrcPassword    the source password component
    * @param triedSrcCryptMode   the source files crypt mode component
    * @param triedDstPassword    the destination password component
    * @param triedDstCryptMode   the destination crypt mode component
    * @param triedCryptCacheSize the crypt cache size component
    * @return a ''Try'' with the config
    */
  private def createSyncConfig(triedUris: Try[(String, String)],
                               triedApplyMode: Try[ApplyMode],
                               triedTimeout: Try[Timeout],
                               triedLogFile: Try[Option[Path]],
                               triedSyncLog: Try[Option[Path]],
                               triedTimeDelta: Try[Option[Int]],
                               triedOpsPerSec: Try[Option[Int]],
                               triedSrcPassword: Try[Option[String]],
                               triedSrcCryptMode: Try[CryptMode.Value],
                               triedDstPassword: Try[Option[String]],
                               triedDstCryptMode: Try[CryptMode.Value],
                               triedCryptCacheSize: Try[Int]): Try[SyncConfig] =
    ParameterManager.createRepresentation(triedUris, triedApplyMode, triedTimeout, triedLogFile,
      triedSyncLog, triedTimeDelta, triedOpsPerSec, triedSrcPassword, triedSrcCryptMode, triedDstPassword,
      triedDstCryptMode, triedCryptCacheSize) {
      SyncConfig(triedUris.get, triedApplyMode.get, triedTimeout.get,
        triedLogFile.get, triedSyncLog.get, triedTimeDelta.get, triedSrcPassword.get, triedSrcCryptMode.get,
        triedDstPassword.get, triedDstCryptMode.get, triedCryptCacheSize.get, triedOpsPerSec.get)
    }

  /**
    * Returns a processor that extracts the value of the option with the URIs
    * of the structures to be synced.
    *
    * @return the processor to extract the sync URIs
    */
  private def syncUrisProcessor(): CliProcessor[Try[(String, String)]] =
    ParameterManager.optionValue(ParameterManager.InputOption) map { values =>
      Try {
        values match {
          case uriDst :: uriSrc :: Nil =>
            (uriSrc, uriDst)
          case _ :: _ :: _ =>
            throw new IllegalArgumentException("Too many sync URIs specified!")
          case _ :: _ =>
            throw new IllegalArgumentException("Missing destination URI!")
          case _ =>
            throw new IllegalArgumentException("Missing URIs for source and destination!")
        }
      }
    }

  /**
    * Returns a processor that extracts the value of the option for the
    * apply mode.
    *
    * @param destUri the destination URI
    * @return the processor to extract the apply mode
    */
  private def applyModeProcessor(destUri: String): CliProcessor[Try[ApplyMode]] =
    ParameterManager.asMandatory(ApplyModeOption,
      ParameterManager.mapped(ApplyModeOption,
        ParameterManager.singleOptionValue(ApplyModeOption, Some("TARGET"))) {
        case RegApplyTargetUri(uri) =>
          ApplyModeTarget(uri)
        case RegApplyTargetDefault(_*) =>
          ApplyModeTarget(destUri)
        case RegApplyLog(_*) =>
          ApplyModeNone
        case s =>
          throw new IllegalArgumentException(s"Invalid apply mode: '$s'!")
      })

  /**
    * Returns a processor that extracts a crypt mode value from a command line
    * option.
    *
    * @param key the key of the option
    * @return the processor to extract the crypt mode
    */
  private def cryptModeProcessor(key: String): CliProcessor[Try[CryptMode.Value]] =
    ParameterManager.asMandatory(key,
      ParameterManager.mapped(key,
        ParameterManager.singleOptionValue(key, Some(CryptMode.None.toString()))) { name =>
        val mode = CryptMode.values.find(v => v.toString.equalsIgnoreCase(name))
        mode.fold[CryptMode.Value](throw new IllegalArgumentException(s"$key: Invalid crypt mode: '$name'!"))(m => m)
      })

  /**
    * Returns a processor that extracts the value of the option for ignoring
    * file time deltas. This processor is based on the processor for an
    * optional parameter, but the result has to be mapped to an integer.
    *
    * @return the processor for the ignore time delta option
    */
  private def ignoreTimeDeltaProcessor(): CliProcessor[Try[Option[Int]]] =
    ParameterManager.intOptionValue(IgnoreTimeDeltaOption)

  /**
    * Returns a processor that extracts he value of the option for the number
    * of sync operations per second.
    *
    * @return the processor for the ops per second option
    */
  private def opsPerSecondProcessor(): CliProcessor[Try[Option[Int]]] =
    ParameterManager.intOptionValue(OpsPerSecondOption)

  /**
    * Returns a processor that extracts the value of the option for the crypt
    * cache size. The string value is converted to an integer, and some
    * validation is performed.
    *
    * @return the processor for the crypt cache size
    */
  private def cryptCacheSizeProcessor(): CliProcessor[Try[Int]] =
    ParameterManager.asMandatory(CryptCacheSizeOption,
      ParameterManager.mapped(CryptCacheSizeOption,
        ParameterManager.intOptionValue(CryptCacheSizeOption, Some(DefaultCryptCacheSize))) { size =>
        if (size < MinCryptCacheSize)
          throw new IllegalArgumentException(s"Crypt cache size must be greater or equal $MinCryptCacheSize.")
        else size
      })

  /**
    * Returns a processor that obtains the encryption password for one of the
    * sync structures. The password is only obtained if required by the crypt
    * mode configured. If it is not specified in the command line options, it
    * is read from the console.
    *
    * @param keyCryptMode the option key for the crypt mode
    * @param keyPwd       the option key for the password
    * @return the processor to extract the encryption password
    */
  private def cryptPasswordProcessor(keyCryptMode: String, keyPwd: String):
  CliProcessor[SingleOptionValue[String]] = {
    val condProc = cryptModeProcessor(keyCryptMode).map(_.map(mode => mode.requiresPassword))
    val pwdProc = ParameterManager.withFallback(ParameterManager.optionValue(keyPwd),
      ParameterManager.consoleReaderValue(keyPwd, password = true))
    ParameterManager.asSingleOptionValue(keyPwd, ParameterManager.conditionalValue(condProc, pwdProc))
  }

  /**
    * Returns a processor that extracts the timeout from the command line.
    * This processor extracts an int value, which is interpreted as timeout in
    * seconds. A default timeout is set if the option is undefined.
    *
    * @return
    */
  private def timeoutProcessor(): CliProcessor[Try[Timeout]] =
    ParameterManager.asMandatory(TimeoutOption,
      ParameterManager.mapped(TimeoutOption,
        ParameterManager.intOptionValue(TimeoutOption,
          Some(DefaultTimeout.duration.toSeconds.toInt)))(_.seconds))

}
