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

package com.github.sync.webdav

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

/**
  * An internally used helper object that generates the content of the request
  * to update a file's last-modified time.
  *
  * After a file has been uploaded to the server, its last-modified time has to
  * be set in a second step. For this purpose a special XML payload has to be
  * created that depends on some properties of the WebDav configuration. This
  * object evaluates the configuration and creates corresponding XML payload.
  */
private object ModifiedTimeRequestFactory {
  /** The placeholder to be replaced by the file modified time. */
  private[webdav] val PlaceholderTime = "${modifiedTime}"

  /** The namespace prefix for the custom modified property. */
  private[webdav] val SyncNsPrefix = "ssync"

  /** The formatter for the last modified time of files. */
  private val FileTimeFormatter =
    DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneId.of("Z"))

  /**
    * Generates a template for requests based on the passed in configuration.
    * This template can be created initially and then used for each request.
    *
    * @param config the configuration of the WebDav server
    * @return the template string
    */
  def requestTemplate(config: DavConfig): String = {
    val (propName, namespace) = config.lastModifiedNamespace match {
      case Some(ns) =>
        (s"$SyncNsPrefix:${config.lastModifiedProperty}", s""" xmlns:$SyncNsPrefix="$ns"""")
      case None =>
        val prefix = if (config.lastModifiedProperty == DavConfig.DefaultModifiedProperty) "D:"
        else ""
        (prefix + config.lastModifiedProperty, "")
    }
    s"""<?xml version="1.0" encoding="utf-8" ?>
<D:propertyupdate xmlns:D="DAV:"$namespace>
  <D:set>
    <D:prop>
      <$propName>$PlaceholderTime</$propName>
    </D:prop>
  </D:set>
</D:propertyupdate>"""
  }

  /**
    * Creates the payload of a request to set the modified time based on the
    * given parameters.
    *
    * @param template     the template as generated by ''requestTemplate()''
    * @param modifiedTime the modified time to be injected
    * @return the payload for the modified time request
    */
  def createModifiedTimeRequest(template: String, modifiedTime: Instant): String = {
    val formattedTime = FileTimeFormatter format modifiedTime
    template.replace(PlaceholderTime, formattedTime)
  }
}
