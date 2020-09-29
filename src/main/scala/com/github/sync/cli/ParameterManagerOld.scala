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

import com.github.sync.cli.ParameterExtractorOld._
import com.github.sync.cli.ParameterParserOld.{KeyExtractor, OptionPredicate, ParameterParseException, ParametersMap}

import scala.util.{Failure, Success, Try}

/**
  * This module is the main entry point into this library for command line
  * parsing and processing.
  *
  * ''ParameterManager'' acts as a facade for the other modules offered by this
  * library. It provides higher-level functions that delegate to other services
  * in order to combine multiple steps required to parse and process the
  * command line. These functions should be appropriate for the most use cases;
  * if there are special requirements, the underlying services can be used
  * directly, which enables a higher degree of customization.
  */
object ParameterManagerOld {
  /**
    * Type definition for a function that does the initial parsing of the
    * command line arguments. The function expects the sequence of arguments as
    * input and tries to categorize the single elements into input arguments,
    * options, and their values.
    */
  type ParsingFunc = Seq[String] => Try[ParametersMap]

  /**
    * Returns a ''ParsingFunc'' that is configured with the parameters
    * provided. For missing parameters, meaningful default values are used.
    *
    * @param optionFunc    optional function to detect command line options
    * @param keyExtractor  optional function to extract the keys of options
    * @param optFileOption optional name of an option to read command line
    *                      files
    * @return the configured parsing function
    */
  def parsingFunc(optionFunc: OptionPredicate = null,
                  keyExtractor: KeyExtractor = null,
                  optFileOption: Option[String] = None): ParsingFunc = {
    val theOptionFunc = getOrDefault(optionFunc, ParameterParserOld.DefaultOptionPrefixes.isOptionFunc)
    val theExtractorFunc = getOrDefault(keyExtractor, ParameterParserOld.DefaultOptionPrefixes.extractorFunc)
    args =>
      ParameterParserOld.parseParameters(args, isOptionFunc = theOptionFunc, keyExtractor = theExtractorFunc,
        optFileOption = optFileOption)
  }

  /**
    * Converts a ''CliExtractor'' that always returns a value into one that
    * returns a ''Try'' of this value.
    *
    * @param extractor the original extractor
    * @tparam A the result type of the extractor
    * @return the ''CliExtractor'' that returns a ''Try[A]''
    */
  def wrapTryExtractor[A](extractor: CliExtractor[A]): CliExtractor[Try[A]] =
    extractor.map(Try(_))

  /**
    * The main function for command line processing.
    *
    * This function uses a ''ParsingFunc'' to parse the given sequence of
    * command line arguments and runs a ''CliExtractor'' on the result. A
    * ''Try'' with the result of this extractor and the parameter context is
    * returned. If the extraction process fails, result is a ''Failure'' that
    * contains a
    * [[com.github.sync.cli.ParameterExtractorOld#ParameterExtractionException]].
    * From this exception, all information is available to generate a
    * meaningful error message and usage information. Specifically, the failure
    * messages have already been added to the help context in the parameter
    * context available via the exception.
    *
    * @param args                      the sequence of command line arguments
    * @param extractor                 the ''CliExtractor'' to generate a result
    * @param parser                    an optional custom parsing function
    * @param checkUnconsumedParameters flag whether a check for unexpected
    *                                  parameters should be performed
    * @tparam A the result type of the ''CliExtractor''
    * @return a ''Try'' with the result of the extractor and the parameter
    *         context
    */
  def processCommandLine[A](args: Seq[String], extractor: CliExtractor[Try[A]], parser: ParsingFunc = null,
                            checkUnconsumedParameters: Boolean = true): Try[(A, ParameterContext)] = {
    val theParsingFunc = getOrDefault(parser, parsingFunc())
    for {
      parsedArgs <- parse(args, extractor, theParsingFunc)
      extResult <- extract(parsedArgs, extractor, checkUnconsumedParameters)
    } yield extResult
  }

  /**
    * Implements the parsing step of parameter processing. This function
    * invokes the ''ParsingFunc'' to do the actual parsing. It then does a
    * special exception handling for parse exceptions: Such exceptions are
    * mapped to [[ParameterExtractionException]] exceptions that have a fully
    * initialized parameter context. To obtain the latter, the meta data of
    * the ''CliExtractor'' is retrieved.
    *
    * @param args      the sequence of command line arguments
    * @param extractor the current ''CliExtractor''
    * @param parseFunc the function to handle the parsing
    * @return a ''Try'' with the result of the parse operation
    */
  private def parse(args: Seq[String], extractor: CliExtractor[_], parseFunc: ParsingFunc): Try[ParametersMap] =
    parseFunc(args) recoverWith {
      case e: ParameterParseException =>
        val context = gatherMetaData(extractor, parameters = e.currentParameters)
        val failure = ExtractionFailure(e.fileOption, e.getMessage, context)
        Failure(updateHelpContextWithFailures(List(failure), context))
    }

  /**
    * Handles the extraction of data objects from the command line and
    * optionally checks for unconsumed parameters. In case of failures, it is
    * ensured that all messages are contained in the resulting exception.
    *
    * @param params                    the map with parameters
    * @param extractor                 the extractor
    * @param checkUnconsumedParameters flag whether a check for unexpected
    *                                  parameters should be performed
    * @tparam A the result type of the ''CliExtractor''
    * @return a ''Try'' with the result of the extractor and the parameter
    *         context
    */
  private def extract[A](params: ParametersMap, extractor: CliExtractor[Try[A]], checkUnconsumedParameters: Boolean):
  Try[(A, ParameterContext)] = {
    val (res, context) = runExtractor(extractor, params)(DefaultConsoleReaderOld)
    val triedContext = checkParametersConsumedConditionally(context, checkUnconsumedParameters)
    createRepresentation(res, triedContext) {
      (_, _)
    } recoverWith {
      case e: ParameterExtractionException =>
        Failure(updateHelpContextWithFailures(e.failures, context))
    }
  }

  /**
    * Performs a check for unconsumed parameters if this check is enabled.
    * Otherwise, a success result is returned.
    *
    * @param context the parameter context to check
    * @param enabled flag whether the check is enabled
    * @return a ''Try'' with the checked parameter context
    */
  private def checkParametersConsumedConditionally(context: ParameterContext, enabled: Boolean):
  Try[ParameterContext] =
    if (enabled) checkParametersConsumed(context)
    else Success(context)

  /**
    * Generates an updated help context that contains all the failures of the
    * passed in list. Then the failures are updated to reference the
    * parameter context with the updated help context, and a new exception with
    * these failures is created.
    *
    * @param failures the original list of failures
    * @param context  the most recent parameter context
    * @return an updated exception referencing the new help context
    */
  private def updateHelpContextWithFailures(failures: List[ExtractionFailure], context: ParameterContext):
  ParameterExtractionException = {
    val helpContext = addFailuresToHelpContext(context.helpContext, failures)
    val newContext = context.copy(helpContext = helpContext)
    val newFailures = failures.map(_.copy(context = newContext))
    ParameterExtractionException(newFailures)
  }

  /**
    * Helper function to replace a '''null''' value by a default value.
    *
    * @param value   the value, which can be '''null'''
    * @param default the lazy default value
    * @tparam A the type of the value
    * @return the value if defined; the default value otherwise
    */
  private def getOrDefault[A](value: A, default: => A): A =
    Option(value) getOrElse default
}
