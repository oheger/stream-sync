/*
 * Copyright 2018-2025 The Developers Team.
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

import com.github.sync.SyncTypes.{FsElement, FsFile, SyncAction, SyncConflictException, SyncOperation, SyncOperationResult}
import com.github.sync.cli.FilterManager.SyncOperationFilter
import com.github.sync.log.ElementSerializer
import com.github.sync.stream.AbstractStageSpec.createFile
import com.github.sync.{AsyncTestHelper, FileTestHelper, SyncTypes}
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Flow, Sink, Source}
import org.apache.pekko.stream.{KillSwitches, SharedKillSwitch}
import org.apache.pekko.testkit.TestKit
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.nio.file.Files
import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.io.Source as IOSource
import scala.util.{Success, Using}

object SyncStreamSpec:
  /** Name of an element that produce a failed operation result. */
  private val ErrorFileName = "error"

  /** Name of an element that produces a success operation result. */
  private val SuccessFileName = "success"

  /** A name for sync streams used by tests. */
  private val StreamName = "TestSyncStream"

  /**
    * A special exception class to report operation errors. For the tests, an
    * exception class is needed that has a meaningful ''equals()'' method.
    *
    * @param msg the error message
    */
  case class SyncStreamException(msg: String) extends Exception(msg)

  /**
    * Creates a test element based on the given index. The name of the element
    * subject of the operation is determined by the ''success'' flag. The test
    * processing stage will generate a failure result depending on this name.
    *
    * @param index   the index
    * @param success flag whether the element can be processed successfully
    * @return the test element
    */
  private def createElement(index: Int, success: Boolean = true): FsFile =
    successState(createFile(index), success)

  /**
    * Modifies the passed in element by adding a suffix to the URL that
    * determines whether the element could be processed successfully. This is
    * used to simulate failed operations.
    *
    * @param element the original element
    * @param success the success state
    * @return the modified element
    */
  private def successState(element: FsFile, success: Boolean): FsFile =
    val elemName = if success then SuccessFileName else ErrorFileName
    element.copy(relativeUri = s"${element.relativeUri}$elemName")

  /**
    * Creates a test sync operation based on the given index. This is an
    * operation using an element created by ''createElement()'' as subject.
    *
    * @param index   the index
    * @param success flag whether the operation should be successful
    * @return the test ''SyncOperation''
    */
  private def createOperation(index: Int, success: Boolean = true): SyncOperation =
    val element = createElement(index, success)
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
    AbstractStageSpec.foldSink[SyncOperationResult]

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
    * Creates the parameters object for a sync stream setting some default
    * values.
    *
    * @param localSource   the source for local elements
    * @param remoteSource  the source for remote elements
    * @param sinkTotal     the sink receiving all elements
    * @param sinkError     the sink receiving the error elements
    * @param filter        the filter for operations
    * @param optKillSwitch the optional kill switch
    * @param ignoreDelta   the ignore time delta
    * @param dryRun        the dry-run flag
    * @tparam TOTAL the type of the total sink
    * @tparam ERROR the type of the error sink
    * @return
    */
  private def syncParams[TOTAL, ERROR](localSource: Source[FsElement, Any],
                                       remoteSource: Source[FsElement, Any],
                                       sinkTotal: Sink[SyncOperationResult, Future[TOTAL]],
                                       sinkError: Sink[SyncOperationResult, Future[ERROR]] = Sink.ignore,
                                       filter: SyncStream.OperationFilter = SyncStream.AcceptAllOperations,
                                       optKillSwitch: Option[SharedKillSwitch] = None,
                                       ignoreDelta: IgnoreTimeDelta = IgnoreTimeDelta.Zero,
                                       dryRun: Boolean = false):
  SyncStream.SyncStreamParams[TOTAL, ERROR] =
    val baseParams = SyncStream.BaseStreamParams(processFlow = processingFlow, sinkTotal = sinkTotal,
      sinkError = sinkError, operationFilter = filter, optKillSwitch = optKillSwitch)
    SyncStream.SyncStreamParams(baseParams = baseParams, streamName = StreamName, stateFolder = testDirectory,
      localSource = localSource, remoteSource = remoteSource, ignoreDelta = ignoreDelta, dryRun = dryRun)

  /**
    * Writes a file with local state information with the given content.
    *
    * @param state the content of the local state file
    */
  private def writeLocalState(state: Seq[LocalState.LocalElementState]): Unit =
    val stateFolder = LocalState.LocalStateFolder(testDirectory, StreamName)
    val sink = FileIO.toPath(stateFolder.resolve(LocalState.LocalStateFile.Complete))
    val source = Source(state).map(ElementSerializer.serialize)
    futureResult(source.runWith(sink))

  /**
    * Writes a file with local state information for the test stream that
    * contains the given elements.
    *
    * @param elements the elements in the local state
    */
  private def writeLocalStateElements(elements: Seq[FsElement]): Unit =
    writeLocalState(elements.map(elem => LocalState.LocalElementState(elem, removed = false)))

  /**
    * Reads the file with the local state of the test stream and deserializes
    * the elements stored there.
    *
    * @return a sequence with the elements in the local state
    */
  private def readLocalState(): Seq[LocalState.LocalElementState] =
    val stateFolder = LocalState.LocalStateFolder(testDirectory, StreamName)
    Using(IOSource.fromFile(stateFolder.resolve(LocalState.LocalStateFile.Complete).toFile)) { src =>
      src.getLines().toList.map(ElementSerializer.deserialize[LocalState.LocalElementState])
    }.get map (_.get)

  /**
    * Constructs and starts a test mirror stream based on the given parameters.
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
    for
      graph <- SyncStream.createMirrorStream(params)
      result <- graph.run()
    yield result

  /**
    * Constructs and runs a test mirror stream based on the given parameters.
    * Waits for the completion of the stream and returns the materialized
    * result.
    *
    * @param params the parameters of the test stream
    * @tparam TOTAL type of the total sink
    * @tparam ERROR type of the error sink
    * @return the materialized stream result
    */
  private def runStream[TOTAL, ERROR](params: SyncStream.MirrorStreamParams[TOTAL, ERROR]):
  SyncStream.SyncStreamMat[TOTAL, ERROR] = futureResult(startStream(params))

  /**
    * Constructs and starts a test sync stream based on the given parameters.
    * Returns the ''Future'' with the result.
    *
    * @param params the parameters of the test stream
    * @tparam TOTAL type of the total sink
    * @tparam ERROR type of the error sink
    * @return the ''Future'' with the materialized stream result
    */
  private def startStream[TOTAL, ERROR](params: SyncStream.SyncStreamParams[TOTAL, ERROR]):
  Future[SyncStream.SyncStreamMat[TOTAL, ERROR]] =
    import system.dispatcher
    for
      graph <- SyncStream.createSyncStream(params)
      result <- graph.run()
    yield result

  /**
    * Constructs and runs a test sync stream based on the given parameters.
    * Waits for the completion of the stream and returns the materialized
    * result.
    *
    * @param params the parameters of the test stream
    * @tparam TOTAL type of the total sink
    * @tparam ERROR type of the error sink
    * @return the materialized stream result
    */
  private def runStream[TOTAL, ERROR](params: SyncStream.SyncStreamParams[TOTAL, ERROR]):
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

    runStream(params)
    val logLines = Files.readAllLines(logFile)
    logLines.get(0) + System.lineSeparator() should be(ElementSerializer.serialize(operations.head).utf8String)
    logLines.get(1) + System.lineSeparator() should be(ElementSerializer.serialize(operations(1)).utf8String)
  }

  it should "support the creation of an error log sink" in {
    val operations = List(createOperation(1), createOperation(2, success = false), createOperation(3))
    val logFile = createFileReference()
    val params = mirrorParams(operations, SyncStream.createLogSink(logFile, errorLog = true))

    runStream(params)
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

  it should "create a sync stream" in {
    val localFile = createFile(1)
    val remoteFile = createFile(2, idType = "remote")
    val localElements = List(localFile)
    val remoteElements = List(remoteFile)
    val expOps = List(SyncOperation(localFile, SyncAction.ActionCreate, localFile.level, localFile.id),
      SyncOperation(remoteFile, SyncAction.ActionLocalCreate, remoteFile.level, remoteFile.id))
    val params = syncParams(Source(localElements), Source(remoteElements), collectingSink, collectingSink)

    val result = runStream(params)
    result.totalSinkMat should contain theSameElementsInOrderAs expOps.map(createOperationResult).reverse
    result.errorSinkMat shouldBe empty
  }

  it should "create a correct local source for the local state" in {
    val remoteFile = createFile(1, idType = "remote")
    writeLocalStateElements(List(createFile(1)))
    val expOp = SyncOperation(remoteFile, SyncAction.ActionRemove, remoteFile.level, remoteFile.id)
    val params = syncParams(Source.empty, Source(List(remoteFile)), collectingSink)

    val result = runStream(params)
    result.totalSinkMat should contain only createOperationResult(expOp)
  }

  it should "pass only failed operations to the error sink in a sync stream" in {
    val localElements = List(createElement(1), createElement(2, success = false),
      createElement(3))
    val remoteElements = List(successState(createFile(1, idType = "remote", deltaTime = 10), success = true),
      successState(createFile(2, idType = "remote", deltaTime = 20), success = false),
      successState(createFile(3, idType = "remote", deltaTime = 30), success = true))
    writeLocalStateElements(localElements)
    val expOps = localElements.zip(remoteElements).map { e =>
      SyncOperation(e._2, SyncAction.ActionLocalOverride, e._2.level, e._1.id)
    }
    val expResults = expOps map createOperationResult
    val params = syncParams(Source(localElements), Source(remoteElements), collectingSink, collectingSink)

    val result = runStream(params)
    result.totalSinkMat.reverse should contain theSameElementsInOrderAs expResults
    result.errorSinkMat should contain only expResults(1)
  }

  it should "support a kill switch for sync streams" in {
    val ElemCount = 16
    val localElements = (1 to ElemCount) map { idx => createFile(idx) }
    val remoteElements = (1 to ElemCount) map { idx => createFile(idx, idType = "remote") }
    writeLocalStateElements(localElements)
    val localSource = Source(localElements).delay(50.millis)
    val remoteSource = Source(remoteElements)
    val killSwitch = KillSwitches.shared("killSync")
    val params = syncParams(localSource, remoteSource, collectingSink, optKillSwitch = Some(killSwitch))
    val futResult = startStream(params)

    killSwitch.shutdown()
    val result = futureResult(futResult)
    result.totalSinkMat.size should be < ElemCount
  }

  it should "update the local state of a sync stream" in {
    val unchangedElement = createFile(1)
    val changedElement = createFile(2, idType = "remote", deltaTime = 100)
    val localElements = List(unchangedElement, createFile(2))
    val remoteElements = List(createFile(1, idType = "remote"), changedElement)
    writeLocalStateElements(localElements)
    val params = syncParams(Source(localElements), Source(remoteElements), collectingSink)
    val expState = List(LocalState.LocalElementState(unchangedElement, removed = false),
      LocalState.LocalElementState(changedElement, removed = false))

    runStream(params)
    val state = readLocalState()
    state should contain theSameElementsInOrderAs expState
  }

  it should "pass sync conflicts to the error sink" in {
    val localChangedElement = createFile(1, deltaTime = 77)
    val remoteChangedElement = createFile(1, deltaTime = 99)
    val localUnchangedElement = createFile(1)
    writeLocalStateElements(List(localUnchangedElement))
    val params = syncParams(Source(List(localChangedElement)), Source(List(remoteChangedElement)),
      collectingSink, collectingSink)

    val result = runStream(params)
    result.errorSinkMat should have size 1
    result.errorSinkMat.head.optFailure.get match
      case e: SyncConflictException =>
        e.localOperations should contain only SyncOperation(remoteChangedElement, SyncAction.ActionLocalOverride,
          remoteChangedElement.level, localChangedElement.id)
        e.remoteOperations should contain only SyncOperation(localChangedElement, SyncAction.ActionOverride,
          localChangedElement.level, remoteChangedElement.id)
      case e2 => fail("Unexpected exception: " + e2)

    val state = readLocalState()
    state should contain only LocalState.LocalElementState(localChangedElement, removed = false)
  }

  it should "support an operation filter for sync streams" in {
    val unchangedElement = createFile(1)
    val deletedElement = createFile(2)
    val localElements = List(unchangedElement, deletedElement)
    val unchangedRemoteElement = createFile(1, idType = "remote")
    writeLocalStateElements(localElements)
    val opFilter: SyncOperationFilter = op => op.action != SyncAction.ActionLocalRemove
    val params = syncParams(Source(localElements), Source(List(unchangedRemoteElement)),
      collectingSink, filter = opFilter)
    val expOps = List(SyncOperation(unchangedElement, SyncAction.ActionNoop, unchangedElement.level,
      unchangedRemoteElement.id),
      SyncOperation(deletedElement, SyncAction.ActionNoop, deletedElement.level, deletedElement.id))
      .map(createOperationResult)
    val expState = localElements.map(elem => LocalState.LocalElementState(elem, removed = false))

    val result = runStream(params)
    result.totalSinkMat.reverse should contain theSameElementsInOrderAs expOps
    val state = readLocalState()
    state should contain theSameElementsInOrderAs expState
  }

  it should "support a dry-run flag for sync streams" in {
    val unchangedElement = createFile(1)
    val changedElement = createFile(2, idType = "remote", deltaTime = 100)
    val localElements = List(unchangedElement, createFile(2))
    val remoteElements = List(createFile(1, idType = "remote"), changedElement)
    writeLocalStateElements(localElements)
    val params = syncParams(Source(localElements), Source(remoteElements), collectingSink, dryRun = true)
    val expState = localElements map { elem => LocalState.LocalElementState(elem, removed = false) }

    val result = runStream(params)
    result.totalSinkMat should have size 2
    val state = readLocalState()
    state should contain theSameElementsInOrderAs expState
  }

  it should "support a threshold for time deltas in sync streams" in {
    val DeltaTime = 11
    val unchangedElement = createFile(1)
    val changedElement = createFile(2, idType = "remote", deltaTime = DeltaTime + 1)
    val localElements = List(unchangedElement, createFile(2))
    val remoteElements = List(createFile(1, idType = "remote", deltaTime = DeltaTime), changedElement)
    writeLocalStateElements(localElements)
    val expOps = List(SyncOperation(unchangedElement, SyncAction.ActionNoop, unchangedElement.level,
      remoteElements.head.id),
      SyncOperation(changedElement, SyncAction.ActionLocalOverride, changedElement.level, localElements(1).id))
      .map(createOperationResult)
    val params = syncParams(Source(localElements), Source(remoteElements), collectingSink,
      ignoreDelta = IgnoreTimeDelta(DeltaTime.seconds))

    val result = runStream(params)
    result.totalSinkMat.reverse should contain theSameElementsInOrderAs expOps
  }

  it should "create an import stream" in {
    val elements = (1 to 8) map (idx => createFile(idx))
    val params = syncParams(Source(elements), Source.empty, collectingSink, collectingSink)
    val expOps = elements map { elem =>
      val op = SyncOperation(elem, SyncAction.ActionLocalCreate, elem.level, elem.id)
      SyncOperationResult(op, optFailure = None)
    }
    val expState = elements map (elem => LocalState.LocalElementState(elem, removed = false))

    import system.dispatcher
    val result = futureResult(for
      graph <- SyncStream.createStateImportStream(params)
      res <- graph.run()
    yield res)

    result.errorSinkMat shouldBe empty
    result.totalSinkMat.reverse should contain theSameElementsInOrderAs expOps
    val state = readLocalState()
    state should contain theSameElementsInOrderAs expState
  }
