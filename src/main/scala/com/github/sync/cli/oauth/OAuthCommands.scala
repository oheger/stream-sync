/*
 * Copyright 2018-2022 The Developers Team.
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

import akka.actor.ActorSystem
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthTokenData
import com.github.scli.ConsoleReader
import com.github.sync.cli.oauth.OAuthParameterManager.{InitCommandConfig, LoginCommandConfig, RemoveCommandConfig}
import com.github.sync.oauth.{IDPConfig, OAuthStorageService, OAuthTokenRetrieverService, SyncOAuthStorageConfig}

import scala.concurrent.{ExecutionContext, Future}

object OAuthCommands:
  /**
    * A short cut type definition for a fully parameterized
    * ''OAuthStorageService''.
    */
  type StorageService = OAuthStorageService[SyncOAuthStorageConfig, IDPConfig, Secret, OAuthTokenData]

  /**
    * A short cut type definition for a full parametrized
    * ''OAuthTokenRetrieverService''
    */
  type TokenService = OAuthTokenRetrieverService[IDPConfig, Secret, OAuthTokenData]

  /**
    * A function to be used for printing out text to the console. This is used
    * to prompt the user for input. (By replacing the print function, a test
    * can check the output generated by an operation.)
    */
  type PrintFunc = String => Unit

  /**
    * A default function to print a string. This implementation writes the
    * string directly to the console.
    */
  final val ConsolePrintFunc: PrintFunc = println

/**
  * A trait defining a number of operations to manage OAuth identity providers
  * via the command line.
  *
  * Data about identity providers can be added to the system, removed from it,
  * and access tokens can be obtained using the authorization grant flow.
  */
trait OAuthCommands:

  import OAuthCommands._

  /**
    * Creates a persistent configuration of an IDP based on the passed in
    * parameters.
    *
    * @param config         the configuration of this command
    * @param storageService the storage service
    * @param ec             the execution context
    * @param system         the actor system
    * @return a future with the output generated by this operation
    */
  def initIdp(config: InitCommandConfig, storageService: StorageService)
             (implicit ec: ExecutionContext, system: ActorSystem): Future[String]

  /**
    * Performs a login against a specific IDP using the authorization code flow.
    *
    * The function generates the URL for the authorization request and tries to
    * open the Web Browser at this address. The user then has to login. The
    * resulting authorization code is then entered in the console. With this
    * information a token pair is retrieved from the IDP.
    *
    * @param loginConfig    the configuration of the login command
    * @param storageService the token storage service
    * @param tokenService   the service to retrieve a token
    * @param browserHandler the ''BrowserHandler''
    * @param consoleReader  a reader to prompt the user for input
    * @param printFunc      a function to print messages to the console
    * @param ec             the execution context
    * @param system         the actor system
    * @return a future with the output generated by this operation
    */
  def login(loginConfig: LoginCommandConfig, storageService: StorageService, tokenService: TokenService,
            browserHandler: BrowserHandler, consoleReader: ConsoleReader,
            printFunc: PrintFunc = ConsolePrintFunc)
           (implicit ec: ExecutionContext, system: ActorSystem): Future[String]

  /**
    * Removes data about an IDP from the persistent storage. This also affects
    * the tokens retrieved from this IDP.
    *
    * @param removeConfig   the configuration of the remove command
    * @param storageService the token storage service
    * @param ec             the execution context
    * @param system         the actor system
    * @return a future with the output generated by this operation
    */
  def removeIdp(removeConfig: RemoveCommandConfig, storageService: StorageService)
               (implicit ec: ExecutionContext, system: ActorSystem): Future[String]
