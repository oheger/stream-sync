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

import scala.annotation.tailrec
import scala.io.Source
import scala.util.{Failure, Success, Try}

/**
  * A module for parsing command line arguments into options and input values.
  *
  * This module provides a function that expects a sequence of command line
  * arguments as input and tries to generate a map with parameters from it.
  * This function groups the single strings on the command line into option
  * names, option values, and other input parameters. The resulting
  * representation of parameters can then serve as input for the further
  * processing of the command line, e.g. to extract configuration objects out
  * of it.
  */
object ParameterParserOld {
  /**
    * Name of an option that collects the input strings that are no values of
    * options.
    */
  final val InputOption = "input"

  /**
    * An OptionPrefixes object with the default prefix for options. This is
    * used if for a parse operation no explicit functions to recognize options
    * and extract their keys are specified.
    */
  final val DefaultOptionPrefixes = OptionPrefixes("--")

  /**
    * Type definition for the map with resolved parameter values. The array
    * with command line options is transformed in such a map which allows
    * direct access to the value(s) assigned to options.
    */
  type ParametersMap = Map[String, Iterable[String]]

  /**
    * Type definition for a function that allows querying boolean properties on
    * an option key. Functions of this type are used to categorize arguments
    * into input parameters, options, etc.
    */
  type OptionPredicate = String => Boolean

  /**
    * Type definition for a function that extracts the key of an option from a
    * command line argument. Options typically start with a prefix. This
    * function must remove this prefix; it can do some other normalizations as
    * well, e.g. convert the key to lowercase.
    */
  type KeyExtractor = String => String

  object OptionPrefixes {
    /**
      * Returns a new instance of ''OptionPrefixes'' that accepts the prefixes
      * passed in.
      *
      * @param prefixes the supported option prefixes
      * @return the new ''OptionPrefixes'' instance
      */
    def apply(prefixes: String*): OptionPrefixes = {
      new OptionPrefixes(prefixes.toList)
    }
  }

  /**
    * A data class that stores a list with supported prefixes for options.
    *
    * When parsing the command line each item is checked whether it starts with
    * one of the prefixes defined by this class. If so, the item is considered
    * an option.
    *
    * @param prefixes the supported option prefixes
    */
  case class OptionPrefixes private(prefixes: List[String]) {
    /**
      * Stores the prefixes sorted by their lengths. This makes sure that they
      * are processed in correct order, if one prefix starts with another one.
      */
    private val sortedPrefixes = prefixes.sortWith(_.length > _.length)

    /**
      * Returns a function to check whether a command line argument is an
      * option based on the data of this object. The function checks whether
      * the argument starts with one of the prefixes defined for this object.
      *
      * @return a function to check whether an argument is an option
      */
    def isOptionFunc: OptionPredicate =
      key => findPrefix(key).isDefined

    /**
      * Returns a function that extracts an option key from a command line
      * argument. The function checks whether the passed in string starts with
      * one of the prefixes defined for this object. If so, the prefix is
      * removed; otherwise, the string is returned as is.
      *
      * @return the function to extract an option key
      */
    def extractorFunc: KeyExtractor =
      key => findPrefix(key).fold(key)(prefix => key.drop(prefix.length))

    /**
      * Returns an ''Option'' with the prefix that matches the passed in option
      * key.
      *
      * @param key the option key
      * @return an ''Option'' with the prefix the key starts with
      */
    private def findPrefix(key: String): Option[String] =
      sortedPrefixes find key.startsWith
  }

  /**
    * A specialized exception class to report problems during parameter
    * parsing.
    *
    * The parsing process itself is lenient; failures are detected later in the
    * extraction phase. An unrecoverable problem, however, is an exception
    * thrown when reading a parameter file. Such exceptions are converted to
    * exceptions of this type. They transport some more information which is
    * useful when handling errors, e.g. printing help or error information.
    *
    * @param msg               an error message
    * @param cause             the causing exception, typically an ''IOException''
    * @param fileOption        the name of the option to read parameter files
    * @param currentParameters the current parameters parsed so far
    */
  class ParameterParseException(msg: String,
                                cause: Throwable,
                                val fileOption: String,
                                val currentParameters: ParametersMap) extends Exception(msg, cause)

  /**
    * Type definition for an internal map type used during processing of
    * command line arguments.
    */
  private type InternalParamMap = Map[String, List[String]]

