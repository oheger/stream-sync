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

import com.github.sync.SyncTypes.{FsFile, IterateResult, SyncFolderData, TransformResultFunc}

import scala.concurrent.Future

/**
  * A module offering functionality related to encryption and decryption.
  *
  * The functions provided by this object are used to implement support for
  * cryptographic operations in the regular sync process.
  */
object CryptService {
  /**
    * Returns a transformation function that supports the iteration over an
    * encrypted folder structure.
    *
    * @tparam F the type of the folders in the result
    * @return the transformation function for encrypted folder structures
    */
  def cryptTransformFunc[F <: SyncFolderData](): TransformResultFunc[F] = result =>
    Future.successful(transformEncryptedFileSizes(result))

  /**
    * Adapts the sizes of encrypted files in the given result object. This is
    * necessary because the files store some extra information.
    *
    * @param result the result to be transformed
    * @tparam F the type of the folders in the result
    * @return the transformed result with file sizes adapted
    */
  def transformEncryptedFileSizes[F <: SyncFolderData](result: IterateResult[F]): IterateResult[F] =
    result.copy(files = result.files map transformEncryptedFileSize)

  /**
    * Transforms the given file to have the decrypted file size.
    *
    * @param f the file
    * @return the transformed file
    */
  private def transformEncryptedFileSize(f: FsFile): FsFile =
    f.copy(size = DecryptOpHandler.processedSize(f.size))
}
