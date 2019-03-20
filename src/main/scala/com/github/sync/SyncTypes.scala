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

import java.time.Instant

import scala.concurrent.Future

/**
  * A module that collects some central data classes and type definitions that
  * are used in multiple places.
  *
  * The object was introduced to have a central location of such central API
  * classes.
  */
object SyncTypes {

  /**
    * A trait representing an element that can occur in the file system.
    *
    * This trait defines some basic properties of such elements that are relevant
    * for the sync algorithm. There are concrete implementations for the items
    * that are handled by the sync engine; those can define additional attributes
    * that may be of interest.
    */
  sealed trait FsElement {
    /**
      * Returns the URI of this file system element relative to the root URI of
      * the source that is synced.
      *
      * @return the relative URI of this element
      */
    def relativeUri: String

    /**
      * Returns the level of this file system element in the structure that is
      * subject of the current sync operation. The level is the distance of this
      * element to the root folder. Elements contained in the root directory have
      * level 1, the elements in a sub folder of the root directory have level 2
      * and so on.
      *
      * @return the level of this element
      */
    def level: Int
  }

  /**
    * A class representing a file in a file system to be synced.
    *
    * This class defines some additional attributes relevant for files.
    *
    * @param relativeUri  the relative URI of this file
    * @param level        the level of this file
    * @param lastModified the time of the last modification
    * @param size         the file size (in bytes)
    */
  case class FsFile(override val relativeUri: String,
                    override val level: Int,
                    lastModified: Instant,
                    size: Long) extends FsElement

  /**
    * A class representing a folder in a file system to be synced.
    *
    * @param relativeUri the relative URI of this folder
    * @param level       the level of this folder
    */
  case class FsFolder(override val relativeUri: String,
                      override val level: Int) extends FsElement

  /**
    * A trait representing an action to be applied on an element during a sync
    * operation.
    */
  sealed trait SyncAction

  /**
    * A special ''SyncAction'' stating that an element should be newly created in
    * the destination structure.
    */
  case object ActionCreate extends SyncAction

  /**
    * A special ''SyncAction'' stating that an element from the source structure
    * should replace the corresponding one in the destination structure.
    */
  case object ActionOverride extends SyncAction

  /**
    * A special ''SyncAction'' stating that an element should be removed from the
    * destination structure.
    */
  case object ActionRemove extends SyncAction

  /**
    * A class that stores all information for a single sync operation.
    *
    * Subject of the operation is an element (a folder or a file), for which an
    * action is to be executed. The operation also has a level which corresponds
    * to the level of the element in the source structure that triggered it.
    * (Note that this does not necessarily correspond to the level of the element
    * associated with the operation.) The level can be used in filter expressions
    * to customize sync behavior.
    *
    * @param element the element that is subject to this operation
    * @param action  the action to be executed on this element
    */
  case class SyncOperation(element: FsElement, action: SyncAction, level: Int)

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

  /**
    * A data class representing an argument supported by a structure to be
    * synced.
    *
    * Depending on the structure, different additional arguments may be required
    * in order to access it correctly; for instance user credentials or
    * additional meta data. This class can be used to describe such an additional
    * argument.
    *
    * @param key          the key of the option that defines the argument
    * @param mandatory    a flag whether this option must be present
    * @param defaultValue an optional default value (only has effect if
    *                     ''mandatory'' is '''true''')
    */
  case class SupportedArgument(key: String, mandatory: Boolean, defaultValue: Option[String] = None)

  /**
    * A trait describing objects storing information about folders during a
    * sync operation.
    *
    * (Sub) folders discovered while iterating over a folder structure have to
    * be recorded and processed later in a defined order. To support this in a
    * generic way, a minimum set of properties must be provided. Based on these
    * properties, an ''Ordering'' implementation is provided. Concrete
    * implementations can enhance the data by use case-specific properties.
    */
  trait SyncFolderData {
    /**
      * Returns the represented ''FsFolder'' object.
      *
      * @return the folder
      */
    def folder: FsFolder

