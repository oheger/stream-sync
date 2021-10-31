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

package com.github.sync.cli.oauth

import java.awt.Desktop
import java.net.URI

object BrowserHandler:
  /**
    * Creates a new instance of ''BrowserHandler'' that is initialized with
    * the ''Desktop'' object for the current platform if it is available.
    * Otherwise, an instance is returned that cannot open a browser.
    *
    * @return the newly created ''BrowserHandler''
    */
  def apply(): BrowserHandler =
    val optDesktop = if Desktop.isDesktopSupported then Some(Desktop.getDesktop) else None
    new BrowserHandler(optDesktop)

/**
  * A helper class for the OAuth CLI that supports opening a Web Browser on a
  * specific URL.
  *
  * This class is used for the authorization of the user during the code flow.
  * The responsible command generates the authorization URL and then passes it
  * to this class, so that it can be opened automatically in the browser.
  *
  * @param optDesktop an ''Option'' with the ''Desktop'' object; this is
  *                   undefined if the current platform does not support it
  */
class BrowserHandler(val optDesktop: Option[Desktop]):
  /**
    * Opens the browser at the specified URI. As this functionality may not be
    * available on all platforms, a ''Boolean'' is returned indicating whether
    * the browser could be opened. This allows the caller to implement a
    * fallback; e.g. prompting the user to manually open the browser.
    *
    * @param uri the URI to be opened in the browser
    * @return a flag whether the browser could be opened
    */
  def openBrowser(uri: String): Boolean =
    optDesktop.filter(_.isSupported(Desktop.Action.BROWSE))
      .exists { desktop =>
        try
          desktop.browse(URI.create(uri))
          true
        catch
          case _: Exception => false
      }
