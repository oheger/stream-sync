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

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.testkit.TestKit
import com.github.sync.{AsyncTestHelper, FileTestHelper, SyncTypes}
import com.github.sync.SyncTypes.{FsFile, SyncOperation, SyncOperationResult}
import com.github.sync.log.ElementSerializer
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.time.Instant
import scala.concurrent.Future
import scala.util.Success

object SyncStreamSpec:
  /** Name of an element that produce a failed operation result. */
  private val ErrorFileName = "error"

  /** Name of an element that produces a success operation result. */
  private val SuccessFileName = "element"

  /**
    * A special exception class to report operation errors. For the tests, an
    * exception class is needed that has a meaningful ''equals()'' method.
    *
    * @param msg the error message
    */
  case class SyncStreamException(msg: String) extends Exception(msg)

  /**
    * Creates a test sync operation based on the given index. The name of the
    * element subject of the operation is determined by the ''success'' flag.
    * The test processing stage will generate a failure result depending on
    * this name.
    *
    * @param index   the index
    * @param success flag whether the operation should be successful
    * @return the test ''SyncOperation''
    */
  private def createOperation(index: Int, success: Boolean = true): SyncOperation =
    val elemName = if success then SuccessFileName else ErrorFileName
    val element = FsFile("id" + index, s"/$elemName$index", level = index / 10,
      lastModified = Instant.parse("2021-11-04T12:22:45Z"), size = 100 * index + 1)
    SyncOperation(element, SyncTypes.SyncAction.ActionOverride, element.level, "id" + index)

  /**
    * Creates the result for a ''SyncOperation''. Depending on the URL of the
    * element subject of the operation, the result is either successful or a
    * failure.
    *
    * @param op the operation in question
    * @return the result for this operation
    */
  private def createOperationResult(op: SyncOperation): SyncOperationResult =
    SyncOperationResult(op,
      if op.element.relativeUri.contains(ErrorFileName) then Some(SyncStreamException(op.toString + " failed!"))
      else None)

  /**
    * Returns a ''Flow'' to simulate the processing stage. This flow checks
    * whether the element subject of the current operation contains the error
    * name in its URL. If so, a failed operation is simulated; otherwise, the
    * operation is passed through.
    *
    * @return a ''Flow'' simulating the processing stage
    */
  private def processingFlow: Flow[SyncOperation, SyncOperationResult, Any] =
    Flow[SyncOperation].map(createOperationResult)

  /**
    * Returns a ''Sink'' that collects all sync operations passed to it. The
    * operations are stored in this list in reversed order.
    *
    * @return the collecting ''Sink''
    */
  private def collectingSink: Sink[SyncOperationResult, Future[List[SyncOperationResult]]] =
    Sink.fold[List[SyncOperationResult], SyncOperationResult](List.empty) { (lst, op) => op :: lst }

  /**
    * Convenience function to construct an object with parameters for a sync
    * stream. Some defaults are already set.
    *
    * @param operations the operations to pass through the stream
    * @param sinkTotal  the sink receiving all elements
    * @param sinkError  the sink receiving the error elements
    * @tparam TOTAL the type of the total sink
    * @tparam ERROR the type of the error sink
    * @return the parameters object
    */
  private def syncParams[TOTAL, ERROR](operations: List[SyncOperation],
                                       sinkTotal: Sink[SyncOperationResult, Future[TOTAL]],
                                       sinkError: Sink[SyncOperationResult, Future[ERROR]] = Sink.ignore):
  SyncStream.SyncStreamParams[TOTAL, ERROR] =
    SyncStream.SyncStreamParams(source = Source(operations), processFlow = processingFlow,
      sinkTotal = sinkTotal, sinkError = sinkError)

/**
  * Test class for ''SyncStream''.
  */
class SyncStreamSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike, BeforeAndAfterAll,
  BeforeAndAfterEach, Matchers, AsyncTestHelper, FileTestHelper :
  def this() = this(ActorSystem("SyncStreamSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  override protected def afterEach(): Unit =
    super.afterEach()
    tearDownTestFile()

  import SyncStreamSpec.*

  /**
    * Constructs and runs a test stream based on the given parameters.
    *
    * @param params the parameters of the test stream
    * @tparam TOTAL type of the total sink
    * @tparam ERROR type of the error sink
    * @return the materialized stream result
    */
  private def runStream[TOTAL, ERROR](params: SyncStream.SyncStreamParams[TOTAL, ERROR]):
  SyncStream.SyncStreamMat[TOTAL, ERROR] =
    import system.dispatcher
    val graph = SyncStream.createSyncStream(params)
    futureResult(graph.run())

  "SyncStream" should "pass all operations to the total sink" in {
    val operations = List(createOperation(1), createOperation(2), createOperation(3))
    val expResults = operations map createOperationResult
    val params = syncParams(operations, collectingSink)

    val result = runStream(params)
    result.totalSinkMat.reverse should contain theSameElementsInOrderAs expResults
    result.errorSinkMat should be(Done)
  }

  it should "pass only failed operations to the error sink" in {
    val operations = List(createOperation(1), createOperation(2, success = false), createOperation(3))
    val expTotalResults = operations map createOperationResult
    val params = syncParams(operations, collectingSink, collectingSink)

    val result = runStream(params)
    result.totalSinkMat.reverse should contain theSameElementsInOrderAs expTotalResults
    result.errorSinkMat should contain only createOperationResult(operations(1))
  }

  it should "support the creation of a log sink" in {
    val operations = List(createOperation(1), createOperation(2, success = false), createOperation(3))
    val logFile = createFileReference()
    val params = syncParams(operations, SyncStream.createLogSink(logFile))

    val result = runStream(params)
    result.totalSinkMat.status should be(Success(Done))
    val logLines = Files.readAllLines(logFile)
    logLines.get(0) + System.lineSeparator() should be(ElementSerializer.serializeOperation(operations.head)
      .utf8String)
    logLines.get(2) should include(operations(1).toString + " failed")
  }

  it should "create a log sink that appends to an existing log file" in {
    val OriginalContent = "This is an existing log line."
    val operations = List(createOperation(1))
    val logFile = writeFileContent(createFileReference(), OriginalContent + System.lineSeparator())
    val params = syncParams(operations, SyncStream.createLogSink(logFile))

    val result = runStream(params)
    val logLines = Files.readAllLines(logFile)
    logLines.get(0) should be(OriginalContent)
    logLines.get(1) + System.lineSeparator() should be(ElementSerializer.serializeOperation(operations.head)
      .utf8String)
  }

  it should "add a logging sink to an existing sink" in {
    import system.dispatcher
    val operations = List(createOperation(1), createOperation(2))
    val expTotalResults = operations map createOperationResult
    val logFile = createFileReference()
    val sink = SyncStream.sinkWithLogging(collectingSink, logFile)
    val params = syncParams(operations, sink)

    val result = runStream(params)
    result.totalSinkMat.reverse should contain theSameElementsInOrderAs expTotalResults
    val logLines = Files.readAllLines(logFile)
    logLines.get(0) + System.lineSeparator() should be(ElementSerializer.serializeOperation(operations.head)
      .utf8String)
  }

  it should "create a counting sink" in {
    val OperationCount = 16
    val operations = (1 to OperationCount) map (idx => createOperation(idx))
    val params = syncParams(operations.toList, SyncStream.createCountSink())

    val result = runStream(params)
    result.totalSinkMat should be(OperationCount)
  }
  