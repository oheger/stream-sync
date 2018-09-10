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

package com.github.sync.cli

import java.nio.file.{Path, Paths}
import java.util.Locale

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Framing, Sink}
import akka.util.{ByteString, Timeout}

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
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
object ParameterManager {
  /** Key for the reserved option under which URIs to be synced are grouped. */
  val SyncUriOption = "syncUri"

  /** The prefix for arguments that are command line options. */
  val OptionPrefix = "--"

  /**
    * Name of an option that defines a parameters file. The file is read, and
    * its content is added to the command line options.
    */
  val FileOption: String = OptionPrefix + "file"

  /** Name of the option for the apply mode. */
  val ApplyModeOption: String = OptionPrefix + "apply"

  /** Name of the option that defines a timeout for sync operations. */
  val TimeoutOption: String = OptionPrefix + "timeout"

  /** The default timeout for sync operations. */
  val DefaultTimeout = Timeout(1.minute)

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
    * A concrete apply mode meaning that sync operations are serialized and
    * written to a log. (No actual changes are executed.)
    *
    * @param logFilePath the path to the log file to be written
    */
  case class ApplyModeLog(logFilePath: Path) extends ApplyMode

  /**
    * A class that holds the configuration options for a sync process.
    *
    * An instance of this class is created from the command line options passed
    * to the program.
    *
    * @param syncUris  the URIs to be synced (source and destination)
    * @param applyMode the apply mode
    * @param timeout   a timeout for sync operations
    */
  case class SyncConfig(syncUris: (String, String),
                        applyMode: ApplyMode,
                        timeout: Timeout)

  /**
    * A case class representing a processor for command line options.
    *
    * This is a kind of state action. Such processors can be combined to
    * extract multiple options from the command line and to remove the
    * corresponding option keys from the map with arguments.
    *
    * @param run a function to obtain an option and update the arguments map
    * @tparam A the type of the result of the processor
    */
  case class CliProcessor[A](run: Map[String, Iterable[String]] =>
    (A, Map[String, Iterable[String]])) {
    def flatMap[B](f: A => CliProcessor[B]): CliProcessor[B] = CliProcessor(map => {
      val (a, map1) = run(map)
      f(a).run(map1)
    })

    def map[B](f: A => B): CliProcessor[B] =
      flatMap(a => CliProcessor(m => (f(a), m)))
  }

  /**
    * Type definition for an internal map type used during processing of
    * command line arguments.
    */
  private type ParamMap = Map[String, List[String]]

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
    * Regular expression for parsing the apply mode ''Log'' with the path to
    * the log file.
    */
  private val RegApplyLog =
    """(?i)LOG:(.+)""".r

  /**
    * Parses the command line arguments and converts them into a map keyed by
    * options.
    *
    * @param args the sequence with command line arguments
    * @param ec   the execution context
    * @param mat  an object to materialize streams for reading parameter files
    * @return a future with the parsed map of arguments
    */
  def parseParameters(args: Seq[String])(implicit ec: ExecutionContext, mat: ActorMaterializer):
  Future[Map[String, Iterable[String]]] = {
    def appendOptionValue(argMap: ParamMap, opt: String, value: String):
    ParamMap = {
      val optValues = argMap.getOrElse(opt, List.empty)
      argMap + (opt -> (value :: optValues))
    }

    @tailrec def doParseParameters(argsList: Seq[String], argsMap: ParamMap):
    ParamMap = argsList match {
      case opt :: value :: tail if isOption(opt) =>
        doParseParameters(tail, appendOptionValue(argsMap, opt.toLowerCase(Locale.ROOT), value))
      case h :: t if !isOption(h) =>
        doParseParameters(t, appendOptionValue(argsMap, SyncUriOption, h))
      case h :: _ =>
        throw new IllegalArgumentException("Option without value: " + h)
      case Nil =>
        argsMap
    }

    def parseParameterSeq(argList: Seq[String]): ParamMap =
      doParseParameters(argList, Map.empty)

    def parseParametersWithFiles(argList: Seq[String], currentParams: ParamMap,
                                 processedFiles: Set[String]): Future[ParamMap] = Future {
      combineParameterMaps(currentParams, parseParameterSeq(argList))
    } flatMap { argMap =>
      argMap get FileOption match {
        case None =>
          Future.successful(argMap)
        case Some(files) =>
          val filesToRead = files.toSet diff processedFiles
          readAllParameterFiles(filesToRead.toList) flatMap { argList =>
            parseParametersWithFiles(argList, argMap - FileOption, processedFiles ++ filesToRead)
          }
      }
    }

    parseParametersWithFiles(args.toList, Map.empty, Set.empty)
  }

