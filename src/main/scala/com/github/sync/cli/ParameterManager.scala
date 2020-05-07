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
import com.github.sync.cli.CliHelpGenerator.{CliHelpContext, InputParameterRef}

import scala.annotation.tailrec
import scala.collection.SortedSet
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

  /** A mapping storing the boolean literals for conversion. */
  private final val BooleanMapping = Map("true" -> true, "false" -> false)

  /**
    * A dummy parameter context object that is used if no current context is
    * available. It contains only dummy values.
    */
  private val DummyParameterContext = ParameterContext(Parameters(Map.empty, Set.empty),
    new CliHelpContext(Map.empty, SortedSet.empty, None, Nil),
    DummyConsoleReader)

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
    * An instance of this class is passed to a ''CliProcessor'' when it is
    * executed. It stores the actual [[Parameters]] plus some helper objects
    * that may be needed to extract meaningful data or provide information
    * about the processor..
    *
    * @param parameters  the parameters to be processed
    * @param helpContext the context to generate help information
    * @param reader      an object to read data from the console
    */
  case class ParameterContext(parameters: Parameters,
                              helpContext: CliHelpContext,
                              reader: ConsoleReader) {
    /**
      * Returns a new ''ParameterContext'' object that was updated with the
      * given ''Parameters'' and help context. All other properties remain
      * constant.
      *
      * @param nextParameters  the ''Parameters'' to replace the current ones
      * @param nextHelpContext the updated help context
      * @return the updated ''ParameterContext''
      */
    def update(nextParameters: Parameters, nextHelpContext: CliHelpContext): ParameterContext =
      copy(parameters = nextParameters, helpContext = nextHelpContext)

    /**
      * Returns a new ''ParameterContext'' object with an updated
      * ''CliHelpContext'', to which the given attribute has been added.
      *
      * @param attr  the attribute key
      * @param value the attribute value
      * @return the updated ''ParameterContext''
      */
    def updateHelpContext(attr: String, value: String): ParameterContext =
      copy(helpContext = helpContext.addAttribute(attr, value))

    /**
      * Returns a ''ParameterContext'' for a conditional update of the
      * ''CliHelpContext''. If the passed in attribute value is defined, the
      * help context is replaced; otherwise, the same ''ParameterContext'' is
      * returned.
      *
      * @param attr     the attribute key
      * @param optValue an ''Option'' with the attribute value
      * @return the updated (or same) ''ParameterContext''
      */
    def updateHelpContextConditionally(attr: String, optValue: Option[String]): ParameterContext =
      optValue map (value => updateHelpContext(attr, value)) getOrElse this
  }

  /**
    * A data class to represent an error during parameter extraction.
    *
    * Instances of this class are generated by ''CliProcessor'' objects if
    * invalid parameters are detected. The properties contain all the
    * information available about the error.
    *
    * @param key     the key of the option with the invalid parameter
    * @param message an error message
    * @param context the current parameter context
    */
  case class ExtractionFailure(key: String,
                               message: String,
                               context: ParameterContext)

  object ParameterExtractionException {
    /**
      * Creates a new instance of ''ParameterExtractionException'' that stores
      * the given extraction failure.
      *
      * @param failure the ''ExtractionFailure'' to be recorded
      * @return the resulting exception instance
      */
    def apply(failure: ExtractionFailure): ParameterExtractionException =
      new ParameterExtractionException(List(failure))

    /**
      * Creates a new instance of ''ParameterExtractionException'' that stores
      * the given extraction failures. The passed in list must contain at least
      * one element; otherwise, an ''IllegalArgumentException'' exception is
      * thrown.
      *
      * @param failures the failures to add to the exception
      * @return the resulting exception instance
      */
    def apply(failures: List[ExtractionFailure]): ParameterExtractionException =
      if (failures.isEmpty)
        throw new IllegalArgumentException("Cannot create ParameterExtractionException without failures")
      else new ParameterExtractionException(failures)

    /**
      * Generates a string from all the given failures that is used as
      * exception message for a ''ParameterExtractionException''.
      *
      * @param failures the list with failures
      * @return the resulting message
      */
    private def generateExceptionMessage(failures: List[ExtractionFailure]): String =
      failures.map(f => s"${f.key}: ${f.message}")
        .mkString(", ")
  }

  /**
    * A special exception class used by ''CliProcessor'' objects to report
    * failures during parameter extraction.
    *
    * An instance can store multiple failures; so information about all invalid
    * options passed to the command line can be accumulated and displayed.
    *
    * @param failures a list with failures
    */
  class ParameterExtractionException private(val failures: List[ExtractionFailure])
    extends Exception(ParameterExtractionException.generateExceptionMessage(failures)) {
    /**
      * Returns a ''ParameterContext'' from the list of failures. (It is
      * unspecified, from which failure the context is obtained.)
      *
      * @return a ''ParameterContext'' from the failures managed by this object
      */
    def parameterContext: ParameterContext =
      failures.head.context
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

    /**
      * Returns a ''CliProcessor'' based on the current one that applies a
      * mapping function to the original result and also modifies the
      * parameter context.
      *
      * @param f the mapping function that is also passed the context
      * @tparam B the result type of the new processor
      * @return the new ''CliProcessor''
      */
    def mapWithContext[B](f: (A, ParameterContext) => (B, ParameterContext)): CliProcessor[B] = {
      val fProc: A => CliProcessor[B] = a => {
        CliProcessor(ctx => f(a, ctx))
      }
      flatMap(fProc)
    }
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
      * ''fallback()'', but creates the fallback processor itself. A
      * description for the value is generated based on the passed in
      * parameters.
      *
      * @param firstValue the first fallback value
      * @param moreValues additional fallback values
      * @return the ''CliProcessor'' supporting these fallback values
      */
    def fallbackValues(firstValue: A, moreValues: A*): CliProcessor[OptionValue[A]] =
      fallback(constantOptionValue(firstValue, moreValues: _*))

    /**
      * Adds a processor as fallback to the managed ''CliProcessor'' that
      * produces the passed in constant values and sets the value description
      * specified. Works the same way as ''fallbackValues()'', but allows more
      * control over the value description.
      *
      * @param optValueDesc the optional description of the value
      * @param firstValue   the first fallback value
      * @param moreValues   additional fallback values
      * @return the ''CliProcessor'' supporting these fallback values
      */
    def fallbackValuesWithDesc(optValueDesc: Option[String], firstValue: A, moreValues: A*):
    CliProcessor[OptionValue[A]] =
      fallback(constantOptionValueWithDesc(optValueDesc, firstValue, moreValues: _*))

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
      * Returns a ''CliProcessor'' that checks whether the number of values
      * passed to the current option corresponds to the multiplicity specified
      * by this function. If too few or too many values are provided, the
      * processor fails with a corresponding exception.
      *
      * @param atLeast the minimum number of values
      * @param atMost  the maximum number of values (a value less than 0 means
      *                that there is no restriction)
      * @return the ''CliProcessor'' enforcing the multiplicity
      */
    def multiplicity(atLeast: Int = 0, atMost: Int = -1): CliProcessor[OptionValue[A]] =
      withMultiplicity(proc, atLeast, atMost)

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

    /**
      * Returns a ''CliProcessor'' that interprets the values of this processor
      * as enum literals by applying the given mapping function. If the mapping
      * function yields a result for a current value, the result becomes the
      * new value; otherwise, the processor fails with an error message.
      *
      * @param fMap the enum mapping function
      * @tparam B the result type of the mapping function
      * @return the ''CliProcessor'' returning enum values
      */
    def toEnum[B](fMap: A => Option[B]): CliProcessor[OptionValue[B]] =
      asEnum(proc)(fMap)
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

    /**
      * Returns a string-based ''CliProcessor'' that returns values converted
      * to lower case.
      *
      * @return the ''CliProcessor'' returning lower case strings
      */
    def toLower: CliProcessor[OptionValue[String]] = asLowerCase(proc)

    /**
      * Returns a string-based ''CliProcessor'' that returns values converted
      * to upper case.
      *
      * @return the ''CliProcessor'' returning upper case strings
      */
    def toUpper: CliProcessor[OptionValue[String]] = asUpperCase(proc)
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
      argMap + (opt -> (optValues :+ value))
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
    * @param a            the constant value to be returned
    * @param optValueDesc an optional description of the default value
    * @tparam A the type of the value
    * @return the ''CliProcessor'' returning this constant value
    */
  def constantProcessor[A](a: A, optValueDesc: Option[String] = None): CliProcessor[A] =
    CliProcessor(context => {
      val nextContext = context.updateHelpContextConditionally(CliHelpGenerator.AttrFallbackValue, optValueDesc)
      (a, nextContext)
    })

  /**
    * Returns a ''CliProcessor'' that returns a constant collection of option
    * values of the given type. This is a special case of a constant processor
    * that operates on the base type of command line arguments. This function
    * automatically generates a description of the default value (based on the
    * values passed in). Use the ''constantOptionValueWithDesc()'' function to
    * define a description manually.
    *
    * @param first the first value
    * @param items a sequence of additional values
    * @return the ''CliProcessor'' returning this constant ''OptionValue''
    * @tparam A the type of the resulting option value
    */
  def constantOptionValue[A](first: A, items: A*): CliProcessor[OptionValue[A]] = {
    val values = first :: items.toList
    val valueDesc = generateValueDescription(values)
    constantProcessor(Success(values), Some(valueDesc))
  }

  /**
    * Returns a ''CliProcessor'' that returns a constant collection of option
    * values of the given type and sets the given value description. This
    * function works like ''constantOptionValue()'', but offers more
    * flexibility regarding the description of the value.
    *
    * @param optValueDesc an ''Option'' with the value description; ''None'' to
    *                     set no description
    * @param first        the first value
    * @param items        a sequence of additional values
    * @tparam A the type of the resulting option value
    * @return the ''CliProcessor'' returning this constant ''OptionValue''
    */
  def constantOptionValueWithDesc[A](optValueDesc: Option[String], first: A, items: A*): CliProcessor[OptionValue[A]] =
    constantProcessor(Success(first :: items.toList), optValueDesc)

  /**
    * Returns a processor that extracts all values of the specified option key
    * in their basic string representation.
    *
    * @param key  the key of the option
    * @param help an optional help text for this option
    * @return the processor to extract the option values
    */
  def optionValue(key: String, help: Option[String] = None): CliProcessor[OptionValue[String]] =
    CliProcessor(context => {
      val values = context.parameters.parametersMap.getOrElse(key, Nil)
      val nextHelpCtx = context.helpContext.addOption(key, help)
      (Success(values), context.update(context.parameters keyAccessed key, nextHelpCtx))
    }, Some(key))

  /**
    * Returns a processor that extracts a value from the input parameters. An
    * input parameter is a parameter that is not the value of an option. The
    * position of the parameter in the command line needs to be specified. The
    * position starts with index 0. It can be negative to count from the end of
    * the command line; for instance, index -1 represents the last input
    * parameter, index -2 the one before the last, etc. If no parameter with
    * this index exists (because the user has entered too few parameters), the
    * processor generates a failure. It is also possible to check whether too
    * many parameters have been provided. This is done by setting the ''last''
    * flag to '''true''' for the input parameter with the maximum index. (This
    * works only if positive index values are used.) The processor then
    * generates a failure if more input values are defined.
    *
    * To support the generation of usage texts, a key and a help text can be
    * assigned to the input parameter. The key can be used in the overview of
    * the command line; the help text is a more detailed description of this
    * parameter.
    *
    * @param index   the index of the input parameter to be extracted
    * @param optKey  an optional key to be assigned to this parameter
    * @param optHelp an optional help text
    * @param last    flag whether this is the last input parameter
    * @return the processor to extract this input value
    */
  def inputValue(index: Int, optKey: Option[String] = None, optHelp: Option[String] = None, last: Boolean = false):
  CliProcessor[OptionValue[String]] =
    inputValues(index, index, optKey, optHelp, last)

  /**
    * Returns a processor that extracts a sequence of values from the input
    * parameters. This function is similar to ''inputValue()'', but the result
    * can have multiple values; it is specified by the first and the last index
    * in the sequence of input parameters. Like for ''inputValue()'', indices
    * are 0-based and can be negative. For instance, by setting ''fromIdx''
    * to 1 and ''toIdx'' to -1, all values except for the first one are
    * extracted.
    *
    * @param fromIdx the start index of the input parameter
    * @param toIdx   the last index of the input parameter
    * @param optKey  an optional key to be assigned to this parameter
    * @param optHelp an optional help text
    * @param last    flag whether this is the last input parameter
    * @return the processor to extract these input values
    */
  def inputValues(fromIdx: Int, toIdx: Int, optKey: Option[String] = None, optHelp: Option[String] = None,
                  last: Boolean = false): CliProcessor[OptionValue[String]] =
    CliProcessor(context => {
      val inputs = context.parameters.parametersMap.getOrElse(InputOption, Nil)

      // handles special negative index values and checks the index range
      def adjustAndCheckIndex(index: Int): Try[Int] = {
        val adjustedIndex = if (index < 0) inputs.size + index
        else index
        if (adjustedIndex >= 0 && adjustedIndex < inputs.size) Success(adjustedIndex)
        else Failure(paramException(context, InputOption, tooFewErrorText(adjustedIndex)))
      }

      def tooFewErrorText(index: Int): String = {
        val details = optKey map (k => s"'$k''") getOrElse s"for index $index"
        s"Too few input arguments; undefined argument $details."
      }

      val result = if (last && inputs.size > toIdx + 1)
        Failure(paramException(context, InputOption, s"Too many input arguments; expected at most ${toIdx + 1}"))
      else
        for {
          firstIndex <- adjustAndCheckIndex(fromIdx)
          lastIndex <- adjustAndCheckIndex(toIdx)
        } yield inputs.slice(firstIndex, lastIndex + 1)
      val helpContext = context.helpContext.addInputParameter(fromIdx, optKey, optHelp)
      (result, context.update(context.parameters keyAccessed InputOption, helpContext))
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
    * @tparam A the type of the option values
    */
  def withFallback[A](proc: CliProcessor[OptionValue[A]], fallbackProc: CliProcessor[OptionValue[A]]):
  CliProcessor[OptionValue[A]] =
    CliProcessor(context => {
      val (result, context2) = proc.run(context)
      if (result.isFailure || result.get.nonEmpty) {
        val helpContext = updateHelpContext(context2.helpContext, List((fallbackProc, None)))
        (result, context2.copy(helpContext = helpContext))
      } else fallbackProc.run(context2)
    }, proc.optKey)

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
    * failure, this processor returns a failure with the same exception.
    *
    * Using this function, it is possible to implement quite complex scenarios.
    * For instance, a program can expect a ''mode'' parameter, and depending on
    * the concrete mode, a number of other parameters become enabled or
    * disabled.
    *
    * Each processor can be assigned a group name; the options extracted by the
    * processors are also associated with this group. When generating help
    * information for the CLI it is then possible to show only help texts for
    * options belonging to specific groups or to indicate that some options are
    * valid only under specific conditions.
    *
    * @param condProc  the processor that defines the condition
    * @param ifProc    the processor to run if the condition is fulfilled
    * @param elseProc  the processor to run if the condition is not fulfilled
    * @param ifGroup   name of the group for the if processor
    * @param elseGroup name of the group for the else processor
    * @return the conditional processor
    * @tparam A the type of the option values
    */
  def conditionalValue[A](condProc: CliProcessor[Try[Boolean]], ifProc: CliProcessor[OptionValue[A]],
                          elseProc: CliProcessor[OptionValue[A]] = emptyProcessor[A],
                          ifGroup: Option[String] = None,
                          elseGroup: Option[String] = None): CliProcessor[OptionValue[A]] =
    CliProcessor(context => {
      val (condResult, context2) = condProc.run(context)
      condResult match {
        case Success(value) =>
          val (activeProc, activeGroup) = if (value) (ifProc, ifGroup) else (elseProc, elseGroup)

          val processorsAndGroups = List((ifProc, ifGroup), (elseProc, elseGroup))
            .filter(_._1 != activeProc)
          val helpContext = updateHelpContext(context2.helpContext, processorsAndGroups)
          val (result, context3) =
            activeProc.run(context2.copy(helpContext = helpContext startGroupConditionally activeGroup))
          (result, context3.copy(helpContext = context3.helpContext.endGroupConditionally(activeGroup)))

        case Failure(exception) =>
          (Failure(exception), context2)
      }
    })

  /**
    * Returns a processor that dispatches from the result of one processor to a
    * group of other processors. The original processor yields a string value
    * which is looked up in a map to find the processor to be executed.
    *
    * This processor is useful for applications that support a mode or command
    * argument. Based on this argument, different command line options are
    * enabled or disabled. The map to be passed to this function in such a
    * scenario has the supported command names as strings and the processors
    * querying the command-specific options as values. If the value returned
    * by the selector processor is not found in the map, the resulting
    * processor fails with an error message. It also fails if the selector
    * processor fails (with the same exception).
    *
    * The keys in the map are also used as group names when invoking the
    * group-specific processors. So the group-specific command line options can
    * be categorized and displayed in group-specific sections in the
    * application's help text.
    *
    * @param groupProc the ''CliProcessor'' that selects the active group
    * @param groupMap  a map with processor for the supported groups
    * @tparam A the result type of the resulting processor
    * @return the processor returning the group value
    */
  def conditionalGroupValue[A](groupProc: CliProcessor[Try[String]],
                               groupMap: Map[String, CliProcessor[Try[A]]]): CliProcessor[Try[A]] =
    CliProcessor(context => {
      val (triedGroup, context2) = groupProc.run(context)
      triedGroup match {
        case Success(group) =>
          groupMap.get(group).
            fold((Try[A](throw paramException(context, groupProc.key, s"Cannot resolve group '$group''")),
              context2)) { proc =>
              val processorsAndGroups = groupMap.toList.filter(entry => entry._2 != proc)
                .map(entry => (entry._2, Some(entry._1)))
              val helpContext = updateHelpContext(context2.helpContext, processorsAndGroups)
              val (result, context3) = proc.run(context2.copy(helpContext = helpContext startGroup group))
              (result, context3.copy(helpContext = context3.helpContext.endGroup()))
            }
        case Failure(exception) =>
          (Failure(exception), context2)
      }
    })

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
    proc.mapWithContext((optionValue, context) => {
      val res = optionValue flatMap { values =>
        if (values.size > 1)
          Failure(paramException(context, proc.key,
            s"should have a single value, but has multiple values - $optionValue"))
        else Success(values.headOption)
      }
      (res, context.updateHelpContext(CliHelpGenerator.AttrMultiplicity, "0..1"))
    })

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
    proc.mapWithContext((optionValue, context) => {
      val res = optionValue.flatMap {
        case Some(v) => Success(v)
        case None => Failure(paramException(context, proc.key, "mandatory option has no value"))
      }
      (res, context.updateHelpContext(CliHelpGenerator.AttrMultiplicity, "1..1"))
    })

  /**
    * Returns a processor that enforces the given multiplicity for the values
    * assigned to the current option. A failure is generated if too few or too
    * many values have been provided. Otherwise, no changes on the values are
    * made.
    *
    * @param proc    the processor providing the original value
    * @param atLeast the minimum number of values
    * @param atMost  the maximum number of values (less than 0 for unlimited)
    * @tparam A the result type
    * @return the processor checking the multiplicity
    */
  def withMultiplicity[A](proc: CliProcessor[OptionValue[A]], atLeast: Int, atMost: Int):
  CliProcessor[OptionValue[A]] =
    proc.mapWithContext((optionValue, context) => {
      val res = optionValue.flatMap { values =>
        if (values.size < atLeast)
          Failure(paramException(context, proc.key, s"option must have at least $atLeast values"))
        else if (values.size > atMost)
          Failure(paramException(context, proc.key, s"option must have at most $atMost values"))
        else Success(values)
      }
      (res, context.updateHelpContext(CliHelpGenerator.AttrMultiplicity,
        Multiplicity(atLeast, atMost).toString))
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
    mappedWithContext(proc) { (a, context) =>
      (f(a), context)
    }

  /**
    * Returns a processor that modifies the result of another processor by
    * applying a mapping function that has access to the current
    * ''ParameterContext''. This function is analogous to ''mapped()'', but it
    * expects a mapping function that is passed in a ''ParameterContext'' and
    * returns an updated one.
    *
    * @param proc the processor to be decorated
    * @param f    the mapping function to be applied
    * @tparam A the original result type
    * @tparam B the mapped result type
    * @return the processor applying the mapping function
    */
  def mappedWithContext[A, B](proc: CliProcessor[OptionValue[A]])(f: (A, ParameterContext) => (B, ParameterContext)):
  CliProcessor[OptionValue[B]] =
    proc.mapWithContext { (triedResult, context) => {
      val mappedResult = triedResult.map(o => {
        val mappingResult = o.foldRight((context, List.empty[B], List.empty[ExtractionFailure])) { (a, t) =>
          paramTry(t._1, proc.key)(f(a, t._1)) match {
            case Success((b, nextCtx)) =>
              (nextCtx, b :: t._2, t._3)
            case f@Failure(_) =>
              (t._1, t._2, collectErrorMessages(f) ::: t._3)
          }
        }
        if (mappingResult._3.nonEmpty) {
          val errMsg = mappingResult._3.map(_.message).mkString(", ")
          (Failure[List[B]](ParameterExtractionException(mappingResult._3.head.copy(message = errMsg))), context)
        } else (Success(mappingResult._2), mappingResult._1)
      })

      mappedResult match {
        case Success(res) => res
        case Failure(exception) =>
          (Failure(exception), context)
      }
    }
    }

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
  CliProcessor[OptionValue[Boolean]] = asEnum(asLowerCase(proc))(BooleanMapping.get)

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
    * Returns a processor that converts the results of another processor to
    * lower case strings. This can be useful for instance if case insensitive
    * comparisons are needed.
    *
    * @param proc the processor providing the original option value
    * @return the processor converting string values to lower case
    */
  def asLowerCase(proc: CliProcessor[OptionValue[String]]): CliProcessor[OptionValue[String]] =
    mapped(proc)(toLower)

  /**
    * Returns a processor that converts the results of another processor to
    * upper case strings. This can be useful for instance if case insensitive
    * comparisons are needed.
    *
    * @param proc the processor providing the original option value
    * @return the processor converting string values to upper case
    */
  def asUpperCase(proc: CliProcessor[OptionValue[String]]): CliProcessor[OptionValue[String]] =
    mapped(proc)(_.toUpperCase(Locale.ROOT))

  /**
    * Returns a processor that accepts a number of literals and maps them to
    * enum constants. The resulting processor passes the results of the passed
    * in processor to a mapping function. If the function yields a result, it
    * is used as resulting value; otherwise, the processor returns a failure.
    *
    * @param proc the processor providing the original option value
    * @param fMap the function that maps enum values
    * @tparam A the value type of the original processor
    * @tparam B the value type of the resulting processor
    * @return the processor doing the enum mapping
    */
  def asEnum[A, B](proc: CliProcessor[OptionValue[A]])(fMap: A => Option[B]): CliProcessor[OptionValue[B]] =
    mappedWithContext(proc) { (res, context) =>
      fMap(res) match {
        case Some(value) => (value, context)
        case None => throw paramException(context, proc.key, s"Invalid enum value: $res")
      }
    }

  /**
    * Checks whether all parameters passed via the command line have been
    * consumed. This is a test to find out whether invalid parameters have been
    * specified. During parameter extraction, all parameters that have been
    * accessed are marked. If at the end parameters remain that are not marked,
    * this means that the user has specified unknown or superfluous ones. In
    * this case, parameter validation should fail and no action should be
    * executed by the application. In case, this function detects unused
    * parameters, it returns a ''Failure'' with a
    * [[ParameterExtractionException]]; this exception contains failures for
    * all the unused keys found. Otherwise, result is a ''Success'' with the
    * same ''ParameterContext''.
    *
    * @param paramContext the ''ParameterContext'', updated by all extract
    *                     operations
    * @return a ''Try'' with the validated ''ParameterContext''
    */
  def checkParametersConsumed(paramContext: ParameterContext): Try[ParameterContext] =
    if (paramContext.parameters.allKeysAccessed) Success(paramContext)
    else {
      val failures = paramContext.parameters.notAccessedKeys map { key =>
        ExtractionFailure(key, "Unexpected parameter", paramContext)
      }
      Failure(ParameterExtractionException(failures.toList))
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
  def createRepresentationN[T](components: Try[_]*)(creator: => T): Try[T] = {
    val failures = collectErrorMessages(components: _*)
    if (failures.isEmpty) Success(creator)
    else Failure(ParameterExtractionException(failures))
  }

  /**
    * Creates an object representation from 2 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, T](c1: Try[A], c2: Try[B])(fCreate: (A, B) => T): Try[T] =
    createRepresentationN(c1, c2)(fCreate(c1.get, c2.get))

  /**
    * Creates an object representation from 3 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, T](c1: Try[A], c2: Try[B], c3: Try[C])(fCreate: (A, B, C) => T): Try[T] =
    createRepresentationN(c1, c2, c3)(fCreate(c1.get, c2.get, c3.get))

  /**
    * Creates an object representation from 4 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param c4      component 4
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam D type of component 4
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, D, T](c1: Try[A], c2: Try[B], c3: Try[C], c4: Try[D])
                                         (fCreate: (A, B, C, D) => T): Try[T] =
    createRepresentationN(c1, c2, c3, c4)(fCreate(c1.get, c2.get, c3.get, c4.get))

  /**
    * Creates an object representation from 5 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param c4      component 4
    * @param c5      component 5
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam D type of component 4
    * @tparam E type of component 5
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, D, E, T](c1: Try[A], c2: Try[B], c3: Try[C], c4: Try[D], c5: Try[E])
                                            (fCreate: (A, B, C, D, E) => T): Try[T] =
    createRepresentationN(c1, c2, c3, c4, c5)(fCreate(c1.get, c2.get, c3.get, c4.get, c5.get))

  /**
    * Creates an object representation from 6 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param c4      component 4
    * @param c5      component 5
    * @param c6      component 6
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam D type of component 4
    * @tparam E type of component 5
    * @tparam F type of component 6
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, D, E, F, T](c1: Try[A], c2: Try[B], c3: Try[C], c4: Try[D], c5: Try[E],
                                                c6: Try[F])
                                               (fCreate: (A, B, C, D, E, F) => T): Try[T] =
    createRepresentationN(c1, c2, c3, c4, c5, c6)(fCreate(c1.get, c2.get, c3.get, c4.get, c5.get, c6.get))

  /**
    * Creates an object representation from 7 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param c4      component 4
    * @param c5      component 5
    * @param c6      component 6
    * @param c7      component 7
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam D type of component 4
    * @tparam E type of component 5
    * @tparam F type of component 6
    * @tparam G type of component 7
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, D, E, F, G, T](c1: Try[A], c2: Try[B], c3: Try[C], c4: Try[D], c5: Try[E],
                                                   c6: Try[F], c7: Try[G])
                                                  (fCreate: (A, B, C, D, E, F, G) => T): Try[T] =
    createRepresentationN(c1, c2, c3, c4, c5, c6, c7)(fCreate(c1.get, c2.get, c3.get, c4.get, c5.get, c6.get,
      c7.get))

  /**
    * Creates an object representation from 8 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param c4      component 4
    * @param c5      component 5
    * @param c6      component 6
    * @param c7      component 7
    * @param c8      component 8
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam D type of component 4
    * @tparam E type of component 5
    * @tparam F type of component 6
    * @tparam G type of component 7
    * @tparam H type of component 8
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, D, E, F, G, H, T](c1: Try[A], c2: Try[B], c3: Try[C], c4: Try[D], c5: Try[E],
                                                      c6: Try[F], c7: Try[G], c8: Try[H])
                                                     (fCreate: (A, B, C, D, E, F, G, H) => T): Try[T] =
    createRepresentationN(c1, c2, c3, c4, c5, c6, c7, c8)(fCreate(c1.get, c2.get, c3.get, c4.get, c5.get, c6.get,
      c7.get, c8.get))

  /**
    * Creates an object representation from 9 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param c4      component 4
    * @param c5      component 5
    * @param c6      component 6
    * @param c7      component 7
    * @param c8      component 8
    * @param c9      component 9
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam D type of component 4
    * @tparam E type of component 5
    * @tparam F type of component 6
    * @tparam G type of component 7
    * @tparam H type of component 8
    * @tparam I type of component 9
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, D, E, F, G, H, I, T](c1: Try[A], c2: Try[B], c3: Try[C], c4: Try[D], c5: Try[E],
                                                         c6: Try[F], c7: Try[G], c8: Try[H], c9: Try[I])
                                                        (fCreate: (A, B, C, D, E, F, G, H, I) => T): Try[T] =
    createRepresentationN(c1, c2, c3, c4, c5, c6, c7, c8, c9)(fCreate(c1.get, c2.get, c3.get, c4.get, c5.get, c6.get,
      c7.get, c8.get, c9.get))

  /**
    * Creates an object representation from 10 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param c4      component 4
    * @param c5      component 5
    * @param c6      component 6
    * @param c7      component 7
    * @param c8      component 8
    * @param c9      component 9
    * @param c10     component 10
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam D type of component 4
    * @tparam E type of component 5
    * @tparam F type of component 6
    * @tparam G type of component 7
    * @tparam H type of component 8
    * @tparam I type of component 9
    * @tparam J type of component 10
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, D, E, F, G, H, I, J, T](c1: Try[A], c2: Try[B], c3: Try[C], c4: Try[D],
                                                            c5: Try[E], c6: Try[F], c7: Try[G], c8: Try[H],
                                                            c9: Try[I], c10: Try[J])
                                                           (fCreate: (A, B, C, D, E, F, G, H, I, J) => T): Try[T] =
    createRepresentationN(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10)(fCreate(c1.get, c2.get, c3.get, c4.get, c5.get,
      c6.get, c7.get, c8.get, c9.get, c10.get))

  /**
    * Creates an object representation from 11 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param c4      component 4
    * @param c5      component 5
    * @param c6      component 6
    * @param c7      component 7
    * @param c8      component 8
    * @param c9      component 9
    * @param c10     component 10
    * @param c11     component 11
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam D type of component 4
    * @tparam E type of component 5
    * @tparam F type of component 6
    * @tparam G type of component 7
    * @tparam H type of component 8
    * @tparam I type of component 9
    * @tparam J type of component 10
    * @tparam K type of component 11
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, D, E, F, G, H, I, J, K, T](c1: Try[A], c2: Try[B], c3: Try[C], c4: Try[D],
                                                               c5: Try[E], c6: Try[F], c7: Try[G], c8: Try[H],
                                                               c9: Try[I], c10: Try[J], c11: Try[K])
                                                              (fCreate: (A, B, C, D, E, F, G, H, I, J, K) => T):
  Try[T] =
    createRepresentationN(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11)(fCreate(c1.get, c2.get, c3.get, c4.get, c5.get,
      c6.get, c7.get, c8.get, c9.get, c10.get, c11.get))

  /**
    * Creates an object representation from 12 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param c4      component 4
    * @param c5      component 5
    * @param c6      component 6
    * @param c7      component 7
    * @param c8      component 8
    * @param c9      component 9
    * @param c10     component 10
    * @param c11     component 11
    * @param c12     component 12
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam D type of component 4
    * @tparam E type of component 5
    * @tparam F type of component 6
    * @tparam G type of component 7
    * @tparam H type of component 8
    * @tparam I type of component 9
    * @tparam J type of component 10
    * @tparam K type of component 11
    * @tparam L type of component 12
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, D, E, F, G, H, I, J, K, L, T](c1: Try[A], c2: Try[B], c3: Try[C], c4: Try[D],
                                                                  c5: Try[E], c6: Try[F], c7: Try[G], c8: Try[H],
                                                                  c9: Try[I], c10: Try[J], c11: Try[K], c12: Try[L])
                                                                 (fCreate: (A, B, C, D, E, F, G, H, I, J, K, L) => T):
  Try[T] =
    createRepresentationN(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12)(fCreate(c1.get, c2.get, c3.get, c4.get,
      c5.get, c6.get, c7.get, c8.get, c9.get, c10.get, c11.get, c12.get))

  /**
    * Creates an object representation from 13 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param c4      component 4
    * @param c5      component 5
    * @param c6      component 6
    * @param c7      component 7
    * @param c8      component 8
    * @param c9      component 9
    * @param c10     component 10
    * @param c11     component 11
    * @param c12     component 12
    * @param c13     component 13
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam D type of component 4
    * @tparam E type of component 5
    * @tparam F type of component 6
    * @tparam G type of component 7
    * @tparam H type of component 8
    * @tparam I type of component 9
    * @tparam J type of component 10
    * @tparam K type of component 11
    * @tparam L type of component 12
    * @tparam M type of component 13
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, D, E, F, G, H, I, J, K, L, M, T](c1: Try[A], c2: Try[B], c3: Try[C], c4: Try[D],
                                                                     c5: Try[E], c6: Try[F], c7: Try[G], c8: Try[H],
                                                                     c9: Try[I], c10: Try[J], c11: Try[K], c12: Try[L],
                                                                     c13: Try[M])
                                                                    (fCreate: (A, B, C, D, E, F, G, H, I, J, K, L,
                                                                      M) => T):
  Try[T] =
    createRepresentationN(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13)(fCreate(c1.get, c2.get, c3.get,
      c4.get, c5.get, c6.get, c7.get, c8.get, c9.get, c10.get, c11.get, c12.get, c13.get))

  /**
    * Creates an object representation from 14 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param c4      component 4
    * @param c5      component 5
    * @param c6      component 6
    * @param c7      component 7
    * @param c8      component 8
    * @param c9      component 9
    * @param c10     component 10
    * @param c11     component 11
    * @param c12     component 12
    * @param c13     component 13
    * @param c14     component 14
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam D type of component 4
    * @tparam E type of component 5
    * @tparam F type of component 6
    * @tparam G type of component 7
    * @tparam H type of component 8
    * @tparam I type of component 9
    * @tparam J type of component 10
    * @tparam K type of component 11
    * @tparam L type of component 12
    * @tparam M type of component 13
    * @tparam N type of component 14
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, D, E, F, G, H, I, J, K, L, M, N, T](c1: Try[A], c2: Try[B], c3: Try[C], c4: Try[D],
                                                                        c5: Try[E], c6: Try[F], c7: Try[G], c8: Try[H],
                                                                        c9: Try[I], c10: Try[J], c11: Try[K],
                                                                        c12: Try[L], c13: Try[M], c14: Try[N])
                                                                       (fCreate: (A, B, C, D, E, F, G, H, I, J, K, L,
                                                                         M, N) => T):
  Try[T] =
    createRepresentationN(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14)(fCreate(c1.get, c2.get,
      c3.get, c4.get, c5.get, c6.get, c7.get, c8.get, c9.get, c10.get, c11.get, c12.get, c13.get, c14.get))

  /**
    * Creates an object representation from 15 extracted components using a
    * creator function.
    *
    * @param c1      component 1
    * @param c2      component 2
    * @param c3      component 3
    * @param c4      component 4
    * @param c5      component 5
    * @param c6      component 6
    * @param c7      component 7
    * @param c8      component 8
    * @param c9      component 9
    * @param c10     component 10
    * @param c11     component 11
    * @param c12     component 12
    * @param c13     component 13
    * @param c14     component 14
    * @param c15     component 15
    * @param fCreate the creator function
    * @tparam A type of component 1
    * @tparam B type of component 2
    * @tparam C type of component 3
    * @tparam D type of component 4
    * @tparam E type of component 5
    * @tparam F type of component 6
    * @tparam G type of component 7
    * @tparam H type of component 8
    * @tparam I type of component 9
    * @tparam J type of component 10
    * @tparam K type of component 11
    * @tparam L type of component 12
    * @tparam M type of component 13
    * @tparam N type of component 14
    * @tparam O type of component 15
    * @tparam T the type of the object representation
    * @return a ''Try'' with the resulting object
    */
  def createRepresentation[A, B, C, D, E, F, G, H,
    I, J, K, L, M, N, O, T](c1: Try[A], c2: Try[B], c3: Try[C], c4: Try[D], c5: Try[E], c6: Try[F], c7: Try[G],
                            c8: Try[H], c9: Try[I], c10: Try[J], c11: Try[K], c12: Try[L], c13: Try[M], c14: Try[N],
                            c15: Try[O])(fCreate: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) => T):
  Try[T] =
    createRepresentationN(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15)(fCreate(c1.get,
      c2.get, c3.get, c4.get, c5.get, c6.get, c7.get, c8.get, c9.get, c10.get, c11.get, c12.get, c13.get, c14.get,
      c15.get))

  /**
    * Executes the given ''CliProcessor'' on the parameters specified and
    * returns its result and the updated ''Parameters'' object.
    *
    * @param processor     the processor to be executed
    * @param parameters    the current ''Parameters''
    * @param consoleReader the object to read from the console
    * @tparam T the result type of the ''CliProcessor''
    * @return a tuple with the result and the resulting ''ParameterContext''
    */
  def runProcessor[T](processor: CliProcessor[T], parameters: Parameters)
                     (implicit consoleReader: ConsoleReader): (T, ParameterContext) = {
    val context = ParameterContext(parameters,
      new CliHelpContext(Map.empty, SortedSet.empty[InputParameterRef], None, Nil), consoleReader)
    val (result, nextContext) = processor.run(context)
    (result, nextContext)
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
    * @return a ''Try'' of a tuple with the result and the updated
    *         ''ParameterContext''
    */
  def tryProcessor[T](processor: CliProcessor[Try[T]], parameters: Parameters)
                     (implicit consoleReader: ConsoleReader): Try[(T, ParameterContext)] = {
    val (triedRes, next) = runProcessor(processor, parameters)
    triedRes map ((_, next))
  }

  /**
    * Generates a ''Try'' for the given expression that contains a meaningful
    * exception in case of a failure. This function maps the original
    * exception to an ''IllegalArgumentException'' with a message that contains
    * the name of the parameter.
    *
    * @param context the ''ParameterContext''
    * @param key     the parameter key
    * @param f       the expression
    * @tparam T the result type of the expression
    * @return a succeeded ''Try'' with the expression value or a failed ''Try''
    *         with a meaningful exception
    */
  def paramTry[T](context: ParameterContext, key: String)(f: => T): Try[T] =
    Try(f) recoverWith {
      case pex: ParameterExtractionException => Failure(pex)
      case ex => Failure(paramException(context, key, ex.getMessage, ex))
    }

  /**
    * Generates an exception that reports a problem with a specific command
    * line option. These exceptions have a special type.
    *
    * @param context the ''ParameterContext''
    * @param key     the option key
    * @param message the error message
    * @param cause   an option cause of the error
    * @return the resulting exception
    */
  def paramException(context: ParameterContext, key: String, message: String, cause: Throwable = null):
  ParameterExtractionException = {
    val failure = ExtractionFailure(key, generateErrorMessage(message, cause), context)
    ParameterExtractionException(failure)
  }

  /**
    * Updates the given help context to contain all the error messages from the
    * failures provided. For each ''ExtractionFailure'', the error message is
    * added to the corresponding attribute of the option affected. The modified
    * help context can then be used to generate formatted output with all error
    * messages.
    *
    * @param helpContext the help context to be used as base
    * @param failures    a collection with failures during extraction
    * @return the modified help context
    */
  def addFailuresToHelpContext(helpContext: CliHelpContext, failures: Iterable[ExtractionFailure]): CliHelpContext =
    failures.foldLeft(helpContext) { (ctx, failure) =>
      ctx.addOption(failure.key, None)
        .addAttribute(CliHelpGenerator.AttrErrorMessage, failure.message)
    }

  /**
    * Returns a collection containing all extraction failures from the given
    * components. This is used to create an object representation of a group of
    * command line arguments. Only if all components could be extracted
    * successfully, the representation can be created. Otherwise, a list with
    * all errors is returned. The resulting collection is also an indicator
    * whether the representation can be created: if it is empty, there are no
    * errors.
    *
    * @param components the single components
    * @return a collection with ''ExtractionFailure'' extracted from the
    *         components
    */
  private def collectErrorMessages(components: Try[_]*): List[ExtractionFailure] =
    components.foldRight(List.empty[ExtractionFailure]) { (c, list) =>
      c match {
        case Failure(exception: ParameterExtractionException) =>
          exception.failures ::: list
        case Failure(exception) =>
          failureFor(exception) :: list
        case _ => list
      }
    }

  /**
    * Generates an ''ExtractionFailure'' object from an arbitrary exception.
    * As the exception does not contain specific failure information, some
    * fields are initialized with dummy values.
    *
    * @param exception the exception
    * @return the resulting ''ExtractionFailure''
    */
  private def failureFor(exception: Throwable): ExtractionFailure =
    ExtractionFailure(message = exception.getMessage, key = "", context = DummyParameterContext)

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
    * Updates a help context by running some processors against it. This
    * function is typically used by conditional processors that select some
    * processors to be executed from a larger set of processors. The other
    * processors that are not selected still need to be reflected by the help
    * context. This function expects a list of processors and the optional
    * groups they belong to. It runs them against a dummy parameter context, so
    * that the help context is updated, but the original parameter context is
    * not modified.
    *
    * @param helpContext         the current ''CliHelpContext''
    * @param processorsAndGroups a list with processors and their groups
    * @tparam A the result type of the processors
    * @return the updated help context
    */
  private def updateHelpContext[A](helpContext: CliHelpContext,
                                   processorsAndGroups: List[(CliProcessor[A], Option[String])]): CliHelpContext =
    processorsAndGroups
      .foldLeft(helpContext) { (helpCtx, p) =>
        val helpCtxWithGroup = helpCtx startGroupConditionally p._2
        val paramCtx = contextForMetaDataRun(helpCtxWithGroup)
        val (_, nextContext) = p._1.run(paramCtx)
        nextContext.helpContext.endGroupConditionally(p._2)
      }

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

  /**
    * Generates a description of a constant option value based on the concrete
    * value(s).
    *
    * @param values the constant values of this option
    * @tparam A the type of the values
    * @return the resulting value description
    */
  private def generateValueDescription[A](values: List[A]): String =
    values match {
      case h :: Nil => h.toString
      case l => l.mkString("<", ", ", ">")
    }

  /**
    * Returns a ''ParameterContext'' to be used for the invocation of a
    * ''CliProcessor'' if only meta data of the processor is of interest. This
    * context has no parameter values and dummy helper objects. Only the help
    * context is set and will be updated during the run.
    *
    * @param helpContext the ''CliHelpContext''
    * @return the ''ParameterContext'' for the meta data run
    */
  private def contextForMetaDataRun(helpContext: CliHelpContext): ParameterContext =
    ParameterContext(Parameters(Map.empty, Set.empty), helpContext, DummyConsoleReader)
}
