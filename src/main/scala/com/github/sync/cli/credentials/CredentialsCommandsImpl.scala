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

package com.github.sync.cli.credentials

import com.github.cloudfiles.core.http.Secret
import com.github.sync.auth.{CredentialsService, CredentialsServiceImpl}
import org.apache.pekko.actor.ActorSystem

import java.io.{PrintWriter, StringWriter}
import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

/**
  * The default implementation of the [[CredentialsCommands]] trait.
  *
  * This implementation makes use of a [[CredentialsService]] to manage a file
  * with credentials information.
  *
  * @param credentialsService the service to manipulate the credentials file
  */
class CredentialsCommandsImpl(credentialsService: CredentialsService[CredentialsServiceImpl.CredentialEntry] =
                              CredentialsServiceImpl) extends CredentialsCommands:
  override def listCredentials(credentialsFile: Path, secret: Secret)
                              (using ec: ExecutionContext, system: ActorSystem): Future[String] =
    credentialsService.loadCredentials(credentialsFile, secret).map: credentials =>
      generateOutput: writer =>
        writer.println(s"Credentials from file '$credentialsFile':")
        writer.println()
        credentials.map(_.key).sorted.foreach: key =>
          writer.println(s"- $key")

  override def addCredential(credentialsFile: Path,
                             secret: Secret,
                             key: String,
                             value: Secret)
                            (using ec: ExecutionContext, system: ActorSystem): Future[String] =
    for
      oldCredentials <- credentialsService.loadCredentialsOrEmpty(credentialsFile, secret)
      newCredentials = CredentialsServiceImpl.CredentialEntry(key, value) :: oldCredentials.filterNot(_.key == key)
      _ <- credentialsService.storeCredentials(credentialsFile, secret, newCredentials)
    yield
      generateOutput: writer =>
        val message = oldCredentials.find(_.key == key) match
          case Some(_) => s"The value of credential '$key' was replaced."
          case None => s"Credential '$key' was added."
        writer.println(message)
        writer.println(s"File '$credentialsFile' now contains ${newCredentials.size} credential(s).")

  override def getCredential(credentialsFile: Path, secret: Secret, key: String)
                            (using ec: ExecutionContext, system: ActorSystem): Future[String] =
    credentialsService.loadCredentials(credentialsFile, secret) map : credentials =>
      generateOutput: writer =>
        val message = credentials.find(_.key == key).map: entry =>
          s"$key = ${entry.value.secret}"
        .getOrElse(s"Key '$key' not found in file '$credentialsFile'.")
        writer.println(message)

  override def removeCredential(credentialsFile: Path, secret: Secret, key: String)
                               (using ec: ExecutionContext, system: ActorSystem): Future[String] =
    for
      oldCredentials <- credentialsService.loadCredentials(credentialsFile, secret)
      removed <- removeKeyAndUpdateCredentialFile(credentialsFile, secret, key, oldCredentials)
    yield
      generateOutput: writer =>
        val (message, size) = if removed then
          (s"Credential '$key' was removed.", oldCredentials.size - 1)
        else
          (s"Credential '$key' not found.", oldCredentials.size)
        writer.println(message)
        writer.println(s"File '$credentialsFile' now contains $size credential(s).")

  /**
    * Removes a specific key from credentials data and writes the file again if
    * the remove operation was successful. The resulting [[Future]] indicates
    * whether the key was found and removed.
    *
    * @param credentialsFile the path to the credentials file
    * @param secret          the secret to encrypt the file
    * @param key             the key to be removed
    * @param credentials     the existing credentials
    * @param ec              the execution context
    * @param system          the actor system
    * @return a [[Future]] with a flag whether the key was removed
    */
  private def removeKeyAndUpdateCredentialFile(credentialsFile: Path,
                                               secret: Secret,
                                               key: String,
                                               credentials: List[CredentialsServiceImpl.CredentialEntry])
                                              (using ec: ExecutionContext, system: ActorSystem): Future[Boolean] =
    val filteredCredentials = credentials.filterNot(_.key == key)
    if filteredCredentials.size < credentials.size then
      credentialsService.storeCredentials(credentialsFile, secret, filteredCredentials).map(_ => true)
    else
      Future.successful(false)

  /**
    * A helper function to generate the output of a command based on a function
    * that writes to a [[PrintWriter]]. This function captures the text printed
    * to the writer and returns it.
    *
    * @param f the function to generate the output
    * @return the generated text
    */
  private def generateOutput(f: PrintWriter => Unit): String =
    val out = new StringWriter
    val writer = new PrintWriter(out)
    f(writer)
    out.toString
