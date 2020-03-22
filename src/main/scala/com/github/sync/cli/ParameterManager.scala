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

import java.nio.file.{Path, Paths}
import java.util.Locale

import akka.actor.ActorSystem
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
    * Type definition for the base type of a command line option. The option
    * can have an arbitrary type and multiple values. As each type conversion
    * can fail, it is a ''Try''.
    */
  type OptionValue[A] = Try[Iterable[A]]

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
    * @param run    a function to obtain an option and update the arguments map
    * @param optKey optional key of the option to be extracted
    * @tparam A the type of the result of the processor
    */
  case class CliProcessor[A](run: ParameterContext => (A, ParameterContext), optKey: Option[String] = None) {
    /**
      * Returns the key of the option this processor deals with. If there is no
      * key, result is an empty string. This case should normally not occur in
      * practice.
      *
      * @return the key of the option to be extracted by this processor
      */
    def key: String = optKey getOrElse ""

    def flatMap[B](f: A => CliProcessor[B]): CliProcessor[B] = CliProcessor(ctx => {
      val (a, map1) = run(ctx)
      f(a).run(map1)
    }, optKey)

    def map[B](f: A => B): CliProcessor[B] =
      flatMap(a => CliProcessor(ctx => (f(a), ctx), optKey))
  }

  /**
    * A class providing additional operations on a ''CliProcessor'' of type
    * ''OptionValue''.
    *
    * With the help of this class and an implicit conversion function the
    * construction of more complex ''CliProcessor'' objects is simplified.
    * Specific functionality can be added to a processor by invoking one of the
    * functions offered by this class rather than using the functions of
    * [[ParameterManager]].
    *
    * @param proc the ''CliProcessor'' decorated by this class
    * @tparam A the result type of the ''CliProcessor''
    */
  class CliProcessorOptionsOps[A](proc: CliProcessor[OptionValue[A]]) {
    /**
      * Adds a fallback or default value to the managed ''CliProcessor''. If
      * the original processor does not yield a value, the fallback processor
      * is evaluated.
      *
      * @param fallbackProc the fallback ''CliProcessor''
      * @return the ''CliProcessor'' supporting a fallback
      */
    def fallback(fallbackProc: CliProcessor[OptionValue[A]]): CliProcessor[OptionValue[A]] =
      withFallback(proc, fallbackProc)

    /**
      * Adds a processor as fallback to the managed ''CliProcessor'' that
      * produces the passed in constant values. Works the same way as
      * ''fallback()'', but creates the fallback processor itself.
      *
      * @param firstValue the first fallback value
      * @param moreValues additional fallback values
      * @return the ''CliProcessor'' supporting these fallback values
      */
    def fallbackValues(firstValue: A, moreValues: A*): CliProcessor[OptionValue[A]] =
      fallback(constantOptionValue(firstValue, moreValues: _*))

    /**
      * Returns a ''CliProcessor'' based on the managed processor that yields a
      * single optional value. So the option extracted by this processor may
      * have at most one value.
      *
      * @return the ''CliProcessor'' yielding a single optional value
      */
    def single: CliProcessor[SingleOptionValue[A]] =
      asSingleOptionValue(proc)

    /**
      * Returns a ''CliProcessor'' that yields a boolean result indicating
      * whether the option extracted by the managed processor has a value. This
      * is useful for instance when constructing conditional processors; so
      * conditions can be defined based on the presence of certain options.
      *
      * @return the ''CliProcessor'' checking whether an option has a value
      */
    def isDefined: CliProcessor[Try[Boolean]] = isOptionDefined(proc)

    /**
      * Returns a ''CliProcessor'' that yields the value of the managed
      * processor with the given mapping function applied to it. In contrast to
      * the plain ''map()'' function, this function is more convenient for
      * values of type ''OptionValue'' because the mapping function operates
      * directly on the values and does not have to deal with ''Try'' or
      * ''Iterable'' objects.
      *
      * @param f the mapping function on the option values
      * @tparam B the result type of the mapping function
      * @return the ''CliProcessor'' applying the mapping function
      */
    def mapTo[B](f: A => B): CliProcessor[OptionValue[B]] =
      mapped(proc)(f)
  }

  /**
    * A helper class providing additional functionality to ''CliProcessor''
    * objects related to data type conversions.
    *
    * This class offers some functions to convert string option values to
    * other data types. If a conversion fails (because the input string has an
    * unexpected format), the resulting ''CliProcessor'' yields a ''Failure''
    * result.
    *
    * @param proc the ''CliProcessor'' decorated by this class
    */
  class CliProcessorConvertOps(proc: CliProcessor[OptionValue[String]]) {
    /**
      * Returns a ''CliProcessor'' that converts the option values of the
      * managed processor to ''Int'' values.
      *
      * @return the ''CliProcessor'' extracting ''Int'' values
      */
    def toInt: CliProcessor[OptionValue[Int]] = asIntOptionValue(proc)

    /**
      * Returns a ''CliProcessor'' that converts the option values of the
      * managed processor to ''Boolean'' values. The strings must have the
      * values *true* or *false* to be recognized.
      *
      * @return the ''CliProcessor'' extracting ''Boolean'' values
      */
    def toBoolean: CliProcessor[OptionValue[Boolean]] = asBooleanOptionValue(proc)

    /**
      * Returns a ''CliProcessor'' that converts the option values of the
      * managed processor to ''Path'' values.
      *
      * @return the ''CliProcessor'' extracting ''Path'' values
      */
    def toPath: CliProcessor[OptionValue[Path]] = asPathOptionValue(proc)
  }

  /**
    * A helper class providing additional functionality to ''CliProcessor''
    * objects that yield only a single value.
    *
    * @param proc the ''CliProcessor'' decorated by this class
    * @tparam A the result type of the ''CliProcessor''
    */
  class CliProcessorSingleOps[A](proc: CliProcessor[SingleOptionValue[A]]) {
    /**
      * Returns a ''CliProcessor'' based on the managed processor that yields
      * a single value. Per default, ''CliProcessor'' objects of type
      * ''SingleOptionValue'' return an ''Option''. This function checks
      * whether the ''Option'' is defined. If so, its value is returned
      * directly; otherwise, the processor yields a ''Failure'' result.
      *
      * @return the ''CliProcessor'' extracting a mandatory single value
      */
    def mandatory: CliProcessor[Try[A]] = asMandatory(proc)
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
    * An implicit conversion function to decorate a ''CliProcessor'' with a
    * ''CliProcessorOptionsOps'' object.
    *
    * @param proc the processor to be decorated
    * @tparam A the result type of the processor
    * @return the ''CliProcessorOptionsOps'' object decorating this processor
    */
  implicit def toOptionsOps[A](proc: CliProcessor[OptionValue[A]]): CliProcessorOptionsOps[A] =
    new CliProcessorOptionsOps(proc)

  /**
    * An implicit conversion function to decorate a ''CliProcessor'' with a
    * ''CliProcessorConvertOps'' object.
    *
    * @param proc the processor to be decorated
    * @return the ''CliProcessorConvertOps'' object decorating this processor
    */
  implicit def toConvertOps(proc: CliProcessor[OptionValue[String]]): CliProcessorConvertOps =
    new CliProcessorConvertOps(proc)

  /**
    * An implicit conversion function to decorate a ''CliProcessor'' with a
    * ''CliProcessorSingleOps'' object.
    *
    * @param proc the processor to be decorated
    * @tparam A the result type of the processor
    * @return the ''CliProcessorSingleOps'' object decorating this processor
    */
  implicit def toSingleOps[A](proc: CliProcessor[SingleOptionValue[A]]): CliProcessorSingleOps[A] =
    new CliProcessorSingleOps(proc)

  /**
    * Type definition for an internal map type used during processing of
    * command line arguments.
    */
  private type InternalParamMap = Map[String, List[String]]

  /**
    * Parses the command line arguments and converts them into a map keyed by
    * options.
    *
    * @param args   the sequence with command line arguments
    * @param ec     the execution context
    * @param system the actor system
    * @return a future with the parsed map of arguments
    */
  def parseParameters(args: Seq[String])(implicit ec: ExecutionContext, system: ActorSystem): Future[Parameters] = {
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
    * Returns an option value of the given type that does not contain any data.
    * This is used by some processors to set default values that are not
    * further evaluated.
    *
    * @return the empty option value of the given type
    * @tparam A the type of the option value
    */
  def emptyOptionValue[A]: OptionValue[A] = Success(List.empty[A])

  /**
    * Returns a ''CliProcessor'' that always produces an empty value. This
    * is useful in some cases, e.g. to define a processor when one is required,
    * but the concrete value does not matter.
    *
    * @return the ''CliProcessor'' producing empty values
    * @tparam A the type of the option value
    */
  def emptyProcessor[A]: CliProcessor[OptionValue[A]] = constantProcessor(emptyOptionValue)

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
    * values of the given type. This is a special case of a constant processor
    * that operates on the base type of command line arguments.
    *
    * @param first the first value
    * @param items a sequence of additional values
    * @return the ''CliProcessor'' returning this constant ''OptionValue''
    * @tparam A the type of the resulting option value
    */
  def constantOptionValue[A](first: A, items: A*): CliProcessor[OptionValue[A]] =
    constantProcessor(Success(first :: items.toList))

  /**
    * Returns a processor that extracts all values of the specified option key
    * in their basic string representation.
    *
    * @param key the key of the option
    * @return the processor to extract the option values
    */
  def optionValue(key: String): CliProcessor[OptionValue[String]] = CliProcessor(context => {
    val values = context.parameters.parametersMap.getOrElse(key, Nil)
    (Success(values), context.update(context.parameters keyAccessed key))
  }, Some(key))

  /**
    * Returns a processor that can apply a fallback (or default) value to
    * another processor. The resulting processor invokes the first processor.
    * If this yields a defined result, this result is returned. Otherwise, the
    * fallback processor is returned.
    *
    * @param proc         the first processor to be invoked
    * @param fallbackProc the fallback processor
    * @return the resulting processor applying a fallback value
    * @tparam A the type of the option values
    */
  def withFallback[A](proc: CliProcessor[OptionValue[A]], fallbackProc: CliProcessor[OptionValue[A]]):
  CliProcessor[OptionValue[A]] =
    proc flatMap { result =>
      if (result.isFailure || result.get.nonEmpty) constantProcessor(result)
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
  def consoleReaderValue(key: String, password: Boolean): CliProcessor[OptionValue[String]] =
    CliProcessor(context => (Try(List(context.reader.readOption(key, password))), context), Some(key))

  /**
    * Returns a processor that conditionally delegates to other processors.
    * The condition is modelled as ''CliProcessor'' of type ''Try[Boolean]''.
    * This is because a typical use case is to extract one or multiple other
    * command line options and evaluate their values. If this processor yields
    * '''true''', the ''ifProc'' processor is executed. If the condition
    * processor yields '''false''', the ''elseProc'' is executed. In case of a
    * failure, the ''failProc'' is executed.
    *
    * Using this function, it is possible to implement quite complex scenarios.
    * For instance, a program can expect a ''mode'' parameter, and depending on
    * the concrete mode, a number of other parameters become enabled or
    * disabled.
    *
    * @param condProc the processor that defines the condition
    * @param ifProc   the processor to run if the condition is fulfilled
    * @param elseProc the processor to run if the condition is not fulfilled
    * @param failProc the processor to run in case of a failure
    * @return the conditional processor
    * @tparam A the type of the option values
    */
  def conditionalValue[A](condProc: CliProcessor[Try[Boolean]], ifProc: CliProcessor[OptionValue[A]],
                          elseProc: CliProcessor[OptionValue[A]] = emptyProcessor[A],
                          failProc: CliProcessor[OptionValue[A]] = emptyProcessor[A]): CliProcessor[OptionValue[A]] =
    condProc flatMap {
      case Success(value) =>
        if (value) ifProc else elseProc
      case Failure(_) => failProc
    }

  /**
    * Returns a processor that yields a flag whether the ''CliProcessor''
    * passed in extracts a defined value. The resulting processor yields
    * '''true''' if the referenced processor is successful and has at least one
    * option value.
    *
    * @param proc the processor to be checked
    * @tparam A the type of the option values
    * @return the processor checking whether there is a defined value
    */
  def isOptionDefined[A](proc: CliProcessor[OptionValue[A]]): CliProcessor[Try[Boolean]] =
    proc map { optionValue =>
      optionValue map (_.nonEmpty)
    }

  /**
    * Returns a processor that yields a flag whether the command line option
    * with the given key is defined. This is useful for instance to define
    * a condition for the ''conditionalValue'' processor.
    *
    * @param key the key of the option in question
    * @return a processor checking whether this option is defined
    */
  def isDefinedProcessor(key: String): CliProcessor[Try[Boolean]] =
    isOptionDefined(optionValue(key))

  /**
    * Returns a processor that extracts a single option value from the result
    * of the given processor. It is an error if the result contains multiple
    * values; however, an undefined value is accepted.
    *
    * @param proc the processor to be decorated
    * @return the processor extracting the single option value
    */
  def asSingleOptionValue[A](proc: CliProcessor[OptionValue[A]]): CliProcessor[SingleOptionValue[A]] =
    proc map { optionValue =>
      optionValue flatMap { values =>
        if (values.size > 1)
          Failure(paramException(proc.key, s"should have a single value, but has multiple values - $optionValue"))
        else Success(values.headOption)
      }
    }

  /**
    * Returns a processor that enforces an option to have a defined value. If
    * the provided processor yields a ''Try'' with an undefined option, a
    * failure is generated. Otherwise, the option value is unwrapped.
    *
    * @param proc the processor providing the original value
    * @tparam A the result type
    * @return the processor returning a mandatory value
    */
  def asMandatory[A](proc: CliProcessor[SingleOptionValue[A]]): CliProcessor[Try[A]] =
    proc.map(_.flatMap {
      case Some(v) => Success(v)
      case None => Failure(paramException(proc.key, "mandatory option has no value"))
    })

  /**
    * Returns a processor that modifies the result of another processor by
    * applying a mapping function. While mapping is supported by processors in
    * general, this function simplifies this for ''OptionValue'' objects.
    * The mapping function operates on the values collection, and it is called
    * only if the ''Try'' is  successful. The mapping function can throw an
    * exception; this is handled automatically by causing the result to fail.
    *
    * @param proc the processor to be decorated
    * @param f    the mapping function to be applied
    * @tparam A the original result type
    * @tparam B the mapped result type
    * @return the processor applying the mapping function
    */
  def mapped[A, B](proc: CliProcessor[OptionValue[A]])(f: A => B):
  CliProcessor[OptionValue[B]] =
    proc.map(triedResult => triedResult.flatMap(o => paramTry(proc.key)(o.map(f))))

  /**
    * Returns a processor that combines a map operation with applying constant
    * fallback values. If the passed in processor returns a non-empty value,
    * the mapping function is applied to all values. Otherwise, a constant
    * processor is returned that yields the specified fallback values.
    *
    * @param proc               the processor to be mapped
    * @param firstFallback      the first fallback value
    * @param moreFallbackValues further fallback values
    * @param f                  the mapping function to be applied
    * @tparam A the original result type
    * @tparam B the mapped result type
    * @return the processor applying the mapping function with fallbacks
    */
  def mappedWithFallback[A, B](proc: CliProcessor[OptionValue[A]],
                               firstFallback: B, moreFallbackValues: B*)(f: A => B):
  CliProcessor[OptionValue[B]] =
    withFallback(mapped(proc)(f), constantOptionValue(firstFallback, moreFallbackValues: _*))

  /**
    * Returns a processor that converts a command line argument to int
    * numbers. All the string values of the option are converted to
    * numbers including error handling. Undefined values are ignored.
    *
    * @param proc the processor providing the original option value
    * @return the processor converting the values to numbers
    */
  def asIntOptionValue(proc: CliProcessor[OptionValue[String]]):
  CliProcessor[OptionValue[Int]] = mapped(proc)(_.toInt)

  /**
    * Returns a processor that converts a command line argument to boolean
    * values. All the string values of the option are converted to
    * booleans including error handling. Undefined values are ignored.
    *
    * @param proc the processor providing the original option value
    * @return the processor converting the values to booleans
    */
  def asBooleanOptionValue(proc: CliProcessor[OptionValue[String]]):
  CliProcessor[OptionValue[Boolean]] = mapped(proc)({ s =>
    toLower(s) match {
      case "true" => true
      case "false" => false
      case s => throw new IllegalArgumentException(s"'$s' cannot be converted to a boolean")
    }
  })

  /**
    * Returns a processor that converts a command line argument to file paths.
    * The conversion may fail if one of the option values is not a valid path.
    * Undefined values are ignored.
    *
    * @param proc the processor providing the original option value
    * @return the processor converting the value to ''Path'' objects
    */
  def asPathOptionValue(proc: CliProcessor[OptionValue[String]]):
  CliProcessor[OptionValue[Path]] = mapped(proc)({ s => Paths.get(s) })

  /**
    * A generic function to extract a single value from a command line option
    * of a specific type, optionally applying a default value if the option is
    * undefined. If the option has multiple values, a ''Failure'' is generated.
    * This function is a convenient wrapper around the fallback processor that
    * can be obtained via the ''withFallback()'' function. In order to handle
    * the type conversion, a function has to be provided that maps the original
    * string-based processor to one using the target data type.
    *
    * @param key           the key of the option
    * @param fallbackValue an optional fallback value
    * @param fProc         a function to map the type of the processor
    * @tparam A the type of the target option value
    * @return the processor extracting a single option value
    */
  def singleOptionValue[A](key: String, fallbackValue: Option[A] = None)
                          (fProc: CliProcessor[OptionValue[String]] => CliProcessor[OptionValue[A]]):
  CliProcessor[SingleOptionValue[A]] = {
    val procOptValue = fProc(optionValue(key))
    val procFallback = fallbackValue map { fallback =>
      withFallback(procOptValue, constantOptionValue(fallback))
    } getOrElse procOptValue
    asSingleOptionValue(procFallback)
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
  def stringOptionValue(key: String, fallbackValue: Option[String] = None):
  CliProcessor[SingleOptionValue[String]] = {
    singleOptionValue(key, fallbackValue)(identity)
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
    singleOptionValue(key, fallbackValue)(asIntOptionValue)

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
    singleOptionValue(key, fallbackValue)(asBooleanOptionValue)

  /**
    * Returns a processor to extract the single ''Path'' value of a command
    * line option. This works like ''singleOptionValue'', but the result is
    * mapped to a ''Path''.
    *
    * @param key           the key of the option
    * @param fallbackValue an optional fallback value to be set
    * @return the processor extracting the single ''Path'' value of an option
    */
  def pathOptionValue(key: String, fallbackValue: Option[Path] = None):
  CliProcessor[SingleOptionValue[Path]] =
    singleOptionValue(key, fallbackValue)(asPathOptionValue)

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
    * Generates an exception that reports a problem with a specific command
    * line option. Makes sure that such exceptions have a uniform format.
    *
    * @param key     the option key
    * @param message the error message
    * @param cause   an option cause of the error
    * @return the resulting exception
    */
  def paramException(key: String, message: String, cause: Throwable = null): Throwable =
    new IllegalArgumentException(s"$key: ${generateErrorMessage(message, cause)}", cause)

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
    * @param path   the path to the parameters
    * @param ec     the execution context
    * @param system the actor system
    * @return a future with the result of the read operation
    */
  private def readParameterFile(path: String)
                               (implicit ec: ExecutionContext, system: ActorSystem):
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
    * @param files  list with the files to be read
    * @param ec     the execution context
    * @param system the actor system
    * @return a future with the result of the combined read operation
    */
  private def readAllParameterFiles(files: List[String])
                                   (implicit ec: ExecutionContext, system: ActorSystem):
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
