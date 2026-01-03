/*
 * Copyright 2018-2026 The Developers Team.
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

package com.github.sync.auth

import com.github.cloudfiles.core.http.Secret
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem

import java.nio.file.{Files, Path}
import scala.concurrent.{ExecutionContext, Future}

/**
  * A trait defining a service that provides a secure storage for credentials.
  *
  * When syncing data to cloud file systems, typically multiple credentials are
  * involved - for the source system, the destination system, maybe also
  * secrets to encrypt/decrypt data. To simplify dealing with credentials, this
  * service implements a secure (encrypted) storage for credentials. The user
  * then only has to provide the master key to decrypt the credentials file,
  * and the credentials can then be obtained from this storage.
  *
  * @tparam CREDENTIALS the type to represent credentials information
  */
trait CredentialsService[CREDENTIALS]:
  /**
    * Loads a file with credentials information from a given path and returns a
    * list with the information extracted from the file.
    *
    * @param path   the location of the file with credentials
    * @param secret the [[Secret]] to decrypt the file
    * @param ec     the execution context
    * @param system the actor system
    * @return a [[Future]] with the credentials that were loaded
    */
  def loadCredentials(path: Path, secret: Secret)
                     (using ec: ExecutionContext, system: ActorSystem): Future[List[CREDENTIALS]]

  /**
    * Loads information about credentials from a file if this file exists.
    * Otherwise, returns an empty list. This function could be used to create a
    * new file if it does not exist yet.
    *
    * @param path   the location of the file with credentials
    * @param secret the [[Secret]] to decrypt the file
    * @param ec     the execution context
    * @param system the actor system
    * @return a [[Future]] with the credentials that were loaded
    */
  def loadCredentialsOrEmpty(path: Path, secret: Secret)
                            (using ec: ExecutionContext, system: ActorSystem): Future[List[CREDENTIALS]] =
    Future:
      Files.isReadable(path)
    .flatMap: fileExists =>
      if fileExists then
        loadCredentials(path, secret)
      else
        Future.successful(Nil)

  /**
    * Stores the given credentials in an encrypted file at the provided path.
    *
    * @param path        the location of the file with credentials
    * @param secret      the [[Secret]] to decrypt the file
    * @param credentials the credentials to write to the file
    * @param ec          the execution context
    * @param system      the actor system
    * @return a [[Future]] with the result of the operation
    */
  def storeCredentials(path: Path, secret: Secret, credentials: Iterable[CREDENTIALS])
                      (using ec: ExecutionContext, system: ActorSystem): Future[Done]
