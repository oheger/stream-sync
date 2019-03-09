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

import java.nio.charset.StandardCharsets
import java.security.{Key, SecureRandom}

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.ByteString
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}

import scala.annotation.tailrec

object CryptStage {
  /** The length of the initialization vector. */
  val IvLength = 16

  /** The length of keys required by the encryption algorithm. */
  val KeyLength = 16

  /** The name of the cipher used for encrypt / decrypt operations. */
  val CipherName = "AES/CTR/NoPadding"

  /** The name of the encryption algorithm. */
  val AlgorithmName = "AES"

  /**
    * Transforms the given string key to a byte array which can be used for the
    * creation of a secret key spec. This function takes care that the
    * resulting array has the correct length.
    *
    * @param strKey the string based key
    * @return an array with key data as base for a secret key spec
    */
  def generateKeyArray(strKey: String): Array[Byte] = {
    val keyData = strKey.getBytes(StandardCharsets.UTF_8)
    if (keyData.length < KeyLength) {
      val paddedData = new Array[Byte](KeyLength)
      padKeyData(keyData, paddedData, 0)
    } else keyData
  }

  /**
    * Generates key data of the correct length. This is done by repeating the
    * original key data until the desired target length is reached.
    *
    * @param orgData the original (too short) key data
    * @param target  the target array
    * @param idx     the current index
    * @return the array with key data of the desired length
    */
  @tailrec private def padKeyData(orgData: Array[Byte], target: Array[Byte], idx: Int): Array[Byte] =
    if (idx == target.length) target
    else {
      target(idx) = orgData(idx % orgData.length)
      padKeyData(orgData, target, idx + 1)
    }
}

/**
  * An abstract base class for stages that encrypt or decrypt data.
  *
  * This class implements the major part of the stream processing logic.
  * Derived classes only have to handle their specific transformation.
  *
  * This class assumes that cryptographic operations are executed in two
  * phases: First an initialization has to be done, typically when the first
  * chunk of data arrives. Then the actual stream processing takes place in
  * which data is processed chunk-wise. The phases are represented by
  * transformation functions that need to be provided by concrete sub classes.
  *
  * @param keyData the key to be used for the operation as byte array
  */
abstract class CryptStage(private val keyData: Array[Byte]) extends GraphStage[FlowShape[ByteString, ByteString]] {

  import CryptStage._

  /**
    * Definition of a processing function. The function expects a block of data
    * and a cipher and produces a block of data to be passed downstream.
    */
  type CryptFunc = (ByteString, Cipher) => ByteString

  val in: Inlet[ByteString] = Inlet[ByteString]("CryptStage.in")
  val out: Outlet[ByteString] = Outlet[ByteString]("CryptStage.out")

  override val shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  /**
    * Creates a new instance of ''CryptStage'' with a string based key. This is
    * a convenience constructor; the string is transformed into an array with
    * the correct key length.
    *
    * @param key the key as string
    * @return the new instance
    */
  def this(key: String) = this(CryptStage.generateKeyArray(key))

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      /** Source for randomness. */
      private lazy val random = new SecureRandom

      /** The cipher object managed by this class. */
      private lazy val cryptCipher = Cipher.getInstance(CipherName)

      /** The current processing function. */
      private var processingFunc: CryptFunc = initProcessing

      /**
        * A flag whether data has been received by this stage. This is used to
        * determine whether a final block of data has to be handled when
        * upstream ends.
        */
      private var dataProcessed = false

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val data = grab(in)
          val processedData = processingFunc(data, cryptCipher)
          push(out, processedData)
        }

        override def onUpstreamFinish(): Unit = {
          if (dataProcessed) {
            val finalBytes = cryptCipher.doFinal()
            if (finalBytes.nonEmpty) {
              push(out, ByteString(finalBytes))
            }
          }
          super.onUpstreamFinish()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })

      /**
        * Handles initialization of data processing. This is the ''CryptFunc''
        * that is called for the first block of data received. It initializes
        * the managed cipher object, pushes the first data block downstream,
        * and sets the actual processing function.
        *
        * @param data   the first chunk of data
        * @param cipher the cipher object
        * @return the data to be pushed downstream
        */
      private def initProcessing(data: ByteString, cipher: Cipher): ByteString = {
        dataProcessed = true
        val secKey = new SecretKeySpec(keyData, 0, KeyLength, AlgorithmName)
        val result = initCipher(secKey, cryptCipher, data, random)
        processingFunc = cryptFunc
        result
      }
    }

  /**
    * Initializes the given cipher for the specific operation to be performed.
    * This method when first data arrives and processing has to be setup.
    *
    * @param encKey    the key to encrypt / decrypt
    * @param cipher    the cipher to be initialized
    * @param chunk     the first chunk of data
    * @param secRandom the secure random object to init the cipher
    * @return the next chunk to be passed downstream
    */
  protected def initCipher(encKey: Key, cipher: Cipher, chunk: ByteString, secRandom: SecureRandom): ByteString

  /**
    * Returns the function executing encryption / decryption logic. This
    * function is called when initialization is done to process further data
    * chunks.
    *
    * @return the function for processing data chunks
    */
  private def cryptFunc: CryptFunc = (chunk, cipher) => {
    val encData = cipher.update(chunk.toArray)
    ByteString(encData)
  }
}

/**
  * A graph stage implementation that can encrypt a stream of data on the fly
  * using AES.
  *
  * The stage is provided the key to use for encrypting. Based on the data
  * flowing through the stream, a corresponding cipher text is generated that
  * can be decrypted again using a matching [[DecryptStage]].
  *
  * @param key the key to be used for encryption
  */
class EncryptStage(key: String) extends CryptStage(key) {

  import CryptStage._

  /**
    * @inheritdoc This implementation initializes the cipher for encryption.
    *             It creates a random initialization vector for this purpose.
    *             This IV is passed downstream as part of the initial chunk
    *             of data.
    */
  override protected def initCipher(encKey: Key, cipher: Cipher, chunk: ByteString, secRandom: SecureRandom):
  ByteString = {
    val ivBytes = new Array[Byte](IvLength)
    secRandom.nextBytes(ivBytes)
    val iv = new IvParameterSpec(ivBytes)
    cipher.init(Cipher.ENCRYPT_MODE, encKey, iv)

    val encData = cipher.update(chunk.toArray)
    ByteString(ivBytes) ++ ByteString(encData)
  }
}

/**
  * A graph stage implementation that can decrypt a stream of data on the fly
  * using AES.
  *
  * This class can work together with [[EncryptStage]] to encrypt and decrypt
  * streams of binary data.
  *
  * @param key the key to be used for decryption
  */
class DecryptStage(key: String) extends CryptStage(key) {

  import CryptStage._

  /**
    * @inheritdoc This implementation expects that the initialization vector is
    *             at the beginning of the passed in data chunk. Based on this
    *             information, the cipher can be initialized and the first
    *             block decoded. If the IV cannot be extracted, an
    *             ''IllegalStateException'' exception is thrown.
    */
  override protected def initCipher(encKey: Key, cipher: Cipher, chunk: ByteString, secRandom: SecureRandom):
  ByteString = {
    val data = chunk.toArray
    if (data.length < IvLength)
      throw new IllegalStateException("Illegal initial chunk! The chunk must at least contain the IV.")
    val iv = new IvParameterSpec(data, 0, IvLength)
    cipher.init(Cipher.DECRYPT_MODE, encKey, iv, secRandom)

    ByteString(cipher.update(data, IvLength, data.length - IvLength))
  }
}
