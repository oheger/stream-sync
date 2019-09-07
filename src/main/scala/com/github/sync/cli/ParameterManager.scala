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

import java.nio.file.Paths
import java.util.Locale

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Framing, Sink}
import akka.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

/**
  * A generic service responsible for parsing command line arguments.
  *
  * This service offers functionality to process the command line arguments
  * passed to an application and to convert them to specific configuration
  * objects. There are helper functions to interpret options of different types
  * and to collect arguments that are no options (such as files or directories
  * to be processed). It is possible to check whether all mandatory options are
  * present and that no unsupported options have been specified.
  *
  * This service converts the sequence of command line arguments to a map
  * keyed by known option names. The values are lists with the strings assigned
  * to these options. (Options are allowed to be repeated in the command line
  * and thus can have multiple values; hence their values are represented as
  * lists.) Case does not matter for options; they are always converted to
  * lower case.
  */
object ParameterManager {
  /** The prefix for arguments that are command line options. */
  val OptionPrefix = "--"

  /**
    * Name of an option that collects the input strings that are no values of
    * options.
    */
  val InputOption = "input"

  /**
    * Name of an option that defines a parameters file. The file is read, and
    * its content is added to the command line options.
    */
  val FileOption: String = OptionPrefix + "file"

  /**
    * Type definition for the map with resolved parameter values. The array
    * with command line options is transformed in such a map which allows
    * direct access to the value(s) assigned to options.
    */
  type ParametersMap = Map[String, Iterable[String]]

  /**
    * A data class storing the information required for extracting command
    * line options.
    *
    * This class is used by ''ParameterManager'' to represent parsed command
    * line arguments and to keep track about the option keys that have been
    * read by the application. (This is needed to find additional options
    * provided by the user that are not supported by the application.)
    *
    * @param parametersMap      the map with the options and their values
    * @param accessedParameters a set with the option keys that were queried
    */
  case class Parameters(parametersMap: ParametersMap, accessedParameters: Set[String]) {
    /**
      * Returns a new instance of ''Parameters'' that has the given key marked
      * as accessed.
      *
      * @param key the key affected
      * @return the updated ''Parameters'' instance
      */
    def keyAccessed(key: String): Parameters =
      if (accessedParameters contains key) this
      else copy(accessedParameters = accessedParameters + key)

    /**
      * Returns a new instance of ''Parameters'' that has the given keys marked
      * as accessed.
      *
      * @param keys the collection with keys affected
      * @return the updated ''Parameters'' instance
      */
    def keysAccessed(keys: Iterable[String]): Parameters =
      copy(accessedParameters = accessedParameters ++ keys)

    /**
      * Returns a flag whether all keys in the parameter maps have been
      * accessed. If this property is '''false''' at the end of command line
      * processing, this means that the command line contained unsupported
      * options.
      *
      * @return a flag whether all option keys have been accessed
      */
    def allKeysAccessed: Boolean =
      parametersMap.keySet.forall(accessedParameters.contains)

    /**
      * Returns a set with the option keys that are present, but have not been
      * accessed during command line processing.
      *
      * @return a set with the keys that have not been accessed
      */
    def notAccessedKeys: Set[String] = parametersMap.keySet -- accessedParameters
  }

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
  case class CliProcessor[A](run: Parameters => (A, Parameters)) {
    def flatMap[B](f: A => CliProcessor[B]): CliProcessor[B] = CliProcessor(map => {
      val (a, map1) = run(map)
      f(a).run(map1)
    })

    def map[B](f: A => B): CliProcessor[B] =
      flatMap(a => CliProcessor(m => (f(a), m)))
  }

  /**
    * An implicit conversion to create a ''Parameters'' object from a map of
    * parsed command line options.
    *
    * @param map the map
    * @return the resulting ''Parameters''
    */
  implicit def mapToParameters(map: ParametersMap): Parameters =
    Parameters(map, Set.empty)

