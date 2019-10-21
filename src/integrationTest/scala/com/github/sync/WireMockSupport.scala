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

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Authorization
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.scalatest.{BeforeAndAfterEach, Suite}

import scala.concurrent.duration.FiniteDuration

object WireMockSupport {
  /** Test user ID. */
  val UserId = "scott"

  /** Test password for user credentials. */
  val Password = "tiger"

  /**
    * Priority for default stubs. These stubs act as catch-all for requests
    * for which no specific stub has been defined.
    */
  val PriorityDefault = 10

  /** Priority for stubs for specific resources. */
  val PrioritySpecific = 1

  /**
    * Type definition of a function that applies authorization information to
    * the given mapping builder. This is used by the stubbing helper functions.
    */
  type AuthFunc = MappingBuilder => MappingBuilder

  /**
    * Constant for an authorization function that adds a Basic Auth header with
    * default user credentials to a request.
    */
  val BasicAuthFunc: AuthFunc = basicAuth

  /**
    * Returns an authorization function that adds an authorization header with
    * the given bearer token to a request.
    *
    * @param token the token
    * @return the authorization function applying this token
    */
  def TokenAuthFunc(token: String): AuthFunc = mappingBuilder =>
    mappingBuilder.withHeader(Authorization.name, equalTo(s"Bearer $token"))

  /** The path to the directory where resource files are located. */
  private val ResourceDir = "src/integrationTest/resources"

  /**
    * Adds a Basic Auth header to the specified mapping builder with the
    * default user credentials.
    *
    * @param mappingBuilder the mapping builder to be extended
    * @return the updated mapping builder
    */
  private def basicAuth(mappingBuilder: MappingBuilder): MappingBuilder =
    mappingBuilder.withBasicAuth(UserId, Password)
}

/**
  * A trait that can be mixed into an integration test spec to get support for
  * a managed WireMock server.
  *
  * The trait sets up a WireMock server and starts and stops it before and
  * after each test. Some helper methods are available, e.g. to generate URIs
  * and for WebDav-specific requests (as simulating a WebDav server is the main
  * use case for this project). The companion object defines some useful
  * constants.
  */
trait WireMockSupport extends BeforeAndAfterEach {
  this: Suite =>

  import WireMockSupport._

  /** The managed WireMock server. */
  private val wireMockServer = new WireMockServer(wireMockConfig()
    .dynamicPort()
    .withRootDirectory(ResourceDir))

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    wireMockServer.start()
    configureFor(wireMockServer.port())
    resetAllRequests()
  }

  override protected def afterEach(): Unit = {
    wireMockServer.stop()
    super.afterEach()
  }

  /**
    * Generates an absolute URI to the managed WireMock server with the path
    * specified.
    *
    * @param path the path of the URI (should start with a slash)
    * @return the absolute URI pointing to the managed WireMock server
    */
  protected def serverUri(path: String): String =
    s"http://localhost:${wireMockServer.port()}$path"

  /**
    * Adds a stubbing declaration for a request to a folder that is served with
    * the file specified.
    *
    * @param uri          the URI of the folder
    * @param responseFile the file to serve the request
    * @param status       the status code to return from the request
    * @param authFunc     the authorization function
    * @param optDelay     an optional delay for this request
    */
  protected def stubFolderRequest(uri: String, responseFile: String,
                                  status: Int = StatusCodes.OK.intValue,
                                  authFunc: AuthFunc = BasicAuthFunc,
                                  optDelay: Option[FiniteDuration] = None): Unit = {
    val reqUri = if (uri.endsWith("/")) uri else uri + "/"
    val delay = optDelay.map(_.toMillis.toInt).getOrElse(0)
    stubFor(authFunc(request("PROPFIND", urlPathEqualTo(reqUri))
      .withHeader("Accept", equalTo("text/xml"))
      .withHeader("Depth", equalTo("1"))
      .willReturn(aResponse()
        .withStatus(status)
        .withFixedDelay(delay)
        .withBodyFile(responseFile))))
  }

  /**
    * Adds a wildcard stubbing that accepts all requests with the proper
    * authorization header and returns a success response.
    *
    * @param authFunc the authorization function
    */
  protected def stubSuccess(authFunc: AuthFunc = BasicAuthFunc): Unit = {
    stubFor(authFunc(any(anyUrl()).atPriority(PriorityDefault))
      .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)
        .withBody("<status>OK</status>")))
  }
}
