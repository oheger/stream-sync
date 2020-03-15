/*
 * Copyright 2018-2020 The Developers Team.
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

package com.github.sync.impl

import java.security.Key

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.sync.SourceFileProvider
import com.github.sync.crypt.{CryptOpHandler, CryptStage, DecryptOpHandler, EncryptOpHandler}
import com.github.sync.impl.CryptAwareSourceFileProvider.CryptData

import scala.concurrent.{ExecutionContext, Future}

object CryptAwareSourceFileProvider {
  /**
    * Creates a new instance of ''CryptAwareSourceFileProvider'' that wraps a
    * given ''SourceFileProvider'' and applies decryption or encryption
    * functionality as required. If a password is provided for a structure, a
    * properly initialized [[CryptStage]] is added.
    *
    * @param wrappedProvider the wrapped provider
    * @param optSrcPassword  an option with the source password
    * @param optDstPassword  an option with the destination password
    * @param ec              the execution context
    * @return the newly created instance
    */
  def apply(wrappedProvider: SourceFileProvider, optSrcPassword: Option[String], optDstPassword: Option[String])
           (implicit ec: ExecutionContext): CryptAwareSourceFileProvider = {
    val cryptOps = List(
      optSrcPassword.map(pwd => CryptData(CryptStage.keyFromString(pwd), DecryptOpHandler)),
      optDstPassword.map(pwd => CryptData(CryptStage.keyFromString(pwd), EncryptOpHandler))
    )
    new CryptAwareSourceFileProvider(wrappedProvider, cryptOps.flatten)
  }

  /**
    * An internally used data class that stores information about crypt
    * operations to be applied for the wrapped sources.
    *
    * @param key            the key to encrypt or decrypt
    * @param cryptOpHandler the handler defining the crypt operation
    */
  private case class CryptData(key: Key, cryptOpHandler: CryptOpHandler)

}

/**
  * A ''SourceFileProvider'' implementation that can handle encryption and
  * decryption of files.
  *
  * This class wraps another ''SourceFileProvider'' and is configured with
  * optional passwords for the source and destination structures. When asked
  * for a source for a file it first delegates to the wrapped provider.
  * Depending on the passwords available, it then appends the source of the
  * file by corresponding stages to encrypt or decrypt the file.
  *
  * @param wrappedProvider the wrapped provider
  * @param cryptData       a list with the crypt operations to be executed
  * @param ec              the execution context
  */
class CryptAwareSourceFileProvider private(wrappedProvider: SourceFileProvider, cryptData: List[CryptData])
                                          (implicit ec: ExecutionContext) extends SourceFileProvider {
  /**
    * @inheritdoc This implementation obtains the ''Source'' future from the
    *             wrapped source and then appends a decryption or encryption
    *             stage as necessary.
    */
  override def fileSource(uri: String): Future[Source[ByteString, Any]] = {
    val futSource = wrappedProvider.fileSource(uri)
    cryptData.foldLeft(futSource) { (fut, crypt) =>
      fut.map(src => src.via(new CryptStage(crypt.cryptOpHandler, crypt.key)))
    }
  }

  /**
    * @inheritdoc This implementation delegates to the wrapped provider.
    */
  override def shutdown(): Unit = wrappedProvider.shutdown()

  /**
    * @inheritdoc This implementation adapts the file size returned by the
    *             original handler using the handlers that need to be applied
    *             for crypt operations.
    */
  override def fileSize(orgSize: Long): Long =
    cryptData.foldLeft(wrappedProvider.fileSize(orgSize)) { (size, crypt) =>
      crypt.cryptOpHandler.processedSize(size)
    }
}