  /**
    * Type definition for an internal map type used during processing of
    * command line arguments.
    */
  private type InternalParamMap = Map[String, List[String]]

  /**
    * Parses the command line arguments and converts them into a map keyed by
    * options.
    *
    * @param args the sequence with command line arguments
    * @param ec   the execution context
    * @param mat  an object to materialize streams for reading parameter files
    * @return a future with the parsed map of arguments
    */
  def parseParameters(args: Seq[String])(implicit ec: ExecutionContext, mat: ActorMaterializer): Future[Parameters] = {
    def appendOptionValue(argMap: InternalParamMap, opt: String, value: String):
    InternalParamMap = {
      val optValues = argMap.getOrElse(opt, List.empty)
      argMap + (opt -> (value :: optValues))
    }

    @tailrec def doParseParameters(argsList: Seq[String], argsMap: InternalParamMap):
    InternalParamMap = argsList match {
      case opt :: value :: tail if isOption(opt) =>
        doParseParameters(tail, appendOptionValue(argsMap, toLower(opt), value))
      case h :: t if !isOption(h) =>
        doParseParameters(t, appendOptionValue(argsMap, InputOption, h))
      case h :: _ =>
        throw new IllegalArgumentException("Option without value: " + h)
      case Nil =>
        argsMap
    }

    def parseParameterSeq(argList: Seq[String]): InternalParamMap =
      doParseParameters(argList, Map.empty)

    def parseParametersWithFiles(argList: Seq[String], currentParams: InternalParamMap,
                                 processedFiles: Set[String]): Future[InternalParamMap] = Future {
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

    parseParametersWithFiles(args.toList, Map.empty, Set.empty).map(mapToParameters)
  }

  /**
    * Returns a processor that extracts all values of the specified option key.
    *
    * @param key the key of the option
    * @return the processor to extract the option values
    */
  def optionValue(key: String): CliProcessor[Iterable[String]] = CliProcessor(params => {
    val values = params.parametersMap.getOrElse(key, Nil)
    (values, params keyAccessed key)
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
    * Returns a processor that extracts a boolean option from the command
    * line. The given key must have a single value that is either "true" or
    * "false" (case does not matter).
    *
    * @param key the option key
    * @return the processor to extract the boolean option
    */
  def booleanOptionValue(key: String): CliProcessor[Try[Boolean]] =
    singleOptionValueMapped(key, Some(java.lang.Boolean.FALSE.toString))(s => toBoolean(s, key))

  /**
    * Returns a processor that extracts a single optional value of a command
    * line option. If the option has multiple values, an error is produced. If
    * it has a single value only, this value is returned as an option.
    * Otherwise, result is an undefined ''Option''.
    *
    * @param key the option key
    * @return the processor to extract the optional value
    */
  def optionalOptionValue(key: String): CliProcessor[Try[Option[String]]] =
    optionValue(key) map { values =>
      Try {
        if (values.isEmpty) None
        else if (values.size > 1)
          throw new IllegalArgumentException(s"$key: only a single value is supported!")
        else Some(values.head)
      }
    }

  /**
    * Returns a processor that extracts a single optional value of a command
    * line option of type ''Int''. This is analogous to
    * ''optionalOptionValue()'', but the resulting option is mapped to an
    * ''Int'' (with error handling).
    *
    * @param key    the option key
    * @param errMsg an error message in case the conversion fails
    * @return the processor to extract the optional ''Int'' value
    */
  def optionalIntOptionValue(key: String, errMsg: => String): CliProcessor[Try[Option[Int]]] =
    optionalOptionValue(key) map { strRes =>
      strRes.map(_.map(toInt(errMsg)))
    }

  /**
    * Returns a processor that prompts the user to enter a value for an option
    * if it is not already defined in the command line arguments. This is
    * useful for instance to enter passwords. The processor first checks
    * whether the corresponding command line option has been set. If so, the
    * value is returned. Otherwise, the option key is written to stdout as a
    * prompt, and a string is read from the console. This becomes the value of
    * the option.
    *
    * @param key the option key
    * @return the processor that reads the option from the command line
    */
  def consoleInputOption(key: String): CliProcessor[Try[String]] =
    ParameterManager.optionalOptionValue(key) map { triedOption =>
      triedOption.map {
        case Some(data) => data
        case None =>
          print(s"$key: ")
          StdIn.readLine()
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
    * @param params the object with parameters to be checked
    * @return a future with the passed in map if the check succeeds
    */
  def checkParametersConsumed(params: Parameters): Future[Parameters] =
    if (params.allKeysAccessed) Future.successful(params)
    else Future.failed(new IllegalArgumentException("Found unexpected parameters: " + params.notAccessedKeys))

  /**
    * Returns a collection containing all error messages from the given
    * components. This is used to create an object representation of a group of
    * command line arguments. Only if all components could be extracted
    * successfully, the representation can be created. Otherwise, a list with
    * all errors is returned. The resulting collection is also an indicator
    * whether the representation can be created: if it is empty, there are no
    * errors.
    *
    * @param components the single components
    * @return a collection with error messages extracted from the components
    */
  def collectErrorMessages(components: Try[_]*): Iterable[String] =
    components.collect {
      case Failure(exception) => exception.getMessage
    }

  /**
    * Helper function to create an object representation for a set of
    * components that have been extracted from command line options. The
    * function checks whether all components are successful. If so, the given
    * creator is invoked. Otherwise, result is a failure with an exception
    * that contains all error messages concatenated.
    *
    * @param components the single components
    * @param creator    the function to create the representation
    * @tparam T the type of the representation
    * @return a ''Try'' with the representation or the error messages
    */
  def createRepresentation[T](components: Try[_]*)(creator: => T): Try[T] = {
    val messages = collectErrorMessages(components: _*)
    if (messages.isEmpty) Success(creator)
    else Failure(new IllegalArgumentException(messages.mkString(", ")))
  }

  /**
    * Generates a ''Try'' for the given expression that contains a meaningful
    * exception in case of a failure. This function maps the original
    * exception to an ''IllegalArgumentException'' with a message that contains
    * the name of the parameter.
    *
    * @param key the parameter key
    * @param f   the expression
    * @tparam T the result type of the expression
    * @return a succeeded ''Try'' with the expression value or a failed ''Try''
    *         with a meaningful exception
    */
  def paramTry[T](key: String)(f: => T): Try[T] =
    Try(f) recover {
      case ex => throw new IllegalArgumentException(s"$key: ${ex.getMessage}.", ex)
    }

  /**
    * A mapping function to convert a string to an integer. If this fails due
    * to a ''NumberFormatException'', an ''IllegalArgumentException'' is thrown
    * with a message generated from the passed tag and the original string
    * value.
    *
    * @param tag a tag for generating an error message
    * @param str the string to be converted
    * @return the resulting integer value
    */
  def toInt(tag: String)(str: String): Int =
    try str.toInt
    catch {
      case e: NumberFormatException =>
        throw new IllegalArgumentException(s"Invalid $tag: '$str'!", e)
    }

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
  private def combineParameterMaps(m1: InternalParamMap, m2: InternalParamMap): InternalParamMap =
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
    * Conversion function to convert a string to a boolean. The case does not
    * matter, but the string must either be "true" or "false".
    *
    * @param s   the string to be converted
    * @param key the option key (to generate the error message)
    * @return a ''Try'' with the conversion result
    */
  private def toBoolean(s: String, key: String): Try[Boolean] = toLower(s) match {
    case "true" => Success(true)
    case "false" => Success(false)
    case _ => Failure(new IllegalArgumentException(s"$key: Not a valid boolean value '$s'."))
  }

  /**
    * Converts a string to lower case.
    *
    * @param s the string
    * @return the string in lower case
    */
  private def toLower(s: String): String = s.toLowerCase(Locale.ROOT)
}
