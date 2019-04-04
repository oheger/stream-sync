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

package com.github.sync.crypt

import java.security.Key
import java.util.Base64

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.github.sync.SyncTypes.{FsFile, IterateResult, ResultTransformer}

import scala.concurrent.{ExecutionContext, Future}

/**
  * A module offering functionality related to encryption and decryption.
  *
  * The functions provided by this object are used to implement support for
  * cryptographic operations in the regular sync process.
  */
object CryptService {
  /**
    * Returns a ''ResultTransformer'' that supports the iteration over an
    * encrypted folder structure.
    *
    * @return the transformer for encrypted folder structures
    */
  def cryptTransformer(): ResultTransformer[Unit] = new ResultTransformer[Unit] {
    override def initialState: Unit = ()

    override def transform[F](result: IterateResult[F], s: Unit): Future[(IterateResult[F], Unit)] =
      Future.successful((transformEncryptedFileSizes(result), ()))
  }

  /**
    * Adapts the sizes of encrypted files in the given result object. This is
    * necessary because the files store some extra information.
    *
    * @param result the result to be transformed
    * @tparam F the type of the folders in the result
    * @return the transformed result with file sizes adapted
    */
  def transformEncryptedFileSizes[F](result: IterateResult[F]): IterateResult[F] =
    result.copy(files = result.files map transformEncryptedFileSize)

  /**
    * Encrypts a name (e.g. a component of a path) using the given key. The
    * operation is done in background. The encrypted result is returned as a
    * base64-encoded string.
    *
    * @param key  the key to be used for encrypting
    * @param name the name to be encrypted
    * @param ec   the execution context
    * @param mat  the object to materialize streams
    * @return a future with the encrypted result (in base64)
    */
  def encryptName(key: Key, name: String)(implicit ec: ExecutionContext, mat: ActorMaterializer): Future[String] =
    cryptName(key, name, EncryptOpHandler)(ByteString(_))(buf => Base64.getUrlEncoder.encodeToString(buf.toArray))

  /**
    * Decrypts a name using the given key. This function performs the reverse
    * transformation of ''encryptName()''. It assumes that the passed in name
    * is base64-encoded. It is decoded and then decrypted using the provided
    * key. The result is converted to a string.
    *
    * @param key  the key to be used for decrypting
    * @param name the name to be decrypted (in base64-encoding)
    * @param ec   the execution context
    * @param mat  the object to materialize streams
    * @return a future with the decrypted name
    */
  def decryptName(key: Key, name: String)(implicit ec: ExecutionContext, mat: ActorMaterializer): Future[String] =
    cryptName(key, name, DecryptOpHandler)(n => ByteString(Base64.getUrlDecoder.decode(n)))(_.utf8String)

  /**
    * A generic function to encrypt or decrypt a name. It implements the logic
    * to decode the input string to a ''ByteString'', run a stream with a
    * [[CryptStage]], and encode the resulting ''ByteString'' again to a
    * string. The handler for the crypt operations and functions for the
    * decoding and encoding are expected as arguments.
    *
    * @param key       the key for the crypt operation
    * @param name      the name to be processed
    * @param opHandler the handler for the crypt operation
    * @param decode    the function to decode the input
    * @param encode    the function to encode the output
    * @param ec        the execution context
    * @param mat       the object to materialize streams
    * @return a future with the processed string
    */
  private def cryptName(key: Key, name: String, opHandler: CryptOpHandler)(decode: String => ByteString)
                       (encode: ByteString => String)
                       (implicit ec: ExecutionContext, mat: ActorMaterializer): Future[String] = {
    val source = Source.single(decode(name))
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    val stage = new CryptStage(opHandler, key)
    source.via(stage).runWith(sink) map encode
  }

  /**
    * Transforms the given file to have the decrypted file size.
    *
    * @param f the file
    * @return the transformed file
    */
  private def transformEncryptedFileSize(f: FsFile): FsFile =
    f.copy(size = DecryptOpHandler.processedSize(f.size))
}
