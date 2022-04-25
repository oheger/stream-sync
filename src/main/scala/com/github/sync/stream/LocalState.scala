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

import akka.stream.scaladsl.{FileIO, Sink}
import com.github.sync.SyncTypes.{FsElement, SyncAction, SyncOperation}
import com.github.sync.log.{ElementSerializer, SerializationSupport}

import java.nio.file.Path
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
  /** The extension of a file storing local state information. */
  final val ExtensionLocalState = ".lst"

  /**
    * The extension of a file storing local state information while the sync
    * stream is in progress. This is a temporary file, which is renamed at the
    * end of the stream.
    */
  final val ExtensionLocalStateIP = ExtensionLocalState + ".tmp"

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
    * @param path       the path where to store local state information
    * @param streamName the name of this sync stream
    * @param ec         the execution context
    * @return the sink to update the local element state
    */
  def localStateSink(path: Path, streamName: String)
                    (implicit ec: ExecutionContext): Sink[LocalElementState, Future[Path]] =
    val targetFile = path.resolve(streamName + ExtensionLocalStateIP)
    FileIO.toPath(targetFile).contramap[LocalElementState](ElementSerializer.serialize)
      .mapMaterializedValue(_.map(_ => targetFile))