  /**
    * Returns a processor that extracts all values of the specified option key.
    *
    * @param key the key of the option
    * @return the processor to extract the option values
    */
  def optionValue(key: String): CliProcessor[Iterable[String]] = CliProcessor(map => {
    val values = map.getOrElse(key, Nil)
    (values, map - key)
  })

  /**
    * Returns a processor that extracts a single value of a command line
    * option. If the option has multiple values, a failure is generated. An
    * option with a default value can be specified.
    *
    * @param key      the option key
    * @param defValue a default value
    * @return the processor to extract the single option value
    */
  def singleOptionValue(key: String, defValue: => Option[String] = None):
  CliProcessor[Try[String]] = optionValue(key) map { values =>
    Try {
      if (values.size > 1) throw new IllegalArgumentException(key + " has multiple values!")
      values.headOption orElse defValue match {
        case Some(value) =>
          value
        case None =>
          throw new IllegalArgumentException("No value specified for " + key + "!")
      }
    }
  }

  /**
    * Returns a processor that extracts the single value of a command line
    * option and applies a mapping function on it. Calls
    * ''singleOptionValue()'' and then invokes the mapping function.
    *
    * @param key      the option key
    * @param defValue a default value
    * @param f        the mapping function
    * @tparam R the result type of the mapping function
    * @return the processor to extract the single option value
    */
  def singleOptionValueMapped[R](key: String, defValue: => Option[String] = None)
                                (f: String => Try[R]): CliProcessor[Try[R]] =
    singleOptionValue(key, defValue) map (_.flatMap(f))

