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
import java.time.ZoneId

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes.{FsElement, ResultTransformer, SyncOperation}
import com.github.sync.cli.ParameterManager.{CliProcessor, Parameters}
import com.github.sync.local.LocalFsConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * A trait describing a factory for creating the components of a sync stream
  * that are related to the source structure.
  */
trait SourceComponentsFactory {
  /**
    * Creates the ''Source''for iterating over the source structure of the sync
    * process.
    *
    * @param optSrcTransformer an optional transformer for the structure
    * @tparam T the type of the result transformer
    * @return the source for iterating the source structure
    */
  def createSource[T](optSrcTransformer: Option[ResultTransformer[T]]): Source[FsElement, Any]

  /**
    * Creates a ''SourceFileProvider'' to access files in the source
    * structure.
    *
    * @return the ''SourceFileProvider''
    */
  def createSourceFileProvider(): SourceFileProvider
}

/**
  * A trait describing a factory for creating the components of a sync stream
  * that are related to the destination structure.
  */
trait DestinationComponentsFactory {
  /**
    * Creates the ''Source''for iterating over the destination structure of the sync
    * process.
    *
    * @param optDstTransformer an optional transformer for the structure
    * @tparam T the type of the result transformer
    * @return the source for iterating the destination structure
    */
  def createDestinationSource[T](optDstTransformer: Option[ResultTransformer[T]]): Source[FsElement, Any]

  /**
    * Creates a ''Source'' for iterating over the destination structure
    * starting with a given folder. This is needed for some use cases to
    * resolve files in the destination structure; e.g. if folder names are
    * encrypted.
    *
    * @param startFolderUri the URI of the start folder of the iteration
    * @return the source for a partial iteration
    */
  def createPartialSource(startFolderUri: String): Source[FsElement, Any]

  /**
    * Creates the flow stage that interprets sync operations and applies them
    * to the destination structure. In some constellations, no operations
    * should be executed (e.g. in dry-run mode). This can be specified using a
    * boolean flag. In this case, a special flow stage is returned that does
    * not execute any changes, but does the necessary cleanup.
    *
    * @param fileProvider the provider for files from the source structure
    * @param noop         flag whether no operations should be executed
    * @return the stage to process sync operations
    */
  def createApplyStage(fileProvider: SourceFileProvider, noop: Boolean = false):
  Flow[SyncOperation, SyncOperation, NotUsed]
}

object SyncComponentsFactory {
  /**
    * Property for the time zone to be applied to the last-modified timestamps
    * of files encountered on the local FS. This property is optional. If it is
    * not defined, timestamps are obtained directly from the file system
    * without modifications. This is appropriate if the file system stores them
    * in a defined way. If this is not the case (e.g. for a FAT32 file system
    * which stores them in a local time zone), the time zone must be specified
    * explicitly. Otherwise, the comparison of timestamps (which is one
    * criterion to decide whether a file has been changed) is going to fail.
    */
  val PropLocalFsTimeZone = "time-zone"

  /**
    * Property name for the root path of a local file system.
    */
  val PropLocalFsPath = "path"

  /**
    * A trait defining the type of a structure to be synced.
    *
    * The type determines whether a structure acts as source or destination of a
    * sync process. It is passed to some functions that create certain elements
    * to handle sync actions like sources or processing stages.
    *
    * From the parameters passed to a sync process it must be possible to find
    * out which ones apply to the source and to the destination structure. This
    * is done by defining a unique ''name'' property for the structure type.
    * Parameters can then be prefixed by this name to make clear to which
    * structure they apply.
    */
  sealed trait StructureType {
    /**
      * Returns a name of this structure type.
      *
      * @return the name property
      */
    def name: String

    /**
      * Determines the name of a configuration property with the given name for
      * this ''StructureType''. The full property name is determined by prefixing
      * it with the name of this type. In addition, the parameter prefix is
      * prepended.
      *
      * @param property the property name
      * @return the full property name for this source type
      */
    def configPropertyName(property: String): String = s"--$name$property"
  }

  /**
    * A concrete ''StructureType'' representing the source structure.
    */
  case object SourceStructureType extends StructureType {
    override val name: String = "src-"
  }

  /**
    * A concrete ''StructureType'' representing the destination structure.
    */
  case object DestinationStructureType extends StructureType {
    override val name: String = "dst-"
  }

}

