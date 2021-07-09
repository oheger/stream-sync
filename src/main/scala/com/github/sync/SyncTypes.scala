/*
 * Copyright 2018-2021 The Developers Team.
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
      * Returns an alphanumeric ID that uniquely identifies this element in the
      * file system it belongs to. This ID enables direct access to this
      * element. The concrete value depends on the file system of this element.
      * The file system can use non-string values, such as ''Path''s or URIs;
      * in this case, conversions to and from string are necessary.
      *
      * @return the ID of this element
      */
    def id: String

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

    /**
      * Returns an ''Option'' with the original URI of this element. During
      * processing, it can happen that the URI is changed, e.g. if the element
      * name has to be adapted somehow. With this property the original URI can
      * be obtained. It returns ''None'' if there was no change in the URI.
      *
      * @return an ''Option'' for the original URI of this element
      */
    def optOriginalUri: Option[String]

    /**
      * Returns the original URI of this element. This is the URI how it is
      * stored in the folder structure the element lives in. It may have been
      * changed during processing by a sync operation. In this case, the
      * ''relativeUri'' property contains the modified URI while this property
      * can be used to find the original one. If the URI has not been changed,
      * both properties have the same value.
      *
      * @return the original URI of this element
      */
    def originalUri: String = optOriginalUri getOrElse relativeUri
  }

  /**
    * A class representing a file in a file system to be synced.
    *
    * This class defines some additional attributes relevant for files.
    *
    * @param id             the ID of this file
    * @param relativeUri    the relative URI of this file
    * @param level          the level of this file
    * @param lastModified   the time of the last modification
    * @param size           the file size (in bytes)
    * @param optOriginalUri an ''Option'' for the original URI of this element
    */
  case class FsFile(override val id: String,
                    override val relativeUri: String,
                    override val level: Int,
                    lastModified: Instant,
                    size: Long,
                    override val optOriginalUri: Option[String] = None) extends FsElement

  /**
    * A class representing a folder in a file system to be synced.
    *
    * @param id             the ID of this folder
    * @param relativeUri    the relative URI of this folder
    * @param level          the level of this folder
    * @param optOriginalUri an ''Option'' for the original URI of this element
    */
  case class FsFolder(override val id: String,
                      override val relativeUri: String,
                      override val level: Int,
                      override val optOriginalUri: Option[String] = None) extends FsElement

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
    * action is to be executed. This is in most cases the data from the source
    * structure. To reference the element affected by the action in the
    * destination structure, its ID is stored explicitly (if applicable).
    *
    * The operation also has a level which corresponds to the level of the
    * element in the source structure that triggered it. (Note that this does
    * not necessarily correspond to the level of the element associated with
    * the operation.) The level can be used in filter expressions to customize
    * sync behavior.
    *
    * In some constellations it is required to know the URIs of the element
    * affected on both the source and the destination side. The URI in the
    * element is not sufficient here because both structures may use different
    * file names (e.g. if file names are encrypted). Therefore, the operation
    * stores both URIs explicitly.
    *
    * @param element the element that is subject to this operation
    * @param action  the action to be executed on this element
    * @param level   the level of this operation
    * @param srcUri  the URI of the affected element in the source structure
    * @param dstUri  the URI of the affected element in the dest structure
    * @param dstID   the ID of the destination element; required for downloads
    */
  case class SyncOperation(element: FsElement, action: SyncAction, level: Int, srcUri: String, dstUri: String,
                           dstID: String = null)

  /**
    * A data class representing the result of the execution of a sync
    * operation.
    *
    * The ''SyncOperation'' affected is part of the data. An ''Option'' can be
    * used to determine whether the execution was successful: in case of an
    * error, the causing ''Throwable'' is contained.
    *
    * @param op         the sync operation
    * @param optFailure an ''Option'' with the exception in case of a failure
    */
  case class SyncOperationResult(op: SyncOperation, optFailure: Option[Throwable])

  /**
    * A class describing objects storing information about folders during a
    * sync operation.
    *
    * (Sub) folders discovered while iterating over a folder structure have to
    * be recorded and processed later in a defined order. The order is
    * determined by the folder object, but for concrete iteration
    * implementations it may be necessary to store some additional properties.
    * This is handled by a generic ''data'' property that can be used in an
    * arbitrary way.
    *
    * @param folder the associated ''FsFolder'' object
    * @param data   arbitrary data to be stored with the folder
    */
  case class SyncFolderData[T](folder: FsFolder, data: T) extends Ordered[SyncFolderData[T]] {
    override def compare(that: SyncFolderData[T]): Int = {
      val deltaLevel = that.folder.level - folder.level
      if (deltaLevel != 0) deltaLevel
      else folder.relativeUri.compareTo(that.folder.relativeUri)
    }
  }
}
