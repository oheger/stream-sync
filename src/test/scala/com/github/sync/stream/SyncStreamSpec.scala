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

package com.github.sync.stream

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{KillSwitches, SharedKillSwitch}
import akka.testkit.TestKit
import com.github.sync.SyncTypes.{FsFile, SyncAction, SyncOperation, SyncOperationResult}
import com.github.sync.log.ElementSerializer
import com.github.sync.stream.AbstractStageSpec.createFile
import com.github.sync.{AsyncTestHelper, FileTestHelper, SyncTypes}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.nio.file.Files
import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.*
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
    * Convenience function to construct an object with parameters for a mirror
    * stream. Some defaults are already set.
    *
    * @param operations    the operations to pass through the stream
    * @param sinkTotal     the sink receiving all elements
    * @param sinkError     the sink receiving the error elements
    * @param filter        the filter for operations
    * @param optKillSwitch the optional kill switch
    * @tparam TOTAL the type of the total sink
    * @tparam ERROR the type of the error sink
    * @return the parameters object
    */
  private def mirrorParams[TOTAL, ERROR](operations: List[SyncOperation],
                                         sinkTotal: Sink[SyncOperationResult, Future[TOTAL]],
                                         sinkError: Sink[SyncOperationResult, Future[ERROR]] = Sink.ignore,
                                         filter: SyncStream.OperationFilter = SyncStream.AcceptAllOperations,
                                         optKillSwitch: Option[SharedKillSwitch] = None):
  SyncStream.MirrorStreamParams[TOTAL, ERROR] =
    val baseParams = SyncStream.BaseStreamParams(processFlow = processingFlow, sinkTotal = sinkTotal,
      sinkError = sinkError, operationFilter = filter, optKillSwitch = optKillSwitch)
    SyncStream.MirrorStreamParams(source = Source(operations), baseParams = baseParams)

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
    * Constructs and starts a test stream based on the given parameters.
    * Returns the ''Future'' with the result.
    *
    * @param params the parameters of the test stream
    * @tparam TOTAL type of the total sink
    * @tparam ERROR type of the error sink
    * @return the ''Future'' with the materialized stream result
    */
  private def startStream[TOTAL, ERROR](params: SyncStream.MirrorStreamParams[TOTAL, ERROR]):
  Future[SyncStream.SyncStreamMat[TOTAL, ERROR]] =
    import system.dispatcher
    val graph = SyncStream.createMirrorStream(params)
    graph.run()

  /**
    * Constructs and runs a test stream based on the given parameters. Waits
    * for the completion of the stream and returns the materialized result.
    *
    * @param params the parameters of the test stream
    * @tparam TOTAL type of the total sink
    * @tparam ERROR type of the error sink
    * @return the materialized stream result
    */
  private def runStream[TOTAL, ERROR](params: SyncStream.MirrorStreamParams[TOTAL, ERROR]):
  SyncStream.SyncStreamMat[TOTAL, ERROR] = futureResult(startStream(params))

  "SyncStream" should "create a source for a mirror stream" in {
    val file1 = createFile(1)
    val file2 = createFile(2)
    val file3 = createFile(3)
    val file4 = createFile(4)
    val sourceElems = List(file1, file2, file3)
    val destElems = List(createFile(2, deltaTime = 1), createFile(3, deltaTime = 2), file4)
    val expOps = List(SyncOperation(file1, SyncAction.ActionCreate, file1.level, "-"),
      SyncOperation(file2, SyncAction.ActionNoop, file2.level, "-"),
      SyncOperation(file3, SyncAction.ActionOverride, file3.level, file3.id),
      SyncOperation(file4, SyncAction.ActionRemove, file4.level, file4.id))

    val mirrorSource = SyncStream.createMirrorSource(Source(sourceElems), Source(destElems),
      ignoreTimeDelta = IgnoreTimeDelta(1.second))
    val sink = AbstractStageSpec.foldSink[SyncOperation]
    val result = futureResult(mirrorSource.runWith(sink))
    result.reverse should contain theSameElementsInOrderAs expOps
  }

  it should "pass all operations to the total sink in a mirror stream" in {
    val operations = List(createOperation(1), createOperation(2), createOperation(3))
    val expResults = operations map createOperationResult
    val params = mirrorParams(operations, collectingSink)

    val result = runStream(params)
    result.totalSinkMat.reverse should contain theSameElementsInOrderAs expResults
    result.errorSinkMat should be(Done)
  }

  it should "pass only failed operations to the error sink in a mirror stream" in {
    val operations = List(createOperation(1), createOperation(2, success = false), createOperation(3))
    val expTotalResults = operations map createOperationResult
    val params = mirrorParams(operations, collectingSink, collectingSink)

    val result = runStream(params)
    result.totalSinkMat.reverse should contain theSameElementsInOrderAs expTotalResults
    result.errorSinkMat should contain only createOperationResult(operations(1))
  }

  it should "support an operation filter for mirror streams" in {
    val op1 = createOperation(1)
    val op3 = createOperation(3)
    val excludedOp = createOperation(2)
    val operations = List(op1, excludedOp, op3)
    val expResults = List(op1, op3) map createOperationResult
    val params = mirrorParams(operations, collectingSink, filter = op => op != excludedOp)

    val result = runStream(params)
    result.totalSinkMat should contain theSameElementsAs expResults
  }

  it should "support a kill switch for mirror streams" in {
    val operations = (1 to 8) map (idx => createOperation(idx))
    val killSwitch = KillSwitches.shared("killIt")
    val params = mirrorParams(operations.toList, collectingSink, optKillSwitch = Some(killSwitch))
    val delayedParams = params.copy(source = params.source.delay(100.millis))

    val futResult = startStream(delayedParams)
    killSwitch.shutdown()
    val result = futureResult(futResult)
    result.totalSinkMat.size should be < operations.size
  }

  it should "support the creation of a log sink" in {
    val operations = List(createOperation(1), createOperation(2, success = false), createOperation(3))
    val logFile = createFileReference()
    val params = mirrorParams(operations, SyncStream.createLogSink(logFile))

    val result = runStream(params)
    result.totalSinkMat.status should be(Success(Done))
    val logLines = Files.readAllLines(logFile)
    logLines.get(0) + System.lineSeparator() should be(ElementSerializer.serialize(operations.head).utf8String)
    logLines.get(1) + System.lineSeparator() should be(ElementSerializer.serialize(operations(1)).utf8String)
  }

  it should "support the creation of an error log sink" in {
    val operations = List(createOperation(1), createOperation(2, success = false), createOperation(3))
    val logFile = createFileReference()
    val params = mirrorParams(operations, SyncStream.createLogSink(logFile, errorLog = true))

    val result = runStream(params)
    result.totalSinkMat.status should be(Success(Done))
    val logLines = Files.readAllLines(logFile)
    logLines.get(0) + System.lineSeparator() should be(ElementSerializer.serialize(operations(1)).utf8String)
    logLines.get(1) should include(operations(1).toString + " failed")
  }

  it should "create a log sink that appends to an existing log file" in {
    val OriginalContent = "This is an existing log line."
    val operations = List(createOperation(1))
    val logFile = writeFileContent(createFileReference(), OriginalContent + System.lineSeparator())
    val params = mirrorParams(operations, SyncStream.createLogSink(logFile))

    runStream(params)
    val logLines = Files.readAllLines(logFile)
    logLines.get(0) should be(OriginalContent)
    logLines.get(1) + System.lineSeparator() should be(ElementSerializer.serialize(operations.head).utf8String)
  }

  it should "add a logging sink to an existing sink" in {
    import system.dispatcher
    val operations = List(createOperation(1), createOperation(2))
    val expTotalResults = operations map createOperationResult
    val logFile = createFileReference()
    val sink = SyncStream.sinkWithLogging(collectingSink, logFile)
    val params = mirrorParams(operations, sink)

    val result = runStream(params)
    result.totalSinkMat.reverse should contain theSameElementsInOrderAs expTotalResults
    val logLines = Files.readAllLines(logFile)
    logLines.get(0) + System.lineSeparator() should be(ElementSerializer.serialize(operations.head).utf8String)
  }

  it should "add an error logging sink to an existing sink" in {
    import system.dispatcher
    val operations = List(createOperation(1), createOperation(2, success = false))
    val expTotalResults = operations map createOperationResult
    val logFile = createFileReference()
    val sink = SyncStream.sinkWithLogging(collectingSink, logFile, errorLog = true)
    val params = mirrorParams(operations, sink)

    val result = runStream(params)
    result.totalSinkMat.reverse should contain theSameElementsInOrderAs expTotalResults
    val logLines = Files.readAllLines(logFile)
    logLines.get(0) + System.lineSeparator() should be(ElementSerializer.serialize(operations(1)).utf8String)
  }

  it should "create a counting sink" in {
    val OperationCount = 16
    val operations = (1 to OperationCount) map (idx => createOperation(idx))
    val params = mirrorParams(operations.toList, SyncStream.createCountSink())

    val result = runStream(params)
    result.totalSinkMat should be(OperationCount)
  }
  