/**
  * A factory class for creating the single components of a sync stream based
  * on command line arguments.
  *
  * This factory handles the construction of the parts of the sync stream that
  * depend on the types of the structures to be synced, as identified by the
  * concrete URIs for the source and destination structures.
  *
  * The usage scenario is that the command line arguments have already been
  * pre-processed. The URIs representing the source and destination structures
  * determine, which additional parameters are required to fully define these
  * structures. These parameters are extracted and removed from the
  * ''Parameters'' object. That way a verification of all input parameters is
  * possible.
  */
class SyncComponentsFactory {

  import SyncComponentsFactory._

  /**
    * Creates a factory for creating the stream components related to the
    * source structure of the sync process. The passed in parameters are
    * processed in order to create the factory and updated by removing the
    * parameters consumed.
    *
    * @param uri        the URI defining the source structure
    * @param parameters the object with command line parameters
    * @param system     the actor system
    * @param mat        the object to materialize streams
    * @param ec         the execution context
    * @param timeout    a timeout when applying a sync operation
    * @return updated parameters and the factory for creating source components
    */
  def createSourceComponentsFactory(uri: String, parameters: Parameters)
                                   (implicit system: ActorSystem, mat: ActorMaterializer,
                                    ec: ExecutionContext, timeout: Timeout):
  Future[(Parameters, SourceComponentsFactory)] =
    extractLocalFsConfig(uri, parameters, SourceStructureType)
      .map(t => (t._1, new LocalFsSourceComponentsFactory(t._2)))

  /**
    * Creates a factory for creating the stream components related to the
    * destination structure of the sync process. The passed in parameters are
    * processed in order to create the factory and updated by removing the
    * parameters consumed.
    *
    * @param uri        the URI defining the source structure
    * @param parameters the object with command line parameters
    * @param system     the actor system
    * @param mat        the object to materialize streams
    * @param ec         the execution context
    * @param timeout    a timeout when applying a sync operation
    * @return updated parameters and the factory for creating dest components
    */
  def createDestinationComponentsFactory(uri: String, parameters: Parameters)
                                        (implicit system: ActorSystem, mat: ActorMaterializer,
                                         ec: ExecutionContext, timeout: Timeout):
  Future[(Parameters, DestinationComponentsFactory)] =
    extractLocalFsConfig(uri, parameters, DestinationStructureType)
      .map(t => (t._1, new LocalFsDestinationComponentsFactory(t._2)))

  /**
    * Extracts the configuration for the local file system from the given
    * parameters. Errors are handled, and the map with parameters is updated.
    *
    * @param uri           the URI acting as the root path for the file system
    * @param parameters    the object with command line parameters
    * @param structureType the structure type
    * @param ec            the execution context
    * @return a ''Future'' with the extracted file system configuration
    */
  private def extractLocalFsConfig(uri: String, parameters: Parameters, structureType: StructureType)
                                  (implicit ec: ExecutionContext):
  Future[(Parameters, LocalFsConfig)] = {
    val processor = localFsConfigProcessor(uri, structureType)
    val (triedFsConfig, nextParams) = processor.run(parameters)
    Future.fromTry(triedFsConfig)
      .map((nextParams, _))
  }

  /**
    * Returns a ''CliProcessor'' that extracts the configuration for the local
    * file system from the current command line arguments.
    *
    * @param uri           the URI acting as root path for the file system
    * @param structureType the structure type
    * @return the ''CliProcessor'' for the file system configuration
    */
  private def localFsConfigProcessor(uri: String, structureType: StructureType): CliProcessor[Try[LocalFsConfig]] = {
    val propZoneId = structureType.configPropertyName(PropLocalFsTimeZone)
    ParameterManager.optionalOptionValue(propZoneId)
      .map(triedZoneId => triedZoneId.flatMap(zoneId => ParameterManager.paramTry(propZoneId)(zoneId map ZoneId.of)))
      .map(triedZone => createLocalFsConfig(uri, structureType, triedZone))
  }

  /**
    * Creates a ''LocalFsConfig'' object from the given components. Errors are
    * aggregated in the resulting ''Try''.
    *
    * @param uri           the URI acting as root path for the file system
    * @param structureType the structure type
    * @param triedZone     the tried time zone component
    * @return a ''Try'' with the file system configuration
    */
  private def createLocalFsConfig(uri: String, structureType: StructureType, triedZone: Try[Option[ZoneId]]):
  Try[LocalFsConfig] = {
    val triedPath = ParameterManager.paramTry(structureType.configPropertyName(PropLocalFsPath))(Paths get uri)
    ParameterManager.createRepresentation(triedPath, triedZone) {
      new LocalFsConfig(triedPath.get, triedZone.get)
    }
  }
}
