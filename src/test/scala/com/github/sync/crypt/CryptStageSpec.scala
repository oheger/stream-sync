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

import akka.actor.ActorSystem
import akka.stream.FlowShape
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.stage.GraphStage
import akka.testkit.TestKit
import akka.util.ByteString
import com.github.sync.{AsyncTestHelper, FileTestHelper}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object CryptStageSpec {
  /** A default key used for encryption / decryption. */
  private val Key = "0123456789ABCDEF"

  /** Group size for splitting text input for encryption. */
  private val GroupSize = 64

  /**
    * Splits a text into a number of byte string blocks with a standard chunk
    * size.
    *
    * @param text the input text
    * @return the resulting blocks
    */
  private def splitPlainText(text: String): List[ByteString] =
    text.grouped(GroupSize).map(s => ByteString(s)).toList

  /**
    * Combines a list of binary chunks and returns the result as string.
    *
    * @param data the list of data chunks
    * @return the resulting string
    */
  private def combine(data: List[ByteString]): String =
    data.foldLeft(ByteString.empty)((buf, s) => buf ++ s).utf8String
}

/**
  * Test class for the graph stages that encrypt and decrypt streams.
  */
class CryptStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with BeforeAndAfterAll with Matchers with AsyncTestHelper {
  def this() = this(ActorSystem("CryptStagesSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import CryptStageSpec._

  /** The object to materialize streams. */

  /**
    * Runs a stream that encrypts or decrypts the given data.
    *
    * @param data       the data to be processed
    * @param cryptStage the stage for encryption / decryption
    * @return the list with resulting data chunks
    */
  private def runCryptStream(data: List[ByteString], cryptStage: GraphStage[FlowShape[ByteString, ByteString]]):
  List[ByteString] = {
    val source = Source(data)
    val sink = Sink.fold[List[ByteString], ByteString](Nil)((list, bs) => bs :: list)
    val futResult = source.via(cryptStage)
      .runWith(sink)
    futureResult(futResult).reverse
  }

  /**
    * Runs a stream to encrypt the given message. The message is split into
    * chunks before.
    *
    * @param message the message to be encrypted
    * @param key     the key to encrypt
    * @return the resulting encrypted chunks
    */
  private def encrypt(message: String, key: String = Key): List[ByteString] =
    runCryptStream(splitPlainText(message), CryptStage.encryptStage(CryptStage.keyFromString(key)))

  /**
    * Runs a stream that decrypts the given data chunks and returns the result
    * as string.
    *
    * @param cipherText the chunks of the encrypted message
    * @param key        the key to decrypt
    * @return the resulting decrypted text
    */
  private def decrypt(cipherText: List[ByteString], key: String = Key): String =
    combine(runCryptStream(cipherText, CryptStage.decryptStage(CryptStage.keyFromString(key))))

  /**
    * Checks an encryption followed by a decryption. This should result in the
    * original message.
    *
    * @param message the message to be processed
    * @param key     the key to be used
    */
  private def checkRoundTrip(message: String, key: String = Key): Unit = {
    val encrypted = encrypt(message, key)
    val processed = decrypt(encrypted, key)
    processed should be(message)
  }

  "A CryptStage" should "produce the same text when encrypting and decrypting" in {
    checkRoundTrip(FileTestHelper.TestData)
  }

  it should "handle an empty source to encrypt" in {
    val stage = CryptStage.encryptStage(CryptStage.keyFromString(Key))

    runCryptStream(Nil, stage) should have size 0
  }

  it should "handle an empty source to decrypt" in {
    decrypt(Nil) should be("")
  }

  it should "produce encrypted text" in {
    val cipherText = combine(encrypt(FileTestHelper.TestData))

    cipherText should not be FileTestHelper.TestData
  }

  it should "produce different cipher text on each encrypt operation" in {
    val cipherText1 = combine(encrypt(FileTestHelper.TestData))

    val cipherText2 = combine(encrypt(FileTestHelper.TestData))
    cipherText1 should not be cipherText2
  }

  it should "handle small messages as well" in {
    val Message = "Test"

    checkRoundTrip(Message)
  }

  it should "shorten keys that are too long" in {
    checkRoundTrip(FileTestHelper.TestData, "This is a very long key! Could cause problems...")
  }

  it should "pad keys that are too short" in {
    checkRoundTrip(FileTestHelper.TestData, "foo")
  }

  it should "handle an invalid initial block during decryption" in {
    val blocks = List(ByteString("!"), ByteString("This should be encrypted text"))

    intercept[IllegalStateException] {
      decrypt(blocks)
    }
  }

  it should "collect statistics about the number of bytes processed" in {
    CryptStage.resetProcessedBytes()
    val cipher = encrypt(FileTestHelper.TestData)

    CryptStage.processedBytes should be >= FileTestHelper.TestData.length.toLong
    decrypt(cipher)

    CryptStage.processedBytes should be > 2L * FileTestHelper.TestData.length
  }

  "An EncryptOpHandler" should "calculate a correct file size" in {
    EncryptOpHandler.processedSize(128) should be(144)
  }

  "A DecryptOpHandler" should "calculate a correct file size" in {
    DecryptOpHandler.processedSize(128) should be(112)
  }
}