  /**
    * Returns a processor that extracts the value of the option with the URIs
    * of the structures to be synced.
    *
    * @return the processor to extract the sync URIs
    */
  def syncUrisProcessor(): CliProcessor[Try[(String, String)]] =
    optionValue(SyncUriOption) map { values =>
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
    singleOptionValue(ApplyModeOption, Some("TARGET")) map (_.map {
      case RegApplyTargetUri(uri) =>
        ApplyModeTarget(uri)
      case RegApplyTargetDefault(_*) =>
        ApplyModeTarget(destUri)
      case RegApplyLog(path) =>
        ApplyModeLog(Paths get path)
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
    timeout <- singleOptionValueMapped(TimeoutOption,
      Some(DefaultTimeout.duration.toSeconds.toString))(mapTimeout)
  } yield createSyncConfig(uris, mode, timeout)

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
  def extractSyncConfig(argsMap: Map[String, Iterable[String]])(implicit ec: ExecutionContext):
  Future[(Map[String, Iterable[String]], SyncConfig)] = Future {
    val (triedConfig, map) = syncConfigProcessor().run(argsMap)
    (map, triedConfig.get)
  }

  /**
    * Validates the map with arguments whether two URIs for the sync process
    * have been provided. If successful, the two URIs are returned as tuple
    * (with the source URI in the first and the destination URI in the second
    * component); also the arguments map with these parameters removed. (This
    * is later used to check whether all parameters have been consumed.)
    *
    * @param argsMap the map with arguments
    * @param ec      the execution context
    * @return a future with the extracted URIs and the updated arguments map
    */
  def extractSyncUris(argsMap: Map[String, Iterable[String]])(implicit ec: ExecutionContext):
  Future[(Map[String, Iterable[String]], (String, String))] = Future {
    val updatedArgs = argsMap - SyncUriOption
    argsMap.getOrElse(SyncUriOption, List.empty) match {
      case uriDst :: uriSrc :: Nil =>
        (updatedArgs, (uriSrc, uriDst))
      case _ :: _ :: _ =>
        throw new IllegalArgumentException("Too many sync URIs specified!")
      case _ :: _ =>
        throw new IllegalArgumentException("Missing destination URI!")
      case _ =>
        throw new IllegalArgumentException("Missing URIs for source and destination!")
    }
  }

  /**
    * Obtains the ''ApplyMode'' from the given map of command line arguments.
    * The apply mode is optional; if the corresponding option is not present, a
    * default is returned based on the passed in destination URI. Otherwise,
    * the option is validated, and its value is converted to an [[ApplyMode]]
    * instance. In the returned updated arguments map the apply mode option has
    * been removed.
    *
    * @param argsMap the map with arguments
    * @param destUri the URI for the destination structure
    * @param ec      the execution context
    * @return a future with the updated arguments map and the apply mode
    */
  def extractApplyMode(argsMap: Map[String, Iterable[String]], destUri: String)
                      (implicit ec: ExecutionContext):
  Future[(Map[String, Iterable[String]], ApplyMode)] =
    singleOptionValue(argsMap, ApplyModeOption, Some("TARGET")) map { v =>
      v._2 match {
        case RegApplyTargetUri(uri) =>
          (v._1, ApplyModeTarget(uri))
        case RegApplyTargetDefault(_*) =>
          (v._1, ApplyModeTarget(destUri))
        case RegApplyLog(path) =>
          (v._1, ApplyModeLog(Paths get path))
        case s =>
          throw new IllegalArgumentException(s"Invalid apply mode: '$s'!")
      }
    }

  /**
    * Obtains the option for the timeout from the given map of command line
    * arguments. If the option is missing, the default timeout value is used.
    * Otherwise, the value is validated to be an integer number for the timeout
    * in seconds.
    *
    * @param argsMap the map with arguments
    * @param ec      the execution context
    * @return a future with the updated arguments map and the timeout
    */
  def extractTimeout(argsMap: Map[String, Iterable[String]])(implicit ec: ExecutionContext):
  Future[(Map[String, Iterable[String]], Timeout)] =
    singleOptionValue(argsMap, TimeoutOption,
      Some(DefaultTimeout.duration.toSeconds.toString)) map { v =>
      (v._1, Timeout(v._2.toInt.seconds))
    }

  /**
    * Queries the value of an option that is expected to have exactly one
    * value. It is possible to provide a default value if this option is
    * optional. Result is the value of this option and the arguments map with
    * the option key removed.
    *
    * @param argsMap  the map with arguments
    * @param key      the key of the option in question
    * @param defValue an optional default value
    * @param ec       the execution context
    * @return a future with the option's value and the updated arguments map
    */
  def singleOptionValue(argsMap: Map[String, Iterable[String]], key: String,
                        defValue: => Option[String])(implicit ec: ExecutionContext):
  Future[(Map[String, Iterable[String]], String)] = Future {
    val values = argsMap.getOrElse(key, List.empty)
    if (values.size > 1) throw new IllegalArgumentException(key + " has multiple values!")
    values.headOption orElse defValue match {
      case Some(value) =>
        (argsMap - key, value)
      case None =>
        throw new IllegalArgumentException("No value specified for " + key + "!")
    }
  }

  /**
    * Checks whether all parameters in the given parameters map have been
    * consumed. This is a test to find out whether invalid parameters have been
    * specified. During parameter processing, parameters that are recognized and
    * processed by sub systems are removed from the map with parameters. So if
    * there are remaining parameters, this means that the user has specified
    * unknown or superfluous ones. In this case, parameter validation should
    * fail and no action should be executed.
    *
    * @param argsMap the map with parameters to be checked
    * @return a future with the passed in map if the check succeeds
    */
  def checkParametersConsumed(argsMap: Map[String, Iterable[String]]):
  Future[Map[String, Iterable[String]]] =
    if (argsMap.isEmpty) Future.successful(argsMap)
    else Future.failed(new IllegalArgumentException("Found unexpected parameters: " + argsMap))

  /**
    * Checks whether the given argument string is an option. This is the case
    * if it starts with the option prefix.
    *
    * @param arg the argument to be checked
    * @return a flag whether this argument is an option
    */
  private def isOption(arg: String): Boolean = arg startsWith OptionPrefix

  /**
    * Creates a combined parameter map from the given source maps. The lists
    * with the values of parameter options need to be concatenated.
    *
    * @param m1 the first map
    * @param m2 the second map
    * @return the combined map
    */
  private def combineParameterMaps(m1: ParamMap, m2: ParamMap): ParamMap =
    m2.foldLeft(m1) { (resMap, e) =>
      val values = resMap.getOrElse(e._1, List.empty)
      resMap + (e._1 -> (e._2 ::: values))
    }

  /**
    * Reads a file with parameters asynchronously and returns its single lines
    * as a list of strings.
    *
    * @param path the path to the parameters
    * @param mat  the ''ActorMaterializer'' for reading the file
    * @param ec   the execution context
    * @return a future with the result of the read operation
    */
  private def readParameterFile(path: String)
                               (implicit mat: ActorMaterializer, ec: ExecutionContext):
  Future[List[String]] = {
    val source = FileIO.fromPath(Paths get path)
    val sink = Sink.fold[List[String], String](List.empty)((lst, line) => line :: lst)
    source.via(Framing.delimiter(ByteString("\n"), 1024,
      allowTruncation = true))
      .map(bs => bs.utf8String.trim)
      .filter(_.length > 0)
      .runWith(sink)
      .map(_.reverse)
  }

  /**
    * Reads all parameter files referenced by the provided list. The arguments
    * they contain are combined to a single sequence of strings.
    *
    * @param files list with the files to be read
    * @param mat   the ''ActorMaterializer'' for reading files
    * @param ec    the execution context
    * @return a future with the result of the combined read operation
    */
  private def readAllParameterFiles(files: List[String])
                                   (implicit mat: ActorMaterializer, ec: ExecutionContext):
  Future[List[String]] =
    Future.sequence(files.map(readParameterFile)).map(_.flatten)

  /**
    * Constructs a ''SyncConfig'' object from the passed in components. If all
    * of the passed in components are valid, the corresponding config object is
    * created. Otherwise, all error messages are collected and returned in a
    * failed ''Try''.
    *
    * @param triedUris      the sync URIs component
    * @param triedApplyMode the apply mode component
    * @param triedTimeout   the timeout component
    * @return a ''Try'' with the config
    */
  private def createSyncConfig(triedUris: Try[(String, String)],
                               triedApplyMode: Try[ApplyMode],
                               triedTimeout: Try[Timeout]): Try[SyncConfig] = {
    def collectErrorMessages(components: Try[_]*): Iterable[String] =
      components.foldLeft(List.empty[String]) { (lst, c) =>
        c match {
          case Failure(exception) => exception.getMessage :: lst
          case _ => lst
        }
      }

    val messages = collectErrorMessages(triedUris, triedApplyMode, triedTimeout)
    if (messages.isEmpty)
      Success(SyncConfig(triedUris.get, triedApplyMode.get, triedTimeout.get))
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
    try Timeout(timeoutStr.toInt.seconds)
    catch {
      case _: NumberFormatException =>
        throw new IllegalArgumentException(s"Invalid timeout value: '$timeoutStr'!")
    }
  }
}
