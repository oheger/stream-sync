/*
 * Copyright 2018-2022 The Developers Team.
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

import com.github.sync.SyncTypes.*
import com.github.sync.SyncTypes.SyncAction.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success}

/**
  * Test class for ''ElementSerializer''.
  */
class ElementSerializerSpec extends AnyFlatSpec with Matchers :
  /**
    * Returns the line-ending character.
    *
    * @return the line-ending character
    */
  private def lineEnd: String = System.lineSeparator()

  "ElementSerializer" should "serialize a folder" in {
    val folder: FsElement = FsFolder("someFolderID", "test_folder", 11)

    val s = ElementSerializer.serialize(folder).utf8String
    s should be(s"FOLDER ${folder.id} ${folder.relativeUri} ${folder.level}$lineEnd")
  }

  it should "serialize a file" in {
    val fileTime = "2018-09-06T17:25:28.103Z"
    val file = FsFile("someFileID", "test_data.txt", 21, Instant.parse(fileTime), 123456)

    val s = ElementSerializer.serialize(file: FsElement).utf8String
    s should be(s"FILE ${file.id} ${file.relativeUri} ${file.level} $fileTime ${file.size}$lineEnd")
  }

  it should "encode element URIs on serialization" in {
    val folder = FsFolder("someFolderID", "/my data/sub/cool stuff (42)", 10)

    val s = ElementSerializer.serialize(folder: FsElement).utf8String
    s should be(s"FOLDER ${folder.id} %2Fmy%20data%2Fsub%2Fcool%20stuff%20%2842%29 ${folder.level}$lineEnd")
  }

  it should "encode element IDs on serialization" in {
    val folder = FsFolder("some folder(42)", "folder-uri", 1)

    val s = ElementSerializer.serialize(folder: FsElement).utf8String
    s should be(s"FOLDER some%20folder%2842%29 ${folder.relativeUri} ${folder.level}$lineEnd")
  }

  it should "support a serialization round-trip with a folder" in {
    val folder = FsFolder("some folder(42)", "/my data/sub/cool stuff (42)", 10)
    val s = ElementSerializer.serialize(folder: FsElement).utf8String

    val folder2 = ElementSerializer.deserialize[FsElement](s).get
    folder2 should be(folder)
  }

  it should "support a serialization round-trip with a file" in {
    val fileTime = "2018-09-06T17:25:28.103Z"
    val file = FsFile("someFileID", "test_data.txt", 21, Instant.parse(fileTime), 123456)

    val s = ElementSerializer.serialize(file: FsElement).utf8String
    val file2 = ElementSerializer.deserialize[FsElement](s).get
    file2 should be(file)
  }

  /**
    * Helper method for testing whether an operation of a specific action can
    * be serialized correctly.
    *
    * @param action    the action
    * @param strAction the string representation of this action
    */
  private def checkSerializedOperation(action: SyncAction, strAction: String): Unit =
    val elem = FsFolder("1a", "my_folder", 8)
    val op = SyncOperation(elem, action, 4, dstID = "someDstID")

    val s = ElementSerializer.serialize(op).utf8String
    s should be(s"$strAction ${op.level} ${op.dstID} FOLDER ${elem.id} ${elem.relativeUri} ${elem.level}$lineEnd")

  it should "serialize a create operation" in {
    checkSerializedOperation(ActionCreate, "CREATE")
  }

  it should "serialize an override operation" in {
    checkSerializedOperation(ActionOverride, "OVERRIDE")
  }

  it should "serialize a remove operation" in {
    checkSerializedOperation(ActionRemove, "REMOVE")
  }

  it should "serialize a local create operation" in {
    checkSerializedOperation(ActionLocalCreate, "LOCAL_CREATE")
  }

  it should "serialize a local override operation" in {
    checkSerializedOperation(ActionLocalOverride, "LOCAL_OVERRIDE")
  }

  it should "serialize a local remove operation" in {
    checkSerializedOperation(ActionLocalRemove, "LOCAL_REMOVE")
  }

  it should "serialize a successful sync operation result" in {
    val elem = FsFolder("folderID", "testFolder", 8)
    val op = SyncOperation(elem, ActionCreate, 4, dstID = "someDstID")
    val result = SyncOperationResult(op, None)

    val s = ElementSerializer.serializeOperationResult(result).utf8String
    s should be(s"CREATE ${op.level} ${op.dstID} FOLDER ${elem.id} ${elem.relativeUri} ${elem.level}$lineEnd")
  }

  it should "serialize the exception of a failed sync operation result" in {
    val elem = FsFolder("errorFolderID", "brokenFolder", 3)
    val op = SyncOperation(elem, ActionCreate, 4, dstID = "brokenDstID")
    try
      throw new IllegalStateException("Test exception")
    catch
      case e: Exception =>
        val result = SyncOperationResult(op, Some(e))

        val s = ElementSerializer.serializeOperationResult(result).utf8String.split(lineEnd)
        s should have size e.getStackTrace.length + 2
        s(0) should be(s"CREATE ${op.level} ${op.dstID} FOLDER ${elem.id} ${elem.relativeUri} ${elem.level}")
        s(1) should include(e.getMessage)
  }

  it should "handle a deserialization of an unknown element tag" in {
    val ser = "FOLDER_FILE 123 /test/data 1"

    ElementSerializer.deserialize[FsElement](ser) match
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include("FOLDER_FILE")
      case r => fail("Unexpected result: " + r)
  }

  it should "handle a deserialization of an element with not enough parts" in {
    val triedElement = ElementSerializer.deserialize[FsElement]("")

    triedElement.isFailure shouldBe true
  }

  it should "handle a deserialization of an element with invalid properties" in {
    val ser = "FOLDER anID /uri notAValidLevel"

    val triedElement = ElementSerializer.deserialize[FsElement](ser)
    triedElement.isFailure shouldBe true
  }

  /**
    * Helper method for testing the deserialization of a sync operation using
    * a specific action.
    *
    * @param action the action
    */
  private def checkDeserializeOperation(action: SyncAction): Unit =
    val file = FsFile("the ID", "my/test/data file.txt", 2, Instant.parse("2018-09-06T19:31:33.529Z"),
      20180906193152L)
    val operation = SyncOperation(file, action, 22, dstID = "the destination ID")
    val opRaw = ElementSerializer serialize operation

    ElementSerializer.deserialize[SyncOperation](opRaw.utf8String) match
      case Success(op) =>
        op should be(operation)
      case r =>
        fail("Unexpected result: " + r)

  it should "deserialize a create operation" in {
    checkDeserializeOperation(ActionCreate)
  }

  it should "deserialize an override operation" in {
    checkDeserializeOperation(ActionOverride)
  }

  it should "deserialize a remove operation" in {
    checkDeserializeOperation(ActionRemove)
  }

  it should "deserialize a local create operation" in {
    checkDeserializeOperation(ActionLocalCreate)
  }

  it should "deserialize a local override operation" in {
    checkDeserializeOperation(ActionLocalOverride)
  }

  it should "deserialize a local remove operation" in {
    checkDeserializeOperation(ActionLocalRemove)
  }

  it should "handle a deserialization of an invalid action tag" in {
    val raw = "DELETE 13 FOLDER /foo/bar 8"

    val triedOperation = ElementSerializer.deserialize[SyncOperation](raw)
    triedOperation.isSuccess shouldBe false
  }
