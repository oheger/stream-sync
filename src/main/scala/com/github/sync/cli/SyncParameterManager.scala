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

import java.nio.file.{Path, Paths}

import akka.util.Timeout
import com.github.sync.SyncTypes.SupportedArgument
import com.github.sync.cli.ParameterManager.{CliProcessor, Parameters}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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
    * Name of the option that determines that the file names in the source
    * structure are encrypted. This is evaluated only if a source password is
    * set.
    */
  val EncryptSourceFileNamesOption: String = ParameterManager.OptionPrefix + "src-encrypt-names"

  /**
    * Name of the option that determines that the file names in the destination
    * structure are encrypted. This is evaluated only if a destination password
    * is set.
    */
  val EncryptDestFileNamesOption: String = ParameterManager.OptionPrefix + "dst-encrypt-names"

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
    * A class that holds the configuration options for a sync process.
    *
    * An instance of this class is created from the command line options passed
    * to the program.
    *
    * @param syncUris              the URIs to be synced (source and destination)
    * @param applyMode             the apply mode
    * @param timeout               a timeout for sync operations
    * @param logFilePath           an option with the path to the log file if defined
    * @param syncLogPath           an option with the path to a file containing sync
    *                              operations to be executed
    * @param ignoreTimeDelta       optional threshold for a time difference between
    *                              two files that should be ignored
    * @param srcPassword           an option with the password for the sync source;
    *                              if set, files from the source are encrypted
    * @param srcFileNamesEncrypted flag whether file names in the source are
    *                              encrypted
    * @param dstPassword           an option with the password for the sync dest;
    *                              if set, files written to dest get encrypted
    * @param dstFileNamesEncrypted flag whether file names in the destination
    *                              should be encrypted
    * @param cryptCacheSize        the size of the cache for encrypted names
    * @param opsPerSecond          optional restriction for the number of sync
    *                              operations per second
    */
  case class SyncConfig(syncUris: (String, String),
                        applyMode: ApplyMode,
                        timeout: Timeout,
                        logFilePath: Option[Path],
                        syncLogPath: Option[Path],
                        ignoreTimeDelta: Option[Int],
                        srcPassword: Option[String],
                        srcFileNamesEncrypted: Boolean,
                        dstPassword: Option[String],
                        dstFileNamesEncrypted: Boolean,
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
    * Returns a processor that extracts the value of the option with the URIs
    * of the structures to be synced.
    *
    * @return the processor to extract the sync URIs
    */
  def syncUrisProcessor(): CliProcessor[Try[(String, String)]] =
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
  def applyModeProcessor(destUri: String): CliProcessor[Try[ApplyMode]] =
    ParameterManager.singleOptionValue(ApplyModeOption, Some("TARGET")) map (_.map {
      case RegApplyTargetUri(uri) =>
        ApplyModeTarget(uri)
      case RegApplyTargetDefault(_*) =>
        ApplyModeTarget(destUri)
      case RegApplyLog(_*) =>
        ApplyModeNone
      case s =>
        throw new IllegalArgumentException(s"Invalid apply mode: '$s'!")
    }
      )

  /**
    * Returns a processor that extracts the ''SyncConfig'' from the command
    * line options.
    *
    * @return the processor to extract the ''SyncConfig''
    */
  def syncConfigProcessor(): CliProcessor[Try[SyncConfig]] = for {
    uris <- syncUrisProcessor()
    mode <- applyModeProcessor(uris.getOrElse(("", ""))._2)
    timeout <- ParameterManager.singleOptionValueMapped(TimeoutOption,
      Some(DefaultTimeout.duration.toSeconds.toString))(mapTimeout)
    logFile <- ParameterManager.optionalOptionValue(LogFileOption)
    syncLog <- ParameterManager.optionalOptionValue(SyncLogOption)
    timeDelta <- ignoreTimeDeltaOption()
    opsPerSec <- opsPerSecondOption()
    srcPwd <- ParameterManager.optionalOptionValue(SourcePasswordOption)
    dstPwd <- ParameterManager.optionalOptionValue(DestPasswordOption)
    srcEncFiles <- ParameterManager.booleanOptionValue(EncryptSourceFileNamesOption)
    dstEncFiles <- ParameterManager.booleanOptionValue(EncryptDestFileNamesOption)
    cacheSize <- cryptCacheSizeOption()
  } yield createSyncConfig(uris, mode, timeout, logFile, syncLog, timeDelta, opsPerSec,
    srcPwd, srcEncFiles, dstPwd, dstEncFiles, cacheSize)

  /**
    * Extracts an object with configuration options for the sync process from
    * the map with command line arguments. This object contains all relevant
    * options. The options are validated (the future fails if invalid arguments
    * are detected). All options that have been consumed are removed from the
    * updated map; that way it can be found out whether the user has provided
    * unknown options.
    *
    * @param argsMap the map with arguments
    * @param ec      the execution context
    * @return a future with the extracted config and the updated arguments map
    */
  def extractSyncConfig(argsMap: Parameters)(implicit ec: ExecutionContext):
  Future[(Parameters, SyncConfig)] = Future {
    val (triedConfig, map) = syncConfigProcessor().run(argsMap)
    (map, triedConfig.get)
  }

  /**
    * Extracts the values of additional supported parameters from the given map
    * with command line arguments. This method is used to obtain the argument
    * options required to access the source and destination structures. (These
    * options are specific to the concrete structures to be synced.) Based on
    * the description objects for supported arguments, the values are extracted
    * from the map with arguments (and removed in the returned map). Result is
    * a map with the argument keys and their extracted values.
    *
    * @param argsMap the map with arguments
    * @param args    the arguments to be extracted
    * @param ec      the execution context
    * @return a future with the extracted arguments and the updated arguments
    *         map
    */
  def extractSupportedArguments(argsMap: Parameters,
                                args: Iterable[SupportedArgument])
                               (implicit ec: ExecutionContext):
  Future[(Parameters, Map[String, String])] = Future {
    def handleTriedResult[T](argsMap: Parameters,
                             extrArgs: Map[String, String], proc: CliProcessor[Try[T]],
                             errors: List[String])
                            (h: (Map[String, String], T) => Map[String, String]):
    (Parameters, Map[String, String], List[String]) = {
      val (res, updArgs) = proc.run(argsMap)
      res match {
        case Success(value) =>
          (updArgs, h(extrArgs, value), errors)
        case Failure(exception) =>
          (updArgs, Map.empty, exception.getMessage :: errors)
      }
    }

    val result = args.foldLeft((argsMap, Map.empty[String, String],
      List.empty[String])) { (state, arg) =>
      arg match {
        case SupportedArgument(key, false, _) =>
          val proc = ParameterManager.optionalOptionValue(key)
          handleTriedResult(state._1, state._2, proc, state._3) { (map, res) =>
            res.map(v => map + (key -> v)).getOrElse(map)
          }
        case SupportedArgument(key, true, defaultValue) =>
          val proc = ParameterManager.singleOptionValue(key, defaultValue)
          handleTriedResult(state._1, state._2, proc, state._3) { (map, res) =>
            map + (key -> res)
          }
      }
    }
    if (result._3.nonEmpty)
      throw new IllegalArgumentException(result._3.mkString(", "))
    (result._1, result._2)
  }

  /**
    * Constructs a ''SyncConfig'' object from the passed in components. If all
    * of the passed in components are valid, the corresponding config object is
    * created. Otherwise, all error messages are collected and returned in a
    * failed ''Try''.
    *
    * @param triedUris            the sync URIs component
    * @param triedApplyMode       the apply mode component
    * @param triedTimeout         the timeout component
    * @param triedLogFile         the log file component
    * @param triedSyncLog         the sync log component
    * @param triedTimeDelta       the ignore file time delta component
    * @param triedOpsPerSec       the ops per second component
    * @param triedSrcPassword     the source password component
    * @param triedEncSrcFileNames the source files encrypted component
    * @param triedDstPassword     the destination password component
    * @param triedEncDstFileNames the destination files encrypted component
    * @param triedCryptCacheSize  the crypt cache size component
    * @return a ''Try'' with the config
    */
  private def createSyncConfig(triedUris: Try[(String, String)],
                               triedApplyMode: Try[ApplyMode],
                               triedTimeout: Try[Timeout],
                               triedLogFile: Try[Option[String]],
                               triedSyncLog: Try[Option[String]],
                               triedTimeDelta: Try[Option[Int]],
                               triedOpsPerSec: Try[Option[Int]],
                               triedSrcPassword: Try[Option[String]],
                               triedEncSrcFileNames: Try[Boolean],
                               triedDstPassword: Try[Option[String]],
                               triedEncDstFileNames: Try[Boolean],
                               triedCryptCacheSize: Try[Int]): Try[SyncConfig] = {
    def collectErrorMessages(components: Try[_]*): Iterable[String] =
      components.foldLeft(List.empty[String]) { (lst, c) =>
        c match {
          case Failure(exception) => exception.getMessage :: lst
          case _ => lst
        }
      }

    val messages = collectErrorMessages(triedUris, triedApplyMode, triedTimeout, triedLogFile,
      triedSyncLog, triedTimeDelta, triedOpsPerSec, triedSrcPassword, triedEncSrcFileNames, triedDstPassword,
      triedEncDstFileNames, triedCryptCacheSize)
    if (messages.isEmpty)
      Success(SyncConfig(triedUris.get, triedApplyMode.get, triedTimeout.get,
        mapPath(triedLogFile.get), mapPath(triedSyncLog.get), triedTimeDelta.get,
        triedSrcPassword.get, triedEncSrcFileNames.get, triedDstPassword.get, triedEncDstFileNames.get,
        triedCryptCacheSize.get, triedOpsPerSec.get))
    else Failure(new IllegalArgumentException(messages.mkString(", ")))
  }

  /**
    * Converts a timeout string to the corresponding ''Timeout'' value in
    * seconds. In case of an error, a meaningful message is constructed.
    *
    * @param timeoutStr the string value for the timeout
    * @return a ''Try'' with the converted value
    */
  private def mapTimeout(timeoutStr: String): Try[Timeout] = Try {
    Timeout(ParameterManager.toInt("timeout value")(timeoutStr).seconds)
  }

  /**
    * Returns a processor that extracts the value of the option for ignoring
    * file time deltas. This processor is based on the processor for an
    * optional parameter, but the result has to be mapped to an integer.
    *
    * @return the processor for the ignore time delta option
    */
  private def ignoreTimeDeltaOption(): CliProcessor[Try[Option[Int]]] =
    ParameterManager.optionalIntOptionValue(IgnoreTimeDeltaOption, "threshold for file time deltas")

  /**
    * Returns a processor that extracts he value of the option for the number
    * of sync operations per second.
    *
    * @return the processor for the ops per second option
    */
  private def opsPerSecondOption(): CliProcessor[Try[Option[Int]]] =
    ParameterManager.optionalIntOptionValue(OpsPerSecondOption, "number of operations per second")

  /**
    * Returns a processor that extracts the value of the option for the crypt
    * cache size. The string value is converted to an integer, and some
    * validation is performed.
    *
    * @return the processor for the crypt cache size
    */
  private def cryptCacheSizeOption(): CliProcessor[Try[Int]] =
    ParameterManager.singleOptionValueMapped(CryptCacheSizeOption, Some(DefaultCryptCacheSize.toString)) { s =>
      Try(ParameterManager.toInt("crypt cache size")(s)) map { size =>
        if (size < MinCryptCacheSize)
          throw new IllegalArgumentException(s"Crypt cache size must be greater or equal $MinCryptCacheSize.")
        else size
      }
    }

  /**
    * Transforms an option of a string to an option of a path. The path is
    * resolved.
    *
    * @param optPathStr the option with the path string
    * @return the transformed option for a path
    */
  private def mapPath(optPathStr: Option[String]): Option[Path] =
    optPathStr map (s => Paths get s)
}
