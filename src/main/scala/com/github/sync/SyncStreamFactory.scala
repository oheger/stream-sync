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

package com.github.sync

import java.nio.file.Path

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, RunnableGraph, Source}
import akka.util.Timeout
import com.github.sync.SyncTypes.{FsElement, ResultTransformer, StructureType, SupportedArgument, SyncOperation}

import scala.concurrent.{ExecutionContext, Future}

/**
  * A trait that allows creating a sync stream with specific options.
  *
  * This trait defines operations to construct specific components of a stream
  * to sync two structures. Depending on the arguments passed to the sync CLI
  * (or the configuration of the sync process) slight variations of a sync
  * stream can be created. The resulting stream can then be run to actually
  * execute the sync process.
  */
trait SyncStreamFactory {
  /**
    * Type definition for a map with additional arguments that are required for
    * by a sync structure.
    */
  type StructureArgs = Map[String, String]

  /**
    * Type definition for a function that returns a ''Future'' result when
    * provided a map with arguments.
    *
    * Some functions of this service create objects that may require additional
    * arguments to access certain target structures. Such arguments have to be
    * injected at creation time. This is represented by this type.
    */
  type ArgsFunc[A] = StructureArgs => Future[A]

  /**
    * Determines additional arguments supported by the structure specified. For
    * some types of structures additional arguments are required/supported, for
    * instance user credentials to access files on a WebDav server. This
    * function returns a list with information about such arguments based on
    * the passed in URI.
    *
    * @param uri           the URI to the structure
    * @param structureType the type of the structure
    * @param ec            the execution context
    * @return a future with additional arguments supported by the structure
    */
  def additionalArguments(uri: String, structureType: StructureType)
                         (implicit ec: ExecutionContext): Future[Iterable[SupportedArgument]]

  /**
    * Creates a source for a sync stream based on the URI and arguments
    * specified. The concrete source returned by an implementation depends on
    * the URI provided, as different protocols are supported. As additional
    * arguments may be required, result is actually a function that expects
    * a map with arguments and returns a future with the source.
    *
    * @param uri            the URI for the sync source in question
    * @param optTransformer an optional transformer for the structure
    * @param structureType  the type of the structure the source is for
    * @param startFolderUri optional URI of a folder to start the iteration
    * @param ec             the execution context
    * @param system         the actor system
    * @param mat            the object to materialize streams
    * @tparam T the type of the result transformer
    * @return a function to create the sync source
    */
  def createSyncInputSource[T](uri: String, optTransformer: Option[ResultTransformer[T]], structureType: StructureType,
                               startFolderUri: String = "")
                              (implicit ec: ExecutionContext, system: ActorSystem, mat: ActorMaterializer):
  ArgsFunc[Source[FsElement, Any]]

  /**
    * Creates a ''SourceFileProvider'' based on the URI provided. This is
    * needed to apply sync operations against destination structures.
    *
    * @param uri    the URI of the source structure
    * @param ec     the execution context
    * @param system the actor system
    * @param mat    the object to materialize streams
    * @return a function to create the ''SourceFileProvider''
    */
  def createSourceFileProvider(uri: String)(implicit ec: ExecutionContext, system: ActorSystem,
                                            mat: ActorMaterializer):
  ArgsFunc[SourceFileProvider]

  /**
    * Creates a source that produces a sequence of [[SyncOperation]] objects
    * for the specified folder structures. This source compares the files and
    * folders of the given structures and calculates the operations to be
    * applied to synchronize them.
    *
    * @param uriSrc            the URI to the source structure
    * @param optSrcTransformer an optional transformer to the source structure
    * @param uriDst            the URI to the destination structure
    * @param optDstTransformer an optional transformer for the dest structure
    * @param additionalArgs    a map with additional arguments for structures
    * @param ignoreTimeDelta   the time difference between two files to ignore
    * @param ec                the execution context
    * @param system            the actor system
    * @param mat               the object to materialize streams
    * @tparam TSRC the state type of the source transformer
    * @tparam TDST the state type of the destination transformer
    * @return a future with the source
    */
  def createSyncSource[TSRC, TDST](uriSrc: String, optSrcTransformer: Option[ResultTransformer[TSRC]], uriDst: String,
                                   optDstTransformer: Option[ResultTransformer[TDST]], additionalArgs: StructureArgs,
                                   ignoreTimeDelta: Int)
                                  (implicit ec: ExecutionContext, system: ActorSystem,
                                   mat: ActorMaterializer): Future[Source[SyncOperation, NotUsed]]

  /**
    * Creates the flow stage that interprets sync operations and applies them
    * to the destination structure.
    *
    * @param uriDst       the URI to the destination structure
    * @param fileProvider the provider for files from the source structure
    * @param system       the actor system
    * @param mat          the object to materialize streams
    * @param ec           the execution context
    * @param timeout      a timeout when applying a sync operation
    * @return a function to create the stage to process sync operations
    */
  def createApplyStage(uriDst: String, fileProvider: SourceFileProvider)
                      (implicit system: ActorSystem, mat: ActorMaterializer,
                       ec: ExecutionContext, timeout: Timeout):
  ArgsFunc[Flow[SyncOperation, SyncOperation, NotUsed]]

  /**
    * Creates a ''RunnableGraph'' representing the stream for a sync process.
    * The source for the ''SyncOperation'' objects to be processed is passed
    * in. The stream has three sinks that also determine the materialized
    * values of the graph: one sink counts all sync operations that need to be
    * executed; the second sink counts the sync operations that have been
    * processed successfully by the processing flow; the third sink is used to
    * write a log file, which contains all successfully executed sync
    * operations (it is active only if a path to a log file is provided).
    *
    * @param source   the source producing ''SyncOperation'' objects
    * @param flowProc the flow that processes sync operations
    * @param logFile  an optional path to a log file to write
    * @param opFilter a filter on sync operations
    * @param ec       the execution context
    * @return a future with the runnable graph
    */
  def createSyncStream(source: Source[SyncOperation, Any],
                       flowProc: Flow[SyncOperation, SyncOperation, Any],
                       logFile: Option[Path])
                      (opFilter: SyncOperation => Boolean)
                      (implicit ec: ExecutionContext):
  Future[RunnableGraph[Future[(Int, Int)]]]
}
