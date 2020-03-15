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

package com.github.sync.crypt

import java.security.{Key, SecureRandom}

import akka.util.ByteString
import javax.crypto.Cipher

/**
  * A trait defining an operation (such as encryption or decryption) to be
  * executed by a ''CryptStage''.
  *
  * Each ''CryptStage'' is associated with such a handler. The handler provides
  * functionality to initialize a cipher object for the operation to be
  * executed.
  */
trait CryptOpHandler {
  /**
    * Initializes the given cipher for the specific operation to be performed.
    * This method is called when first data arrives and processing has to be
    * setup.
    *
    * @param encKey    the key to encrypt / decrypt
    * @param cipher    the cipher to be initialized
    * @param chunk     the first chunk of data
    * @param secRandom the secure random object to init the cipher
    * @return the next chunk to be passed downstream
    */
  def initCipher(encKey: Key, cipher: Cipher, chunk: ByteString, secRandom: SecureRandom): ByteString

  /**
    * Calculates the size of a file after it has been processed by this
    * handler. Typically, through an encrypt or decrypt operation the file size
    * will change. Using this method the final file size can be determined.
    *
    * @param orgSize the original file size
    * @return the size after processing by this handler
    */
  def processedSize(orgSize: Long): Long
}
