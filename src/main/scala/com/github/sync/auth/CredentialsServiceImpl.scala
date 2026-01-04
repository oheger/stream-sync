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
import com.github.sync.auth.CredentialsServiceImpl.CredentialEntry
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, JsonFraming, Sink, Source}
import org.apache.pekko.util.ByteString
import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

/**
  * A default implementation of the [[CredentialsService]] trait.
  *
  * This implementation defines a data structure for credentials. It stores
  * encrypted JSON files with a list of such credentials objects.
  */
object CredentialsServiceImpl extends CredentialsService[CredentialEntry]:
  /**
    * A data class representing a credential. An instance has a key that is a
    * unique identifier and - of course - the secret value of the credential.
    *
    * @param key   the key of this object
    * @param value the secret value
    */
  final case class CredentialEntry(key: String,
                                   value: Secret)

  /**
    * An internal representation of a credential entry that is used for JSON
    * serialization. Here, the value is a plain string, so that no special
    * care has to be taken when converting it to JSON.
    *
    * @param key   the key of the entry
    * @param value the secret value of the entry
    */
  private case class CredentialEntryInternal(key: String, value: String)

  /** The separator between two credential entries in the JSON file. */
  private val EntrySeparator = ByteString(",")

  /** The maximum entry of a credentials object in JSON representation. */
  private val MaxObjectLength = 16384

  /** The JSON format for the internal credential representation. */
  private given credentialEntryFormat: RootJsonFormat[CredentialEntryInternal] =
    jsonFormat2(CredentialEntryInternal.apply)

  override def loadCredentials(path: Path, secret: Secret)
                              (using ec: ExecutionContext, system: ActorSystem): Future[List[CredentialEntry]] =
    val source = FileIO.fromPath(path)
    val cryptSource = SecureStorageService.decryptSource(source, secret)
      .via(JsonFraming.objectScanner(MaxObjectLength))
      .map(jsonToEntry)
    val sink = Sink.fold[List[CredentialEntry], CredentialEntry](Nil): (lst, e) =>
      e :: lst
    cryptSource.runWith(sink) map (_.reverse)

  override def storeCredentials(path: Path, secret: Secret, credentials: Iterable[CredentialEntry])
                               (using ec: ExecutionContext, system: ActorSystem): Future[Done] =
    val source = Source(credentials.toList).map(e => entryToJson(e) ++ EntrySeparator)
    val cryptSource = SecureStorageService.encryptSource(source, secret)
    val sink = FileIO.toPath(path)
    val futStream = cryptSource.runWith(sink)
    futStream.map(_ => Done)

  /**
    * Converts the given entry to a JSON string.
    *
    * @param e the entry to convert
    * @return the JSON representation of this entry
    */
  private def entryToJson(e: CredentialEntry): ByteString =
    val internalEntry = CredentialEntryInternal(e.key, e.value.secret)
    ByteString(internalEntry.toJson.compactPrint)

  /**
    * Converts a JSON string to an entry.
    *
    * @param json the JSON string
    * @return the entry represented by this JSON
    */
  private def jsonToEntry(json: ByteString): CredentialEntry =
    val ast = json.utf8String.parseJson
    val internalEntry = ast.convertTo[CredentialEntryInternal]
    CredentialEntry(internalEntry.key, Secret(internalEntry.value))
