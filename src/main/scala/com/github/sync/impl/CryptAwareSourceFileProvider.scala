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

package com.github.sync.impl

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.sync.crypt.{DecryptStage, EncryptStage}
import com.github.sync.{SourceFileProvider, SyncTypes}

import scala.concurrent.{ExecutionContext, Future}

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
  * @param optSrcPassword  an option with the source password
  * @param optDstPassword  an option with the destination password
  * @param ec              the execution context
  */
class CryptAwareSourceFileProvider(wrappedProvider: SourceFileProvider, optSrcPassword: Option[String],
                                   optDstPassword: Option[String])
                                  (implicit ec: ExecutionContext) extends SourceFileProvider {
  /**
    * @inheritdoc This implementation obtains the ''Source'' future from the
    *             wrapped source and then appends a decryption or encryption
    *             stage as necessary.
    */
  override def fileSource(file: SyncTypes.FsFile): Future[Source[ByteString, Any]] =
    wrappedProvider.fileSource(file)
      .map(src => optSrcPassword.map(pwd => src.via(new DecryptStage(pwd))).getOrElse(src))
      .map(src => optDstPassword.map(pwd => src.via(new EncryptStage(pwd))).getOrElse(src))

  /**
    * @inheritdoc This implementation delegates to the wrapped provider.
    */
  override def shutdown(): Unit = wrappedProvider.shutdown()
}
