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
      val out = new StringWriter
      val writer = new PrintWriter(out)
      writer.println(s"Credentials from file '$credentialsFile':")
      writer.println()
      credentials.map(_.key).sorted.foreach: key =>
        writer.println(s"- $key")
      out.toString
