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

package com.github.sync.onedrive

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.stream.scaladsl.Source
import com.github.sync.BaseHttpFsElementSourceSpec
import com.github.sync.SyncTypes.{FsElement, FsFolder}
import com.github.sync.http.HttpRequestActor
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, stubFor, _}
import spray.json.{DeserializationException, JsonParser}

class OneDriveFsElementSourceSpec extends BaseHttpFsElementSourceSpec(ActorSystem("OneDriveFsElementSourceSpec"))
  with OneDriveStubbingSupport {

  import BaseHttpFsElementSourceSpec._
  import OneDriveStubbingSupport._

  /**
    * Creates a test configuration pointing to the mock server.
    *
    * @return the configuration for the test source
    */
  private def createConfig(): OneDriveConfig = createOneDriveConfig(RootPath)

  /**
    * Creates an initialized OneDrive source that can be used by tests.
    *
    * @param config         the test configuration
    * @param startFolderUri an optional URI of a start folder
    * @return the OneDrive source
    */
  private def createTestSource(config: OneDriveConfig, startFolderUri: String = ""): Source[FsElement, Any] =
    OneDriveFsElementSource(config, new SourceFactoryImpl,
      system.actorOf(HttpRequestActor(serverUri(""))), startFolderUri)

  /**
    * Adds stubbing declarations for all test folders. Each folder is mapped to
    * a file defining its representation in the target format.
    *
    * @param config the current OneDrive config
    */
  private def stubTestFolders(config: OneDriveConfig): Unit = {
    stubOneDriveFolderRequest(config, RootPath, "root.json")
    ExpectedElements foreach {
      case FsFolder(relativeUri, _, _) =>
        val fileName = folderFileName(relativeUri, ".json", "")
        val httpUri = Uri(RootPath + encodedFolderUri(relativeUri))
        stubOneDriveFolderRequest(config, httpUri.toString(), fileName)
      case _ => // ignore other elements
    }
  }

  "A DavFsElementSource" should "iterate over a WebDav structure" in {
    val config = createConfig()
    stubTestFolders(config)

    runAndVerifySource(createTestSource(config))
  }

  it should "support setting a start folder URI" in {
    val config = createConfig()
    stubTestFolders(config)
    val StartFolder = createSubFolder(RootFolder, 2)
    val expElements = ExpectedElements filter { elem =>
      elem.relativeUri.startsWith(StartFolder.relativeUri) && elem.relativeUri != StartFolder.relativeUri
    }
    val source = createTestSource(config, StartFolder.relativeUri)

    executeStream(source) should contain theSameElementsAs expElements
  }

  it should "handle a non-JSON response" in {
    stubFor(get(anyUrl())
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withHeader("Content-Type", ContentType)
        .withBody("Not a JSON body")))

    expectFailedFuture[JsonParser.ParsingException](runSource(createTestSource(createConfig())))
  }

  it should "handle an unexpected JSON response" in {
    stubFor(get(anyUrl())
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withHeader("Content-Type", ContentType)
        .withBody("{}")))

    expectFailedFuture[DeserializationException](runSource(createTestSource(createConfig())))
  }
}
