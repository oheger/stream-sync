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

import java.util.Locale

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

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
    * Parses the command line arguments and converts them into a map keyed by
    * options.
    *
    * @param args the sequence with command line arguments
    * @param ec   the execution context
    * @return a future with the parsed map of arguments
    */
  def parseParameters(args: Seq[String])(implicit ec: ExecutionContext):
  Future[Map[String, Iterable[String]]] = Future {
    def appendOptionValue(argMap: Map[String, List[String]], opt: String, value: String):
    Map[String, List[String]] = {
      val optValues = argMap.getOrElse(opt, List.empty)
      argMap + (opt -> (value :: optValues))
    }

    @tailrec def doParseParameters(argsList: Seq[String], argsMap: Map[String, List[String]]):
    Map[String, List[String]] = argsList match {
      case opt :: value :: tail if isOption(opt) =>
        doParseParameters(tail, appendOptionValue(argsMap, opt.toLowerCase(Locale.ROOT), value))
      case h :: t if !isOption(h) =>
        doParseParameters(t, appendOptionValue(argsMap, SyncUriOption, h))
      case h :: _ =>
        throw new IllegalArgumentException("Option without value: " + h)
      case Nil =>
        argsMap
    }

    doParseParameters(args.toList, Map.empty)
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
}
