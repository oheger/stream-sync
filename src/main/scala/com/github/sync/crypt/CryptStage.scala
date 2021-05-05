/*
 * Copyright 2018-2021 The Developers Team.
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
import java.util.concurrent.atomic.AtomicLong

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.util.ByteString
import com.github.sync.crypt.CryptStage.IvLength
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

  /** A counter for keeping track on the number of processed bytes. */
  private val processedBytesCount = new AtomicLong

  /**
    * Generates a key from the given byte array. This method expects that the
    * passed in array has at least the length required by a valid key
    * (additional bytes are ignored).
    *
    * @param keyData the array with the data of the key
    * @return the resulting key
    */
  def keyFromArray(keyData: Array[Byte]): Key =
    new SecretKeySpec(keyData, 0, KeyLength, AlgorithmName)

  /**
    * Generates a key from the given string. If necessary, the string is
    * padded or truncated to come to the correct key length in bytes.
    *
    * @param strKey the string-based key
    * @return the resulting key
    */
  def keyFromString(strKey: String): Key =
    keyFromArray(generateKeyArray(strKey))

  /**
    * Convenience method to generate a ''CryptStage'' that can be used to
    * encrypt data with the given key.
    *
    * @param key the key for encryption
    * @return the stage to encrypt data
    */
  def encryptStage(key: Key): CryptStage =
    new CryptStage(EncryptOpHandler, key)

  /**
    * Convenience method to generate a ''CryptStage'' that can be used to
    * decrypt data with the given key.
    *
    * @param key the key for decryption
    * @return the state to decrypt data
    */
  def decryptStage(key: Key): CryptStage =
    new CryptStage(DecryptOpHandler, key)

  /**
    * Returns the number of bytes that have been encrypted or decrypted in
    * total by instances of this stage class. This information is mainly used
    * for testing or statistical purposes.
    *
    * @return the number of bytes processed by instances of ''CryptStage''
    */
  def processedBytes: Long = processedBytesCount.get()

  /**
    * Resets the counter for the number of bytes processed by instances of
    * ''CryptStage''.
    */
  def resetProcessedBytes(): Unit = {
    processedBytesCount set 0
  }

  /**
    * Transforms the given string key to a byte array which can be used for the
    * creation of a secret key spec. This function takes care that the
    * resulting array has the correct length.
    *
    * @param strKey the string-based key
    * @return an array with key data as base for a secret key spec
    */
  private def generateKeyArray(strKey: String): Array[Byte] = {
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

  /**
    * Updates the counter for the bytes processed.
    *
    * @param buf the current buffer with data to be processed
    */
  private def updateProcessed(buf: ByteString): Unit = {
    processedBytesCount.addAndGet(buf.length)
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
  * @param cryptOpHandler the operation handler
  * @param key            the key to be used for the operation
  */
class CryptStage(val cryptOpHandler: CryptOpHandler, key: Key) extends GraphStage[FlowShape[ByteString, ByteString]] {

  import CryptStage._

  /**
    * Definition of a processing function. The function expects a block of data
    * and a cipher and produces a block of data to be passed downstream.
    */
  type CryptFunc = (ByteString, Cipher) => ByteString

  val in: Inlet[ByteString] = Inlet[ByteString]("CryptStage.in")
  val out: Outlet[ByteString] = Outlet[ByteString]("CryptStage.out")

  override val shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

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
        updateProcessed(data)
        val result = cryptOpHandler.initCipher(key, cryptCipher, data, random)
        processingFunc = cryptFunc
        result
      }
    }

  /**
    * Returns the function executing encryption / decryption logic. This
    * function is called when initialization is done to process further data
    * chunks.
    *
    * @return the function for processing data chunks
    */
  private def cryptFunc: CryptFunc = (chunk, cipher) => {
    val encData = cipher.update(chunk.toArray)
    updateProcessed(chunk)
    ByteString(encData)
  }
}

/**
  * A concrete ''CryptOpHandler'' implementation for encrypting data.
  */
object EncryptOpHandler extends CryptOpHandler {
  /**
    * @inheritdoc This implementation initializes the cipher for encryption.
    *             It creates a random initialization vector for this purpose.
    *             This IV is passed downstream as part of the initial chunk
    *             of data.
    */
  override def initCipher(encKey: Key, cipher: Cipher, chunk: ByteString, secRandom: SecureRandom): ByteString = {
    val ivBytes = new Array[Byte](IvLength)
    secRandom.nextBytes(ivBytes)
    val iv = new IvParameterSpec(ivBytes)
    cipher.init(Cipher.ENCRYPT_MODE, encKey, iv)

    val encData = cipher.update(chunk.toArray)
    ByteString(ivBytes) ++ ByteString(encData)
  }

  /**
    * @inheritdoc During encryption the file size is increased because the
    *             initialization vector is added. This is taken into account by
    *             this implementation.
    */
  override def processedSize(orgSize: Long): Long = orgSize + IvLength
}

/**
  * A concrete ''CryptOpHandler'' implementation for decrypting data.
  */
object DecryptOpHandler extends CryptOpHandler {
  /**
    * @inheritdoc This implementation expects that the initialization vector is
    *             at the beginning of the passed in data chunk. Based on this
    *             information, the cipher can be initialized and the first
    *             block decoded. If the IV cannot be extracted, an
    *             ''IllegalStateException'' exception is thrown.
    */
  override def initCipher(encKey: Key, cipher: Cipher, chunk: ByteString, secRandom: SecureRandom): ByteString = {
    val data = chunk.toArray
    if (data.length < IvLength)
      throw new IllegalStateException("Illegal initial chunk! The chunk must at least contain the IV.")
    val iv = new IvParameterSpec(data, 0, IvLength)
    cipher.init(Cipher.DECRYPT_MODE, encKey, iv, secRandom)

    ByteString(cipher.update(data, IvLength, data.length - IvLength))
  }

  /**
    * @inheritdoc During decryption the file size is reduced by the
    *             initialization vector length. This is taken into account by
    *             this implementation.
    */
  override def processedSize(orgSize: Long): Long = orgSize - IvLength
}
