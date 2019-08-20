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

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.stream.{Graph, SourceShape}

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
    * @param relativeUri    the relative URI of this file
    * @param level          the level of this file
    * @param lastModified   the time of the last modification
    * @param size           the file size (in bytes)
    * @param optOriginalUri an ''Option'' for the original URI of this element
    */
  case class FsFile(override val relativeUri: String,
                    override val level: Int,
                    lastModified: Instant,
                    size: Long,
                    override val optOriginalUri: Option[String] = None) extends FsElement

  /**
    * A class representing a folder in a file system to be synced.
    *
    * @param relativeUri    the relative URI of this folder
    * @param level          the level of this folder
    * @param optOriginalUri an ''Option'' for the original URI of this element
    */
  case class FsFolder(override val relativeUri: String,
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
    * action is to be executed. The operation also has a level which corresponds
    * to the level of the element in the source structure that triggered it.
    * (Note that this does not necessarily correspond to the level of the element
    * associated with the operation.) The level can be used in filter expressions
    * to customize sync behavior.
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
    */
  case class SyncOperation(element: FsElement, action: SyncAction, level: Int, srcUri: String, dstUri: String)

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

  /**
    * A class describing a result generate by a folder iteration function.
    *
    * The iterate function is invoked continuously during iteration over a
    * folder structure. It returns the newly detected elements.
    *
    * @param currentFolder the current folder this result is for
    * @param files         a list with detected files in this folder
    * @param folders       a list with detected sub folders of this folder
    * @tparam F the type of data associated with folder elements
    */
  case class IterateResult[F](currentFolder: FsFolder,
                              files: List[FsFile],
                              folders: List[SyncFolderData[F]]) {
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
  type NextFolderFunc[F] = () => Option[SyncFolderData[F]]

  /**
    * Type definition of a function that returns an iteration result that is
    * generated asynchronously. Result is an updated state and the actual
    * iteration result.
    */
  type FutureResultFunc[F, S] = () => Future[(S, IterateResult[F])]

  /**
    * Type definition for the result type of an [[IterateFunc]].
    */
  type IterateFuncResult[F, S] = (S, Option[IterateResult[F]], Option[FutureResultFunc[F, S]])

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
  type IterateFunc[F, S] = (S, NextFolderFunc[F]) =>
    (S, Option[IterateResult[F]], Option[FutureResultFunc[F, S]])

  /**
    * Type definition of a function that is invoked when the iteration over a
    * folder structure is complete. This mechanism allows doing some cleanup at
    * the end of processing.
    */
  type CompletionFunc[S] = S => Unit

  /**
    * A trait describing a transformation that allows adapting the result of an
    * iteration function.
    *
    * This trait is used to integrate orthogonal functionality into the
    * generic mechanism that iterates over folder structures. One example of
    * such functionality is encryption: the elements produced by a source need
    * to be transformed when the structure iterated over is encrypted.
    *
    * A concrete transformer is allowed to use a state. This can be used for
    * instance for performance optimizations if data is cached between multiple
    * invocations. The state is managed by the iteration source.
    *
    * @tparam S the type of the state used by this transformer
    */
  trait ResultTransformer[S] {
    /**
      * Returns an object with the initial state used by this transformer. This
      * is used by an element source to initialize its state field.
      *
      * @return the initial state of this ''ResultTransformer''
      */
    def initialState: S

    /**
      * The transformation function for result objects produced by an element
      * source. If an element source is configured with such an adapt function, it
      * invokes it on each result object it produces; only the processed result
      * is then passed downstream. Note that a concrete function is expected to
      * manipulate existing result elements, but not to filter out existing ones
      * or generate new ones. As the transformation may be complex, it can be
      * done in background; hence the function returns a future.
      *
      * @param result the result object to be transformed
      * @tparam F the type of folder data
      * @return a future with the transformed result
      */
    def transform[F](result: IterateResult[F], state: S): Future[(IterateResult[F], S)]
  }

  /**
    * Trait describing a factory for an element source.
    *
    * An implementation of this trait is passed to places that need a source to
    * iterate over folder structures.
    */
  trait ElementSourceFactory {
    /**
      * Creates a source to iterate over a folder structure.
      *
      * @param initState         the initial iteration state
      * @param initFolder        the initial folder
      * @param optCompletionFunc an optional function to be called at the end
      * @param iterateFunc       the iteration function
      * @tparam F the folder type
      * @tparam S the type of the state
      * @return the newly created source
      */
    def createElementSource[F, S](initState: S, initFolder: SyncFolderData[F],
                                  optCompletionFunc: Option[CompletionFunc[S]] = None)
                                 (iterateFunc: IterateFunc[F, S]):
    Graph[SourceShape[FsElement], NotUsed]
  }

  /**
    * A data class that holds components related to the source structure of a
    * sync process.
    *
    * These components are typically related and might use shared objects for
    * data access. Therefore, they are created together and returned in form of
    * an instance of this class.
    *
    * @param elementSource      the element source for the source structure
    * @param sourceFileProvider the object for accessing files
    * @tparam T the element type of the source
    */
  case class SyncSourceComponents[T](elementSource: Source[T, Any],
                                     sourceFileProvider: SourceFileProvider)

}
