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

package com.github.sync.onedrive

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.stream.scaladsl.Source
import com.github.sync.{BaseHttpFsElementSourceSpec, FileTestHelper}
import com.github.sync.SyncTypes.{FsElement, FsFolder}
import com.github.sync.http.HttpRequestActor
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, stubFor, _}
import spray.json.{DeserializationException, JsonParser}

/**
  * Test class for ''OneDriveFsElementSource''.
  */
class OneDriveFsElementSourceSpec extends BaseHttpFsElementSourceSpec(ActorSystem("OneDriveFsElementSourceSpec"))
  with OneDriveStubbingSupport with FileTestHelper {

  import BaseHttpFsElementSourceSpec._
  import com.github.sync.WireMockSupport._
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
    * a file defining its representation in the target format - except for
    * folder 1, which is defined by two files. (OneDrive has a page size; this
    * feature needs to be tested as well.)
    *
    * @param config the current OneDrive config
    */
  private def stubTestFolders(config: OneDriveConfig): Unit = {
    val pagedFolder = folderName(1)
    stubOneDriveFolderRequest("", "root.json")
    ExpectedElements.filterNot(_.relativeUri.contains(pagedFolder)) foreach {
      case FsFolder(null, relativeUri, _, _) =>
        val fileName = folderFileName(relativeUri, ".json", "")
        stubOneDriveFolderRequest(relativeUri, fileName)
      case _ => // ignore other elements
    }

    val pageUri = stubOneDriveFolderRequest("/next/folder/listing.json", "folder1_next.json")
    val pathPagedFolder = Paths.get(getClass.getResource("/__files/" + folderFileName(pagedFolder,
      ".json", "")).toURI)
    val pagedFolderContent = readDataFile(pathPagedFolder).replace("${next.folder}", pageUri)
    stubOneDriveFolderRequestContent("/" + pagedFolder)(bodyString(pagedFolderContent))
  }

  ignore should "iterate over a WebDav structure" in {
    val config = createConfig()
    stubTestFolders(config)

    runAndVerifySource(createTestSource(config))
  }

  ignore should "support setting a start folder URI" in {
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
