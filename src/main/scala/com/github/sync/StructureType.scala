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

package com.github.sync

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
