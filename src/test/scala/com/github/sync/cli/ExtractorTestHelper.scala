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

import com.github.scli.ParameterExtractor.{ExtractionContext, Parameters}
import com.github.scli.{ConsoleReader, DummyConsoleReader, ParameterManager, ParameterModel, ParameterParser}
import com.github.scli.ParameterModel.ParameterKey
import com.github.scli.ParameterParser.{CliElement, OptionElement}

/**
  * A test helper module that provides functionality related to calling
  * ''CliExtractor'' objects with parameters.
  */
object ExtractorTestHelper:

  /**
    * Converts a list of parameter values to CLI elements.
    *
    * @param key    the parameter key for the elements
    * @param values the list of values
    * @param index  the index of this option on the command line
    * @return a list of ''CliElement'' objects
    */
  def toElements(key: ParameterKey, values: Iterable[String], index: Int = 0): Iterable[CliElement] =
    values map (v => OptionElement(key, Some(v), index))

  /**
    * Transforms a map that associates only a single value to a parameter key
    * to a map with a list of values. The result can then be used to construct
    * a ''Parameters'' object.
    *
    * @param map the single-value map
    * @return the map with a collection of values
    */
  def toParametersMap(map: Map[String, String]): Map[String, Iterable[String]] =
    map.map(e => (e._1, List(e._2)))

  /**
    * Converts a string key to a ''ParameterKey'', handling the special input
    * parameter key in a special way.
    *
    * @param key the key to be converted
    * @return the resulting ''ParameterKey''
    */
  def toParameterKey(key: String): ParameterKey =
    key match
      case ParameterParser.InputParameter.key =>
        ParameterParser.InputParameter
      case _ =>
        ParameterKey(key, shortAlias = false)

  /**
    * Transforms a simple map with parameter values to the ''Parameters'' type.
    *
    * @param map the map to be transformed
    * @return the resulting ''Parameters'' map
    */
  def toParameters(map: Map[String, Iterable[String]]): Parameters =
    val paramsMap = map.zipWithIndex map { e =>
      val key = toParameterKey(e._1._1)
      key -> toElements(key, e._1._2, e._2)
    }
    Parameters(paramsMap.toMap, Set.empty)

  /**
    * Transforms a map that associates only a single value to a parameter key
    * to a ''Parameters'' object.
    *
    * @param map the map to be transformed
    * @return the resulting ''Parameters'' map
    */
  def toParametersSingleValues(map: Map[String, String]): Parameters =
    toParameters(toParametersMap(map))

  /**
    * Generates an extraction context for the parameters specified.
    *
    * @param args   the parameters
    * @param reader an optional console reader
    * @return the extraction context
    */
  def toExtractionContext(args: Parameters, reader: ConsoleReader = DummyConsoleReader): ExtractionContext =
    ExtractionContext(args, ParameterModel.EmptyModelContext, reader, ParameterManager.defaultExceptionGenerator, None)

  /**
    * Generates an extraction context for the map of parameters specified.
    *
    * @param argsMap the map of parameters
    * @return the extraction context
    */
  def toExtractionContext(argsMap: Map[String, Iterable[String]]): ExtractionContext =
    toExtractionContext(toParameters(argsMap))

  /**
    * Extracts a set with the names of all parameters that have been accessed
    * during parameter processing.
    *
    * @param context the ''ExtractionContext''
    * @return a set with the names of the keys that have been queried
    */
  def accessedKeys(context: ExtractionContext): Set[String] =
    context.parameters.accessedParameters map (_.key)