  /**
    * Parses the command line arguments and tries to convert them into a map
    * keyed by options. The parsing operation can be customized by specifying
    * some properties. To determine whether an argument is an option with a
    * value, a function is used. This function is invoked for each argument; if
    * it returns '''true''', the following argument is interpreted as the value
    * of this option. The keys of options are obtained by invoking the key
    * extractor function; it is responsible for removing option prefixes.
    *
    * The parsing operation normally succeeds, even if invalid parameters are
    * passed in; this is detected and handled later in the extraction phase.
    * The only exception that can occur is that a parameter file cannot be
    * read (which can happen only if a name for the file option is provided).
    * So if the ''Try'' returned by this function fails, the exception is of
    * type [[ParameterParseException]] and contains further information about
    * the failed read operation.
    *
    * @param args          the sequence with command line arguments
    * @param isOptionFunc  a function to determine whether a command line
    *                      argument is an option that has a value
    * @param keyExtractor  a function to obtain the key of an option
    * @param optFileOption optional name for an option to reference parameter
    *                      files; if defined, such files are read, and their
    *                      content is added to the command line
    * @return a ''Try'' with the parsed map of arguments
    */
  def parseParameters(args: Seq[String],
                      isOptionFunc: OptionPredicate = DefaultOptionPrefixes.isOptionFunc,
                      keyExtractor: KeyExtractor = DefaultOptionPrefixes.extractorFunc,
                      optFileOption: Option[String] = None): Try[ParametersMap] = {
    def appendOptionValue(argMap: InternalParamMap, opt: String, value: String):
    InternalParamMap = {
      val optValues = argMap.getOrElse(opt, List.empty)
      argMap + (opt -> (optValues :+ value))
    }

    @tailrec def doParseParameters(argsList: Seq[String], argsMap: InternalParamMap):
    InternalParamMap = argsList match {
      case opt :: value :: tail if isOptionFunc(opt) =>
        doParseParameters(tail, appendOptionValue(argsMap, keyExtractor(opt), value))
      case h :: t if !isOptionFunc(h) =>
        doParseParameters(t, appendOptionValue(argsMap, InputOption, h))
      case _ :: t =>
        doParseParameters(t, argsMap)
      case Nil =>
        argsMap
    }

    def parseParameterSeq(argList: Seq[String]): InternalParamMap =
      doParseParameters(argList, Map.empty)

    def parseParametersWithFiles(argList: Seq[String], currentParams: InternalParamMap,
                                 processedFiles: Set[String]): Try[InternalParamMap] = Try {
      combineParameterMaps(currentParams, parseParameterSeq(argList))
    } flatMap { argMap =>
      optFileOption match {
        case Some(fileOption) =>
          argMap get fileOption match {
            case None =>
              Success(argMap)
            case Some(files) =>
              val nextArgs = argMap - fileOption
              val filesToRead = files.toSet diff processedFiles
              readAllParameterFiles(filesToRead.toList, fileOption, nextArgs) flatMap { argList =>
                parseParametersWithFiles(argList, nextArgs, processedFiles ++ filesToRead)
              }
          }

        case None =>
          Success(argMap)
      }
    }

    parseParametersWithFiles(args.toList, Map.empty, Set.empty)
  }

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
    * Reads a file with parameters and returns its single lines
    * as a list of strings.
    *
    * @param path the path to the parameters
    * @return a ''Try'' with the result of the read operation
    */
  private def readParameterFile(path: String): Try[List[String]] = Try {
    val source = Source.fromFile(path)
    source.getLines()
      .filter(_.length > 0)
      .toList
  }

  /**
    * Reads all parameter files referenced by the provided list. The arguments
    * they contain are combined to a single sequence of strings. If a read
    * operation fails, the resulting ''Try'' fails with a meaningful exception.
    *
    * @param files             list with the files to be read
    * @param fileOption        the name of the option referencing files
    * @param currentParameters the parameters parsed so far
    * @return a ''Try'' with the result of the combined read operation
    */
  private def readAllParameterFiles(files: List[String], fileOption: String, currentParameters: ParametersMap):
  Try[List[String]] = {
    val triedReads = files map (path => (path, readParameterFile(path)))
    val optError = triedReads.find(_._2.isFailure)
    optError.fold(convertSuccessReads(triedReads)) { t =>
      t._2 recoverWith {
        case e: Exception =>
          Failure(new ParameterParseException(s"Failed to load parameter file ${t._1}", e, fileOption,
            currentParameters))
      }
    }
  }

  /**
    * Converts the list with tried reads to the final result, a ''Try'' of a
    * list of arguments. This function is called if all parameter files could
    * be read successfully.
    *
    * @param triedReads the list with tried reads and file names
    * @return the resulting ''Try'' with a list of arguments
    */
  private def convertSuccessReads(triedReads: List[(String, Try[List[String]])]): Try[List[String]] =
    Try(triedReads.map(_._2.get))
      .map(_.flatten)
}
