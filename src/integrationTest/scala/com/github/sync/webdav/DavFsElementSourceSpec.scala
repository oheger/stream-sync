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

import java.util.concurrent.atomic.AtomicInteger
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.Timeout
import com.github.cloudfiles.core.http.{Secret, UriEncodingHelper}
import com.github.sync.SyncTypes._
import com.github.sync._
import com.github.sync.http.{SyncBasicAuthConfig, HttpBasicAuthActor, HttpExtensionActor, HttpRequestActor}
import com.github.tomakehurst.wiremock.client.WireMock._
import org.xml.sax.SAXException

import scala.concurrent.duration._
import scala.xml.SAXParseException

/**
  * Integration test class for ''DavFsElementSource''.
  */
class DavFsElementSourceSpec() extends BaseHttpFsElementSourceSpec(ActorSystem("DavFsElementSourceSpec"))
  with DavStubbingSupport {

  import BaseHttpFsElementSourceSpec._
  import WireMockSupport._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates a test WebDav source with the settings to access the mock WebDav
    * server.
    *
    * @param modifiedProperty the property for the modified time
    * @param optFactory       an option with the factory to create the source
    * @param optRequestActor  an option with a special request actor
    * @return the test source
    */
  private def createTestSource(modifiedProperty: String = DavConfig.DefaultModifiedProperty,
                               optFactory: Option[SourceFactoryImpl] = None,
                               optRequestActor: Option[ActorRef] = None):
  Source[FsElement, Any] = {
    val config = createDavConfig(modifiedProperty)
    val requestActor = optRequestActor getOrElse createRequestActor(config)
    DavFsElementSource(config, optFactory getOrElse new SourceFactoryImpl, requestActor)
  }

  /**
    * Creates an actor for sending HTTP requests.
    *
    * @param config the DAV config
    * @return the request actor
    */
  private def createRequestActor(config: DavConfig): ActorRef = {
    val httpActor = system.actorOf(HttpRequestActor(serverUri(""), 2))
    system.actorOf(HttpBasicAuthActor(httpActor, config.authConfig.asInstanceOf[SyncBasicAuthConfig]))
  }

  /**
    * Creates a test DAV configuration.
    *
    * @param modifiedProperty the property for the modified time
    * @return the config
    */
  private def createDavConfig(modifiedProperty: String): DavConfig =
    DavConfig(serverUri(RootPath), modifiedProperty, None,
      deleteBeforeOverride = false,
      modifiedProperties = List(modifiedProperty, DavConfig.DefaultModifiedProperty),
      Timeout(10.seconds), authConfig = SyncBasicAuthConfig(UserId, Secret(Password)))

  /**
    * Adds stubbing declarations for all test folders. Each folder is mapped to
    * a file defining its representation in the target format. With the
    * ''suffix'' parameter a specific set of properties files can be selected.
    *
    * @param format defines the file extension (the data format)
    * @param suffix the suffix of the files to represent the test folders
    */
  private def stubTestFolders(format: String, suffix: String = ""): Unit = {
    stubFolderRequest(RootPath, "root" + suffix + format)
    ExpectedElements foreach {
      case FsFolder(_, relativeUri, _, _) =>
        val fileName = folderFileName(relativeUri, format, suffix)
        val httpUri = Uri(RootPath + encodedFolderUri(relativeUri))
        stubFolderRequest(httpUri.toString(), fileName)
      case _ => // ignore other elements
    }
  }

  "A DavFsElementSource" should "iterate over a WebDav structure" in {
    stubTestFolders(".xml")

    runAndVerifySource(createTestSource())
  }

  /**
    * Checks an iteration over a source for which a start folder URI is
    * provided.
    *
    * @param config the DAV configuration to be used
    */
  private def checkIterationWithStartFolderUri(config: DavConfig): Unit = {
    stubTestFolders(".xml")
    val StartFolder = createSubFolder(RootFolder, 2)
    val expElements = ExpectedElements filter { elem =>
      elem.relativeUri.startsWith(StartFolder.relativeUri) && elem.relativeUri != StartFolder.relativeUri
    }
    val source = DavFsElementSource(config, new SourceFactoryImpl, createRequestActor(config),
      startFolderUri = StartFolder.relativeUri)

    executeStream(source) should contain theSameElementsAs expElements
  }

  it should "support setting a start folder URI" in {
    checkIterationWithStartFolderUri(createDavConfig(DavConfig.DefaultModifiedProperty))
  }

  it should "support setting a start folder URI if the root URI ends on a slash" in {
    val orgConfig = createDavConfig(DavConfig.DefaultModifiedProperty)
    val config = orgConfig.copy(rootUri = UriEncodingHelper.withTrailingSeparator(orgConfig.rootUri.toString()))

    checkIterationWithStartFolderUri(config)
  }

  it should "support a custom modified time property" in {
    stubTestFolders(".xml", suffix = "_full")

    runAndVerifySource(createTestSource("Win32LastModifiedTime"))
  }

  it should "handle an absent modified time property" in {
    stubTestFolders(".xml")

    runAndVerifySource(createTestSource("Win32LastModifiedTime"))
  }

  it should "fail processing when receiving a non XML response" in {
    stubFolderRequest(RootPath, "invalidResponse.txt")

    expectFailedFuture[SAXParseException](runSource(createTestSource()))
  }

  it should "shutdown the request actor when done" in {
    stubFolderRequest(RootPath, "folder3.xml")
    val releaseCount = new AtomicInteger
    val requestActor = createRequestActor(createDavConfig(DavConfig.DefaultModifiedProperty))
    val propsRequestActor = Props(new Actor {
      override def receive: Receive = {
        case HttpExtensionActor.Release =>
          releaseCount.incrementAndGet()
        case m => requestActor forward m
      }
    })
    val stubRequestActor = system.actorOf(propsRequestActor)
    val factory = new SourceFactoryImpl
    val source = createTestSource(optFactory = Some(factory), optRequestActor = Some(stubRequestActor))
    futureResult(runSource(Source.fromGraph(source)))

    releaseCount.get() should be(1)
  }

  it should "evaluate the status code from a response" in {
    stubFor(request("PROPFIND", urlPathEqualTo(RootPath + "/"))
      .willReturn(aResponse()
        .withStatus(401)
        .withBodyFile("folder3.xml")))

    val ex = expectFailedFuture[HttpRequestActor.RequestException](runSource(createTestSource()))
    ex.request.request.uri.path.toString() should be(RootPath + "/")
  }

  it should "fail processing if no modified time can be obtained" in {
    stubFolderRequest(RootPath, "folder_no_timestamp.xml")

    expectFailedFuture[SAXException](runSource(createTestSource()))
  }

}
