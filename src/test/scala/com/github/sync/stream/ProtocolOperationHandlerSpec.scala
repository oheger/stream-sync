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

package com.github.sync.stream

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.sync.SyncTypes.*
import com.github.sync.SyncTypes.SyncAction.*
import com.github.sync.protocol.SyncProtocol
import com.github.sync.{ActorTestKitSupport, AsyncTestHelper, FileTestHelper}
import org.mockito.Mockito.{verify, verifyNoInteractions, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

object ProtocolOperationHandlerSpec:
  /** The ID of a test element. */
  private val ElementID = "testElementID"

  /** The parent path of a test element */
  private val ElementParent = "/path/to"

  /** The name of a test element. */
  private val ElementName = "the element"

  /** The encoded name of the test element. */
  private val EncodedElementName = "the%20element"

  /** The relative URI of a test element. */
  private val ElementUri = ElementParent + "/" + EncodedElementName

  /** A test file element. */
  private val TestFile = FsFile(id = ElementID + "_src", relativeUri = ElementUri, level = 17,
    lastModified = Instant.parse("2021-05-19T19:51:35.100Z"), size = 4711)

  /** A test folder element. */
  private val TestFolder = FsFolder(id = ElementID + "_src_fld", relativeUri = ElementUri, level = 35)

  /** A source simulating content of a test file. */
  private val FileContent = Source.single(ByteString(FileTestHelper.testBytes()))

  /**
    * Convenience function to create a ''SyncOperation'' with the given
    * parameters.
    *
    * @param elem   the affected element
    * @param action the action to execute on this element
    * @param dstID  the optional destination ID
    * @return the ''SyncOperation''
    */
  private def createOp(elem: FsElement, action: SyncAction, dstID: String = ElementID): SyncOperation =
    SyncOperation(elem, action, dstID = dstID, level = 0)

/**
  * Test class for ''ProtocolOperationHandler''.
  */
class ProtocolOperationHandlerSpec extends AnyFlatSpec with ActorTestKitSupport with Matchers
  with MockitoSugar with AsyncTestHelper :

  import ProtocolOperationHandlerSpec.*

  /**
    * Exposes the actor system's execution context in implicit scope.
    *
    * @return the execution context
    */
  private implicit def executionContext: ExecutionContext = system.executionContext

  "ProtocolOperationHandler" should "handle an operation to remove a file" in {
    val op = createOp(TestFile, ActionRemove)
    val protocol = mock[SyncProtocol]
    when(protocol.removeFile(ElementID)).thenReturn(Future.successful(()))
    val handler = new ProtocolOperationHandler(protocol, null)

    futureResult(handler.execute(op))
    verify(protocol).removeFile(ElementID)
  }

  it should "handle an operation to remove a folder" in {
    val op = createOp(TestFolder, ActionRemove)
    val protocol = mock[SyncProtocol]
    when(protocol.removeFolder(ElementID)).thenReturn(Future.successful(()))
    val handler = new ProtocolOperationHandler(protocol, null)

    futureResult(handler.execute(op))
    verify(protocol).removeFolder(ElementID)
  }

  it should "handle an operation to create a folder" in {
    val op = createOp(TestFolder, ActionCreate, dstID = null)
    val protocol = mock[SyncProtocol]
    when(protocol.createFolder(ElementParent, ElementName, TestFolder)).thenReturn(Future.successful(()))
    val handler = new ProtocolOperationHandler(protocol, null)

    futureResult(handler.execute(op))
    verify(protocol).createFolder(ElementParent, ElementName, TestFolder)
  }

  it should "handle an operation to create a file" in {
    val op = createOp(TestFile, ActionCreate, dstID = null)
    val protocol = mock[SyncProtocol]
    val downloadProtocol = mock[SyncProtocol]
    when(downloadProtocol.downloadFile(TestFile.id)).thenReturn(Future.successful(FileContent))
    when(protocol.createFile(ElementParent, ElementName, TestFile, FileContent)).thenReturn(Future.successful(()))
    val handler = new ProtocolOperationHandler(protocol, downloadProtocol)

    futureResult(handler.execute(op))
    verify(protocol).createFile(ElementParent, ElementName, TestFile, FileContent)
  }

  it should "handle an operation to override a file" in {
    val DstFile = TestFile.copy(id = ElementID)
    val op = createOp(TestFile, ActionOverride)
    val protocol = mock[SyncProtocol]
    val downloadProtocol = mock[SyncProtocol]
    when(downloadProtocol.downloadFile(TestFile.id)).thenReturn(Future.successful(FileContent))
    when(protocol.updateFile(DstFile, FileContent)).thenReturn(Future.successful(()))
    val handler = new ProtocolOperationHandler(protocol, downloadProtocol)

    futureResult(handler.execute(op))
    verify(protocol).updateFile(DstFile, FileContent)
  }

  it should "handle a Noop" in {
    val op = createOp(TestFile, ActionNoop)
    val protocol = mock[SyncProtocol]
    val handler = new ProtocolOperationHandler(protocol, null)

    futureResult(handler.execute(op))
    verifyNoInteractions(protocol)
  }

  it should "handle an unexpected operation" in {
    val op = createOp(null, ActionRemove)
    val handler = new ProtocolOperationHandler(mock[SyncProtocol], null)

    val exception = expectFailedFuture[IllegalStateException](handler.execute(op))
    exception.getMessage should include(op.toString)
  }
