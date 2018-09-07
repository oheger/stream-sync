/*
 * Copyright 2018 The Developers Team.
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

package com.github.sync.log

import java.time.Instant

import akka.util.ByteString
import com.github.sync._
import org.scalatest.{FlatSpec, Matchers}

import scala.util.{Failure, Success}

/**
  * Test class for ''ElementSerializer''.
  */
class ElementSerializerSpec extends FlatSpec with Matchers {
  /**
    * Returns the line-ending character.
    *
    * @return the line-ending character
    */
  private def lineEnd: String = System.lineSeparator()

  "ElementSerializer" should "serialize a folder" in {
    val folder = FsFolder("test/folder", 11)

    val s = ElementSerializer.serializeElement(folder).utf8String
    s should be(s"FOLDER ${folder.relativeUri} ${folder.level}")
  }

  it should "serialize a file" in {
    val fileTime = "2018-09-06T17:25:28.103Z"
    val file = FsFile("test/data.txt", 21, Instant.parse(fileTime), 123456)

    val s = ElementSerializer.serializeElement(file).utf8String
    s should be(s"FILE ${file.relativeUri} ${file.level} $fileTime ${file.size}")
  }

  /**
    * Helper method for testing whether an operation of a specific action can
    * be serialized correctly.
    *
    * @param action    the action
    * @param strAction the string representation of this action
    */
  private def checkSerializedOperation(action: SyncAction, strAction: String): Unit = {
    val elem = FsFolder("my/folder", 8)
    val op = SyncOperation(elem, action, 4)

    val s = ElementSerializer.serializeOperation(op).utf8String
    s should be(s"$strAction ${op.level} FOLDER ${elem.relativeUri} ${elem.level}$lineEnd")
  }

  it should "serialize a create operation" in {
    checkSerializedOperation(ActionCreate, "CREATE")
  }

  it should "serialize an override operation" in {
    checkSerializedOperation(ActionOverride, "OVERRIDE")
  }

  it should "serialize a remove operation" in {
    checkSerializedOperation(ActionRemove, "REMOVE")
  }

  it should "deserialize a folder element" in {
    val folder = FsFolder("some/test/folder", 9)
    val parts = ElementSerializer.serializeElement(folder).utf8String.split("\\s")

    val folder2 = ElementSerializer.deserializeElement(parts).get
    folder2 should be(folder)
  }

  it should "deserialize a file element" in {
    val file = FsFile("my/test/file.txt", 2, Instant.parse("2018-09-06T19:14:36.189Z"),
      20180906191501L)
    val parts = ElementSerializer.serializeElement(file).utf8String.split("\\s")

    val file2 = ElementSerializer.deserializeElement(parts).get
    file2 should be(file)
  }

  it should "handle a deserialization of an unknown element tag" in {
    val parts = Array("FOLDER_FILE", "/test/data", "1")

    ElementSerializer.deserializeElement(parts) match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include("FOLDER_FILE")
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "handle a deserialization of an element with not enough parts" in {
    val triedElement = ElementSerializer.deserializeElement(Array.empty[String])

    triedElement.isFailure shouldBe true
  }

  it should "handle a deserialization of an element with invalid properties" in {
    val parts = Array("FOLDER", "/uri", "notAValidLevel")

    val triedElement = ElementSerializer.deserializeElement(parts)
    triedElement.isFailure shouldBe true
  }

  /**
    * Helper method for testing the deserialization of a sync operation using
    * a specific action.
    *
    * @param action the action
    */
  private def checkDeserializeOperation(action: SyncAction): Unit = {
    val file = FsFile("my/test/file.txt", 2, Instant.parse("2018-09-06T19:31:33.529Z"),
      20180906193152L)
    val operation = SyncOperation(file, action, 22)
    val opRaw = ElementSerializer serializeOperation operation

    ElementSerializer.deserializeOperation(opRaw) match {
      case Success(op) =>
        op should be(operation)
      case r =>
        fail("Unexpected result: " + r)
    }
  }

  it should "deserialize a create operation" in {
    checkDeserializeOperation(ActionCreate)
  }

  it should "deserialize an override operation" in {
    checkDeserializeOperation(ActionOverride)
  }

  it should "deserialize a remove operation" in {
    checkDeserializeOperation(ActionRemove)
  }

  it should "handle a deserialization of an invalid action tag" in {
    val raw = ByteString("DELETE 13 FOLDER /foo/bar 8")

    val triedOperation = ElementSerializer.deserializeOperation(raw)
    triedOperation.isSuccess shouldBe false
  }
}