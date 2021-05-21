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

package com.github.sync.impl

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.stream.scaladsl.{Sink, Source}
import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.{ActionRemove, FsFolder, SyncOperation}
import com.github.sync.http.SyncOperationRequestActor.SyncOperationResult
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import scala.concurrent.{ExecutionContext, Future}

object ProtocolOperationHandlerStageSpec {
  /** A test operation passed to the test stage. */
  private val TestOp = SyncOperation(element = FsFolder("f1", "/test/folder", 1), action = ActionRemove,
    level = 1, srcUri = "srcID", dstUri = "dstID")
}

/**
  * Test class for ''ProtocolOperationHandlerStageSpec''.
  */
class ProtocolOperationHandlerStageSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with MockitoSugar with AsyncTestHelper {

  import ProtocolOperationHandlerStageSpec._

  /**
    * Runs the test stage in a stream against a single sync operation using a
    * mock ''ProtocolOperationHandler''. The result is returned.
    *
    * @param handler the mock protocol handler
    * @return the result produced by the stage
    */
  private def runStage(handler: ProtocolOperationHandler): SyncOperationResult = {
    implicit val ec: ExecutionContext = system.executionContext
    val source = Source.single(TestOp)
    val stage = ProtocolOperationHandlerStage(handler)
    val sink = Sink.fold[List[SyncOperationResult], SyncOperationResult](List.empty) { (lst, e) => e :: lst }

    val results = futureResult(source.via(stage).runWith(sink))
    results should have size 1
    results.head
  }

  "ProtocolOperationHandlerStage" should "process a successful operation" in {
    val handler = mock[ProtocolOperationHandler]
    when(handler.execute(TestOp)).thenReturn(Future.successful(()))

    val result = runStage(handler)
    result should be(SyncOperationResult(TestOp, None))
  }

  it should "process a failed operation" in {
    val exception = new IOException("Test exception: Operation failed")
    val handler = mock[ProtocolOperationHandler]
    when(handler.execute(TestOp)).thenReturn(Future.failed(exception))

    val result = runStage(handler)
    result should be(SyncOperationResult(TestOp, Some(exception)))
  }
}
