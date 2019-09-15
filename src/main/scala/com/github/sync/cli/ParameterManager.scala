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
    * Type definition for the base type of a command line option. Values are
    * strings, but options can be repeated; therefore, the type is an
    * ''Iterable'' of strings.
    */
  type OptionValue = Iterable[String]

  /**
    * Type definition for the value of an option that accepts a single value at
    * most. Parsing the option may cause an error if there are multiple values
    * (because the option key had been repeated); therefore a ''Try'' is used.
    * As the value may be undefined, there is also an ''Option'' included. Some
    * mapping processors operate on this type.
    */
  type SingleOptionValue[A] = Try[Option[A]]

  /**
    * Type definition for the map with resolved parameter values. The array
    * with command line options is transformed in such a map which allows
    * direct access to the value(s) assigned to options.
    */
  type ParametersMap = Map[String, OptionValue]

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
    * A data class storing all the information required for the processing of
    * command line arguments.
    *
    * An instance of this class stores the actual [[Parameters]] plus some
    * helper objects that may be needed to extract meaningful data.
    *
    * @param parameters the parameters to be processed
    * @param reader     an object to read data from the console
    */
  case class ParameterContext(parameters: Parameters, reader: ConsoleReader) {
    /**
      * Returns a new ''ParameterContext'' object that was updated with the
      * given ''Parameters''. All other properties remain constant.
      *
      * @param nextParameters the ''Parameters'' to replace the current ones
      * @return the updated ''ParameterContext''
      */
    def update(nextParameters: Parameters): ParameterContext =
      copy(parameters = nextParameters)
  }

  /**
    * A case class representing a processor for command line options.
    *
    * This is a kind of state action. Such processors can be combined to
    * extract multiple options from the command line and to mark the
    * corresponding option keys as accessed.
    *
    * @param run a function to obtain an option and update the arguments map
    * @tparam A the type of the result of the processor
    */
  case class CliProcessor[A](run: ParameterContext => (A, ParameterContext)) {
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
    * Returns a ''CliProcessor'' that always returns the given constant value
    * as result without manipulating the parameter context. This processor is
    * mainly useful for building up complex processors, e.g. together with
    * conditions or default values for optional parameters.
    *
    * @param a the constant value to be returned
    * @tparam A the type of the value
    * @return the ''CliProcessor'' returning this constant value
    */
  def constantProcessor[A](a: A): CliProcessor[A] = CliProcessor(context => (a, context))

  /**
    * Returns a ''CliProcessor'' that returns a constant collection of option
    * values. This is a special case of a constant processor that operates on
    * the base type of command line arguments.
    *
    * @param first the first value
    * @param items a sequence of additional values
    * @return the ''CliProcessor'' returning this constant ''OptionValue''
    */
  def constantOptionValue(first: String, items: String*): CliProcessor[OptionValue] =
    constantProcessor(first :: items.toList)

  /**
    * Returns a processor that extracts all values of the specified option key.
    *
    * @param key the key of the option
    * @return the processor to extract the option values
    */
  def optionValue(key: String): CliProcessor[OptionValue] = CliProcessor(context => {
    val values = context.parameters.parametersMap.getOrElse(key, Nil)
    (values, context.update(context.parameters keyAccessed key))
  })

  /**
    * Returns a processor that can apply a fallback (or default) value to
    * another processor. The resulting processor invokes the first processor.
    * If this yields a defined result, this result is returned. Otherwise, the
    * fallback processor is returned.
    *
    * @param proc         the first processor to be invoked
    * @param fallbackProc the fallback processor
    * @return the resulting processor applying a fallback value
    */
  def withFallback(proc: CliProcessor[OptionValue], fallbackProc: CliProcessor[OptionValue]):
  CliProcessor[OptionValue] =
    proc flatMap { result =>
      if (result.nonEmpty) constantProcessor(result)
      else fallbackProc
    }

  /**
    * Returns a processor that prompts the user for entering the value of an
    * option. This is done by delegating to the [[ConsoleReader]] in the
    * parameter context passed to the processor. This function can be used for
    * instance together with ''withFallback()'' to let the user enter a value
    * if it has not been provided on the command line.
    *
    * @param key      the key of the option
    * @param password a flag whether a password is to be entered
    * @return the processor that reads from the console
    */
  def consoleReaderValue(key: String, password: Boolean): CliProcessor[OptionValue] =
    CliProcessor(context => (List(context.reader.readOption(key, password)), context))

  /**
    * Returns a processor that extracts a single option value from the result
    * of the given processor. It is an error if the result contains multiple
    * values; however, an undefined value is accepted.
    *
    * @param key  the key of the option (to generate an error message)
    * @param proc the processor to be decorated
    * @return the processor extracting the single option value
    */
  def asSingleOptionValue(key: String, proc: CliProcessor[OptionValue]): CliProcessor[SingleOptionValue[String]] =
    proc map { optionValue =>
      if (optionValue.size > 1)
        Failure(paramException(key, s"should have a single value, but has multiple values - $optionValue"))
      else Success(optionValue.headOption)
    }

  /**
    * Returns a processor that enforces an option to have a defined value. If
    * the provided processor yields a ''Try'' with an undefined option, a
    * failure is generated. Otherwise, the option value is unwrapped.
    *
    * @param key  the key of the option (to generate an error message)
    * @param proc the processor providing the original value
    * @tparam A the result type
    * @return the processor returning a mandatory value
    */
  def asMandatory[A](key: String, proc: CliProcessor[SingleOptionValue[A]]): CliProcessor[Try[A]] =
    proc.map(_.flatMap {
      case Some(v) => Success(v)
      case None => Failure(paramException(key, "mandatory option has no value"))
    })

  /**
    * Returns a processor that modifies the result of another processor by
    * applying a mapping function. While mapping is supported by processors in
    * general, this function simplifies this for ''SingleOptionValue'' results.
    * The mapping function operates on the result type, and it is called only
    * if the ''Try'' and the ''Option'' are defined. The mapping function can
    * throw an exception; this is handled automatically by causing the result
    * to fail.
    *
    * @param key  the key of the option (to generate an error message)
    * @param proc the processor to be decorated
    * @param f    the mapping function to be applied
    * @tparam A the original result type
    * @tparam B the mapped result type
    * @return the processor applying the mapping function
    */
  def mapped[A, B](key: String, proc: CliProcessor[SingleOptionValue[A]])(f: A => B):
  CliProcessor[SingleOptionValue[B]] =
    proc.map(triedResult => triedResult.flatMap(o => paramTry(key)(o.map(f))))

  /**
    * Returns a processor that converts a command line argument to an int
    * number. If the option has a defined string value, it is converted to a
    * number including error handling. Undefined values are ignored.
    *
    * @param key  the key of the option (to generate an error message)
    * @param proc the processor providing the original option value
    * @return the processor converting the value to a number
    */
  def asIntOptionValue(key: String, proc: CliProcessor[SingleOptionValue[String]]):
  CliProcessor[SingleOptionValue[Int]] = mapped(key, proc)(_.toInt)

  /**
    * Returns a processor that converts a command line argument to a boolean
    * value. If the option has a defined string value, it is converted to a
    * boolean including error handling. Undefined values are ignored.
    *
    * @param key  the key of the option (to generate an error message)
    * @param proc the processor providing the original option value
    * @return the processor converting the value to a boolean
    */
  def asBooleanOptionValue(key: String, proc: CliProcessor[SingleOptionValue[String]]):
  CliProcessor[SingleOptionValue[Boolean]] = mapped(key, proc) { s =>
    toLower(s) match {
      case "true" => true
      case "false" => false
      case s => throw new IllegalArgumentException(s"'$s' cannot be converted to a boolean")
    }
  }

  /**
    * Returns a processor to extract the single string value of a command line
    * option. It is possible to specify a fallback value if this option is
    * undefined. The processor fails if the option has multiple values.
    *
    * @param key           the key of the option
    * @param fallbackValue an optional fallback value to be set
    * @return the processor extracting the single value of an option
    */
  def singleOptionValue(key: String, fallbackValue: Option[String] = None):
  CliProcessor[SingleOptionValue[String]] = {
    val procOptValue = optionValue(key)
    val procFallback = fallbackValue map { fallback =>
      withFallback(procOptValue, constantOptionValue(fallback))
    } getOrElse procOptValue
    asSingleOptionValue(key, procFallback)
  }

  /**
    * Returns a processor to extract the single integer value of a command line
    * option. This works like ''singleOptionValue'', but the the result is
    * mapped to an Int.
    *
    * @param key           the key of the option
    * @param fallbackValue an optional fallback value to be set
    * @return the processor extracting the single Int value of an option
    */
  def intOptionValue(key: String, fallbackValue: Option[Int] = None): CliProcessor[SingleOptionValue[Int]] =
    asIntOptionValue(key, singleOptionValue(key, fallbackValue map (_.toString)))

  /**
    * Returns a processor to extract the single boolean value of a command line
    * option. This works like ''singleOptionValue'', but the the result is
    * mapped to a Boolean.
    *
    * @param key           the key of the option
    * @param fallbackValue an optional fallback value to be set
    * @return the processor extracting the single Boolean value of an option
    */
  def booleanOptionValue(key: String, fallbackValue: Option[Boolean] = None):
  CliProcessor[SingleOptionValue[Boolean]] =
    asBooleanOptionValue(key, singleOptionValue(key, fallbackValue map (_.toString)))

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
    * Executes the given ''CliProcessor'' on the parameters specified and
    * returns its result and the updated ''Parameters'' object.
    *
    * @param processor     the processor to be executed
    * @param parameters    the current ''Parameters''
    * @param consoleReader the object to read from the console
    * @tparam T the result type of the ''CliProcessor''
    * @return a tuple with the result and the updated parameters
    */
  def runProcessor[T](processor: CliProcessor[T], parameters: Parameters)
                     (implicit consoleReader: ConsoleReader): (T, Parameters) = {
    val context = ParameterContext(parameters, consoleReader)
    val (result, nextContext) = processor.run(context)
    (result, nextContext.parameters)
  }

  /**
    * Executes the given ''CliProcessor'' that may fail on the parameters
    * specified. Result is a ''Try'' with the processor's result and the
    * updated ''Parameters'' object. This function is useful if a failed
    * processor should cause the whole operation to fail.
    *
    * @param processor     the processor to be executed
    * @param parameters    the current ''Parameters'' object
    * @param consoleReader the object to read from the console
    * @tparam T the result type of the ''CliProcessor''
    * @return a ''Try'' of a tuple with the result and the updated parameters
    */
  def tryProcessor[T](processor: CliProcessor[Try[T]], parameters: Parameters)
                     (implicit consoleReader: ConsoleReader): Try[(T, Parameters)] = {
    val (triedRes, next) = runProcessor(processor, parameters)
    triedRes map ((_, next))
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
      case ex => throw paramException(key, ex.getMessage, ex)
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
    * Converts a string to lower case.
    *
    * @param s the string
    * @return the string in lower case
    */
  private def toLower(s: String): String = s.toLowerCase(Locale.ROOT)

  /**
    * Generates an exception that reports a problem with a specific command
    * line option. Makes sure that such exceptions have a uniform format.
    *
    * @param key     the option key
    * @param message the error message
    * @param cause   an option cause of the error
    * @return the resulting exception
    */
  private def paramException(key: String, message: String, cause: Throwable = null): Throwable =
    new IllegalArgumentException(s"$key: ${generateErrorMessage(message, cause)}", cause)

  /**
    * Generates the error message for an exception encountered during parameter
    * processing. If there is a cause available, the exception class name is
    * added to the message.
    *
    * @param message the original error message
    * @param cause   the causing exception
    * @return the enhanced error message
    */
  private def generateErrorMessage(message: String, cause: Throwable): String = {
    val exceptionName = if (cause == null) ""
    else cause.getClass.getName + " - "
    s"$exceptionName$message"
  }
}
