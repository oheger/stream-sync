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
import com.github.cloudfiles.crypt.alg.CryptAlgorithm
import com.github.cloudfiles.crypt.alg.aes.Aes
import com.github.cloudfiles.crypt.service.CryptService
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import java.security.{Key, SecureRandom}

/**
  * An object providing functionality to load and store sensitive data that
  * needs to be encrypted at rest.
  *
  * Using this service, it is possible to persist credentials or access tokens
  * on the local disk in a secure way.
  */
object SecureStorageService:
  /**
    * Applies encryption to a given [[Source]] based on a provided [[Secret]].
    * This function can be used to encrypt data on the fly before it is
    * persisted in a file.
    *
    * @param source the [[Source]] with the data to encrypt
    * @param secret the [[Secret]] to use for encryption
    * @tparam MAT the materialized type of the source
    * @return the encrypted source
    */
  def encryptSource[MAT](source: Source[ByteString, MAT], secret: Secret): Source[ByteString, MAT] =
    cryptSource(source, secret): (alg, key, rnd, src) =>
      CryptService.encryptSource(alg, key, src)(using rnd)

  /**
    * Applies decryption to a given [[Source]] based on the provided
    * [[Secret]]. This function can be used to load data again that was saved
    * via [[encryptSource]].
    *
    * @param source the [[Source]] with the data to decrypt
    * @param secret the [[Secret]] to use for decryption
    * @tparam MAT the materialized type of the source
    * @return the decrypted source
    */
  def decryptSource[MAT](source: Source[ByteString, MAT], secret: Secret): Source[ByteString, MAT] =
    cryptSource(source, secret): (alg, key, rnd, src) =>
      CryptService.decryptSource(alg, key, src)(using rnd)

  /**
    * Applies a cryptographic operation to the given source defined by a
    * function. This function generates an AES key and applies the given crypt
    * function (to either encrypt or decrypt data) to the given source.
    *
    * @param source    the original source
    * @param secret    the [[Secret]] to use for encryption
    * @param cryptFunc a function to apply the desired operation to the
    *                  original source
    * @tparam Mat the type of materialization
    * @return the decorated source
    */
  private def cryptSource[Mat](source: Source[ByteString, Mat], secret: Secret)
                              (cryptFunc: (CryptAlgorithm, Key, SecureRandom, Source[ByteString, Mat]) =>
                                Source[ByteString, Mat]): Source[ByteString, Mat] =
    cryptFunc(Aes, Aes.keyFromString(secret.secret), new SecureRandom, source)
