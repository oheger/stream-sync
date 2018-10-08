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
import com.github.sync.util.UriEncodingHelper

object ElementUriResolver {
  /**
    * Creates a new instance of ''ElementUriResolver'' that resolves against
    * the root URI specified.
    *
    * @param rootUri the root URI
    * @return the new ''ElementUriResolver''
    */
  def apply(rootUri: Uri): ElementUriResolver =
    new ElementUriResolver(UriEncodingHelper.removeTrailing(rootUri.path.toString(),
      UriEncodingHelper.UriSeparator))
}

/**
  * An internally used helper class to resolve the URIs of elements against the
  * root URI of the web dav server.
  *
  * This class does the necessary encoding of relative element URIs and adds
  * the prefix of the root URI.
  *
  * @param rootUriPrefix the prefix of the root URI
  */
class ElementUriResolver private(rootUriPrefix: String) {
  def resolveElementUri(uri: String): Uri = {
    Uri(rootUriPrefix + uri.split(UriEncodingHelper.UriSeparator)
      .map(UriEncodingHelper.encode)
      .mkString(UriEncodingHelper.UriSeparator))
  }
}
