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

package com.github.sync.webdav

import akka.http.scaladsl.model.Uri
import akka.util.Timeout

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
    * Creates a ''DavConfig'' from the passed in settings.
    *
    * @param rootUri              the root URI of the WebDav structure
    * @param user                 the user name
    * @param password             the password
    * @param optModifiedProperty  an option for the property containing the
    *                             last-modified timestamp
    * @param optModifiedNamespace namespace to use for the last modified
    *                             property
    * @param deleteBeforeOverride flag whether a delete operation should be
    *                             issued before a file override
    * @param timeout              a timeout for requests to the DAV server
    * @return the ''DavConfig'' object
    */
  def apply(rootUri: Uri, user: String, password: String, optModifiedProperty: Option[String],
            optModifiedNamespace: Option[String], deleteBeforeOverride: Boolean,
            timeout: Timeout): DavConfig =
    new DavConfig(rootUri, user, password, optModifiedProperty getOrElse DefaultModifiedProperty,
      optModifiedNamespace, deleteBeforeOverride, createModifiedProperties(optModifiedProperty), timeout)

  /**
    * Generates the list of modified properties. If a custom modified property
    * has been specified, the list consists of this property and the default
    * one. (Because both are checked to obtain the last-modified timestamp of a
    * file.) If there is no custom property, the list contains only the default
    * property.
    *
    * @param optPropModified an option with the custom modified property name
    * @return a list with all modified properties to check
    */
  private def createModifiedProperties(optPropModified: Option[String]): List[String] = {
    val props = List(DefaultModifiedProperty)
    optPropModified match {
      case Some(prop) if prop != DefaultModifiedProperty =>
        prop :: props
      case _ => props
    }
  }
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
  * @param modifiedProperties    a list with properties to be checked to fetch
  *                              the last-modified timestamp
  * @param timeout               a timeout for requests to the DAV server
  */
case class DavConfig(rootUri: Uri, user: String, password: String,
                     lastModifiedProperty: String, lastModifiedNamespace: Option[String],
                     deleteBeforeOverride: Boolean, modifiedProperties: List[String],
                     timeout: Timeout)
