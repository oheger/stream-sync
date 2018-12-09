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

package com.github.sync.webdav

import akka.http.scaladsl.model.Uri
import com.github.sync.{StructureType, SupportedArgument}

import scala.concurrent.{ExecutionContext, Future}

object DavConfig {
  /**
    * Property for the user name. This is used to authenticate against the
    * WebDav server.
    */
  val PropUser = "user"

  /**
    * Property for the password of the user. This is used to authenticate
    * against the WebDav server.
    */
  val PropPassword = "password"

  /**
    * Property for the name of the WebDav property defining the last modified
    * time of an element. This is optional; if unspecified, the default WebDav
    * property for the last modified time is used.
    */
  val PropModifiedProperty = "modified-property"

  /**
    * Property for the name of the WebDav property that defines a namespace for
    * the property with the last modified time. If this property is defined, in
    * patch requests to the WebDav server to update the modified time of a file
    * this namespace will be used. Note that this property has an effect only
    * if a custom modified property is set.
    */
  val PropModifiedNamespace = "modified-namespace"

  /**
    * Property to determine whether a file to be overridden should be deleted
    * before it is uploaded. This may be necessary for some servers to have a
    * reliable behavior. The value of the property is a string that is
    * interpreted as a boolean value (in terms of ''Boolean.parseBoolean()'').
    */
  val PropDeleteBeforeOverride = "delete-before-override"

  /**
    * Default name of the WebDav property storing the last modified time of an
    * element.
    */
  val DefaultModifiedProperty = "getlastmodified"

  /**
    * Default value for the ''delete-before-override'' property.
    */
  val DefaultDeleteBeforeOverride = "false"

  /**
    * Creates a ''Future'' with a new ''DavConfig'' from the specified
    * arguments. The arguments corresponding to the specified structure type
    * are extracted from the passed in map and returned as a config object. If
    * this fails, a failed future is returned.
    *
    * @param structType the structure type (determines, which arguments are
    *                   read from the map)
    * @param rootUri    the root URI of the WebDav structure
    * @param properties a map with properties
    * @param ec         the execution context
    * @return a ''Future'' with the resulting ''DavConfig''
    */
  def apply(structType: StructureType, rootUri: String,
            properties: Map[String, String])(implicit ec: ExecutionContext):
  Future[DavConfig] = Future {
    DavConfig(rootUri, properties(propName(structType, PropUser)),
      properties(propName(structType, PropPassword)),
      properties.getOrElse(propName(structType, PropModifiedProperty), DefaultModifiedProperty),
      properties get propName(structType, PropModifiedNamespace),
      java.lang.Boolean.parseBoolean(properties(propName(structType, PropDeleteBeforeOverride))))
  }

  /**
    * Returns a collection of ''SupportedArgument'' objects for the given
    * structure type. This information is used to obtain the corresponding
    * options from the command line.
    *
    * @param structType the structure type
    * @return a sequence with ''SupportedArgument'' objects
    */
  def supportedArgumentsFor(structType: StructureType): Iterable[SupportedArgument] =
    List(SupportedArgument(propName(structType, PropUser), mandatory = true),
      SupportedArgument(propName(structType, PropPassword), mandatory = true),
      SupportedArgument(propName(structType, PropModifiedProperty), mandatory = false),
      SupportedArgument(propName(structType, PropModifiedNamespace), mandatory = false),
      SupportedArgument(propName(structType, PropDeleteBeforeOverride), mandatory = true,
        defaultValue = Some(DefaultDeleteBeforeOverride)))

  /**
    * Generates a property name based on the given structure type.
    *
    * @param structType the structure type
    * @param prop       the property name
    * @return the resulting qualified property name
    */
  private def propName(structType: StructureType, prop: String): String =
    structType.configPropertyName(prop)
}

/**
  * A data class collecting all configuration settings required to access a
  * WebDav resource in a sync process.
  *
  * @param rootUri               the root URI to be synced
  * @param user                  the user name
  * @param password              the password
  * @param lastModifiedProperty  name for the ''lastModified'' property
  * @param lastModifiedNamespace namespace to use for the last modified
  *                              property
  * @param deleteBeforeOverride  flag whether a delete operation should be
  *                              issued before a file override
  */
case class DavConfig(rootUri: Uri, user: String, password: String,
                     lastModifiedProperty: String, lastModifiedNamespace: Option[String],
                     deleteBeforeOverride: Boolean)
