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

import java.nio.file.Paths
import java.util.Locale

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Framing, Sink}
import akka.util.ByteString

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
    * Name of an option that defines a parameters file. The file is read, and
    * its content is added to the command line options.
    */
  val FileOption: String = OptionPrefix + "file"

  /**
    * Type definition for an internal map type used during processing of
    * command line arguments.
    */
  private type ParamMap = Map[String, List[String]]

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
}