    /**
      * Returns the URI of the represented folder.
      *
      * @return the folder URI
      */
    def uri: String = folder.relativeUri

    /**
      * Returns the level of the represented folder.
      *
      * @return the level of the folder
      */
    def level: Int = folder.level
  }

  /**
    * Provides an implicit ordering for the given type derived from
    * [[SyncFolderData]]. This enables ordering support for all concrete
    * implementations of this trait.
    *
    * @tparam T the type
    * @return the ordering for this type
    */
  implicit def derivedOrdering[T <: SyncFolderData]: Ordering[T] = (x: T, y: T) => {
    val deltaLevel = x.level - y.level
    if (deltaLevel != 0) deltaLevel
    else x.uri.compareTo(y.uri)
  }

  /**
    * A class describing a result generate by a folder iteration function.
    *
    * The iterate function is invoked continuously during iteration over a
    * folder structure. It returns the newly detected elements.
    *
    * @param currentFolder the current folder this result is for
    * @param files         a list with detected files in this folder
    * @param folders       a list with detected sub folders of this folder
    * @tparam F the type used for folder elements
    */
  case class IterateResult[F <: SyncFolderData](currentFolder: FsFolder,
                                                files: List[FsFile],
                                                folders: List[F]) {
    /**
      * Returns a flag whether this result contains some data. This function
      * checks whether there is at least one result element - a file or a
      * folder.
      *
      * @return '''true''' if this object contains result elements; '''false'''
      *         otherwise
      */
    def nonEmpty: Boolean = files.nonEmpty || folders.nonEmpty
  }

  /**
    * Type definition of a function that returns the next folder that is
    * pending in the current iteration. This function is called by the
    * iteration function (see below) when it completed the iteration of a
    * folder; it then has to start with the next folder pending. If there are
    * no more pending folders, result is ''None''.
    */
  type NextFolderFunc[F <: SyncFolderData] = () => Option[F]

  /**
    * Type definition of a function that returns an iteration result that is
    * generated asynchronously. Result is an updated state and the actual
    * iteration result.
    */
  type FutureResultFunc[F <: SyncFolderData, S] = () => Future[(S, IterateResult[F])]

  /**
    * Type definition for the result type of an [[IterateFunc]].
    */
  type IterateFuncResult[F <: SyncFolderData, S] = (S, Option[IterateResult[F]], Option[FutureResultFunc[F, S]])

  /**
    * Type definition of a function that is invoked when iterating over a
    * folder structure.
    *
    * Defining a generic function type for the iteration over folder structures
    * is hard because there are different requirements from specific
    * structures. Some structures allow synchronous access to results while
    * others produce results in background. To support these different
    * scenarios, the return type of the iteration function is a tuple with
    * multiple optional values. An updated status value is returned in any
    * case.
    *
    * If a result is available immediately, the first ''Option'' is defined.
    * The result can then be processed directly, and iteration can continue.
    *
    * If the result requires asynchronous processing, the second ''Option''
    * with a function returning a future result is defined. In this case, the
    * function is invoked, and the result is processed when it becomes
    * available; it is here also possible to provide another updated state.
    * Note that during execution of the asynchronous function no state of the
    * element source can be accessed; this includes invocations of the
    * [[NextFolderFunc]]!
    *
    * If both ''Option'' objects are undefined, this is interpreted as the end
    * of the iteration.
    */
  type IterateFunc[F <: SyncFolderData, S] = (S, NextFolderFunc[F]) =>
    (S, Option[IterateResult[F]], Option[FutureResultFunc[F, S]])

  /**
    * Type definition of a function that is invoked when the iteration over a
    * folder structure is complete. This mechanism allows doing some cleanup at
    * the end of processing.
    */
  type CompletionFunc[S] = S => Unit
}
