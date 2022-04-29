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

package com.github.sync.stream

import akka.NotUsed
import akka.protobufv3.internal.DescriptorProtos.FileDescriptorSet
import akka.stream.scaladsl.{FileIO, Sink, Source}
import com.github.sync.SyncTypes.{FsElement, SyncAction, SyncOperation}
import com.github.sync.log.{ElementSerializer, SerializationSupport, SerializerStreamHelper}

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * A module providing functionality related to the local state of sync
  * processes.
  *
  * Bidirectional sync processes require information of the local elements from
  * the last process. That way, changes on local elements can be detected,
  * which is a precondition for detecting many types of conflicts. A file with
  * the local state is one input of the sync process; depending on the
  * operations performed, the state is updated during the process.
  *
  * This module offers some type definitions and helper functions for dealing
  * with local state. This includes serialization facilities, but also
  * transformations to apply sync operations on local elements.
  */
private object LocalState:
  object LocalElementState:
    /**
      * A [[SerializationSupport]] instance providing serialization support for
      * elements in the local state.
      */
    given serState(using serElem: SerializationSupport[FsElement]): SerializationSupport[LocalElementState] with
      override def deserialize(parts: IndexedSeq[String]): Try[LocalElementState] =
        serElem.deserialize(parts) flatMap { elem =>
          Try {
            val removed = parts.last == "true"
            LocalElementState(elem, removed)
          }
        }

      extension (state: LocalElementState)
        def serialize(builder: mutable.ArrayBuilder[String]): Unit =
          state.element.serialize(builder)
          builder += state.removed.toString

  /**
    * A data class to represent an element in the local state of a sync
    * process.
    *
    * The representation consists of the element with some additional metadata.
    * Elements that have been removed are still stored in the local state, but
    * marked with a flag. This is necessary to resume interrupted sync
    * processes. (If removed elements were missing in the local state, it would
    * not be clear whether they have been processed in the interrupted process
    * or not.)
    *
    * @param element the actual element
    * @param removed flag whether this element has been removed
    */
  case class LocalElementState(element: FsElement,
                               removed: Boolean)

  /** The extension of a file storing local state information. */
  final val ExtensionLocalState = ".lst"

  /**
    * The extension of a file storing local state information while the sync
    * stream is in progress. This is a temporary file, which is renamed at the
    * end of the stream.
    */
  final val ExtensionLocalStateIP = ExtensionLocalState + ".tmp"

  /**
    * The extension of a file with local state information which is created
    * temporarily while resuming an interrupted sync process.
    */
  final val ExtensionLocalStateResuming = ExtensionLocalState + ".rsm"

  /**
    * An enumeration defining the different types of local state files and
    * their extensions that can be present in the storage folder for a sync
    * stream's local state.
    */
  enum LocalStateFile(val fileExtension: String):
    case Complete extends LocalStateFile(ExtensionLocalState)
    case Interrupted extends LocalStateFile(ExtensionLocalStateIP)
    case Resuming extends LocalStateFile(ExtensionLocalStateResuming)

    /**
      * Constructs the name of the local state file of the represented type for
      * the sync stream with the given name.
      *
      * @param streamName the name of the sync stream
      * @return the name of the file of this type for this sync stream
      */
    def fileName(streamName: String): String = streamName + fileExtension
  end LocalStateFile
  
  /**
    * A class with information about the storage path of the local state of a
    * specific sync stream.
    *
    * Depending on the outcome of the last sync process, the local state can be
    * stored in different files (e.g. a completed sync stream vs an interrupted
    * one). The file names are derived from the name of the sync stream. Based
    * on the specified storage path, the class allows resolving the different
    * types of local state files.
    *
    * ''Note'': The class assumes that the name of the stream contains only
    * valid characters for file names; no escaping or quoting is implemented.
    *
    * @param folder     the path to store local state files
    * @param streamName the name of the current sync stream
    */
  case class LocalStateFolder(folder: Path, streamName: String):
    /**
      * Resolves the path to the local state file of the given type.
      *
      * @param file the file type
      * @return the ''Path'' pointing to this state file for the associated
      *         sync stream
      */
    def resolve(file: LocalStateFile): Path = folder.resolve(fileName(file))

    /**
      * Renames a local state file of one type to another type. This function
      * is typically used when an operation is completed. Then a temporary file
      * can be promoted to the complete state file.
      *
      * @param sourceFile      the type of the source file
      * @param destinationFile the type of the destination file
      * @return the path of the destination file
      */
    def rename(sourceFile: LocalStateFile, destinationFile: LocalStateFile): Path =
      val source = resolve(sourceFile)
      val destination = resolve(destinationFile)
      Files.move(source, destination)

    /**
      * Renames a local state file to the name representing a completed state.
      * This is a convenience function, since typically the target of a rename
      * operation is the file for the completed state.
      *
      * @param sourceFile the type of the source file
      * @return the path to the file with the completed state
      */
    def promoteToComplete(sourceFile: LocalStateFile): Path = rename(sourceFile, LocalStateFile.Complete)

    /**
      * Returns the name of the state file with the given type for the
      * associated stream.
      *
      * @param fileType the file type
      * @return the name of the file with this type
      */
    private def fileName(fileType: LocalStateFile): String = streamName + fileType.fileExtension
  end LocalStateFolder

  /** A set with the action types that have an affect of the local state. */
  private val ActionsAffectingLocalState = Set(SyncAction.ActionLocalCreate, SyncAction.ActionLocalOverride,
    SyncAction.ActionLocalRemove)

  extension (operation: SyncOperation)

  /**
    * Returns a flag whether this ''SyncOperation'' affects the local
    * element state.
    */
    def affectsLocalState: Boolean = ActionsAffectingLocalState(operation.action)

  /**
    * Returns a ''Sink'' for the updated local element state of a sync stream.
    * The state is stored in the given folder in a file whose name is derived
    * from the stream name. The materialized value of the sink is a ''Future''
    * with the path to this file.
    *
    * @param stateFolder refers to the folder containing state files
    * @param ec          the execution context
    * @return the sink to update the local element state
    */
  def localStateSink(stateFolder: LocalStateFolder)
                    (implicit ec: ExecutionContext): Sink[LocalElementState, Future[Path]] =
    val targetFile = stateFolder.resolve(LocalStateFile.Interrupted)
    FileIO.toPath(targetFile).contramap[LocalElementState](ElementSerializer.serialize)
      .mapMaterializedValue(_.map(_ => targetFile))

  /**
    * Constructs a ''Source'' to load the local state of a sync process. This
    * is actually a quite tricky operation, since the function has to deal with
    * a process that has been interrupted. In this case, only a partial updated
    * state is available. The function then needs to combine the updated state
    * with the original one to come to the final input source. Therefore,
    * result is a ''Future''.
    *
    * @param stateFolder refers to the folder containing state files
    * @param ec          the execution context
    * @return a ''Future'' with the ''Source'' for the local state
    */
  def constructLocalStateSource(stateFolder: LocalStateFolder)
                               (implicit ec: ExecutionContext): Future[Source[FsElement, Any]] = Future {
    val stateFile = stateFolder.resolve(LocalStateFile.Complete)
    if Files.isRegularFile(stateFile) then
      SerializerStreamHelper.createDeserializationSource[LocalElementState](stateFile)
        .filterNot(_.removed)
        .map(_.element)
    else Source.empty
  }
