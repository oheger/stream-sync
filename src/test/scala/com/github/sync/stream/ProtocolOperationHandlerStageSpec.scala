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

import com.github.cloudfiles.core.http.factory.Spawner
import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.*
import com.github.sync.SyncTypes.SyncAction.*
import org.apache.pekko.actor.DeadLetter
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.time.Instant
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}

object ProtocolOperationHandlerStageSpec:
  /** A test operation passed to the test stage. */
  private val TestOp = createOp(createFolder(1), ActionRemove)

  /** The timeout when waiting for operations in the queue. */
  private val QueueTimeout = 3.seconds

  /** The timeout to wait when no operation is expected. */
  private val NoOperationTimeout = 500.millis

  /** The default level used for test sync operations. */
  private val Level = 2

  /** A name of the test operation handler actor. */
  private val ActorName = "theOperationHandlerActor"

  /**
    * A data class to track operations passed to the mock
    * [[ProtocolOperationHandler]] object. It allows querying the operation to
    * execute and marking it as completed.
    *
    * @param operation the operation to handle
    * @param promise   a promise to complete the operation
    */
  case class OperationInProgress(operation: SyncOperation, promise: Promise[Unit]):
    /**
      * Marks this operation has handled by completing the promise either
      * successfully or with a failure.
      *
      * @param optFailure an optional failure to report
      */
    def handle(optFailure: Option[Throwable] = None): Unit =
      optFailure.fold(promise.success(()))(promise.failure)

  /**
    * Generates a test folder based on the given index.
    *
    * @param index the index
    * @return the test folder with this index
    */
  private def createFolder(index: Int): FsFolder =
    FsFolder(s"folder$index", s"/test/folder$index", index)

  /**
    * Generates a test file based on the given index.
    *
    * @param index the index
    * @return the test file with this index
    */
  private def createFile(index: Int): FsFile =
    FsFile(s"file$index", s"/test/file$index.txt", index, Instant.now(), index * 1234)

  /**
    * Convenience function to create a sync operation with only relevant data.
    *
    * @param elem   the subject element of the operation
    * @param action the action
    * @param level  the level of this operation
    * @return the operation
    */
  private def createOp(elem: FsElement, action: SyncAction, level: Int = Level): SyncOperation =
    SyncOperation(element = elem, action = action, level = level, dstID = null)

/**
  * Test class for ''ProtocolOperationHandlerStageSpec''.
  */
class ProtocolOperationHandlerStageSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with MockitoSugar with AsyncTestHelper:

  import ProtocolOperationHandlerStageSpec.*

  /**
    * Tests whether a sync operation can only be executed after the completion
    * of another one.
    *
    * @param op1 the first operation (which blocks the other)
    * @param op2 the second operation
    */
  private def checkSerialExecution(op1: SyncOperation, op2: SyncOperation): Unit =
    val helper = new StageTestHelper

    val futResult = helper.runStage(List(op1, op2))
    val opInP = helper.expectOperation()
    helper.expectNoOperation()
    opInP.handle()
    helper.expectAndHandleOperation(op2)
    futureResult(futResult) should contain only(SyncOperationResult(op1, None),
      SyncOperationResult(op2, None))

  /**
    * Tests whether two sync operations can be executed in parallel; i.e. the
    * second operation does not have to wait for the first one to complete.
    *
    * @param op1 the first operation
    * @param op2 the second operation
    */
  private def checkParallelExecution(op1: SyncOperation, op2: SyncOperation): Unit =
    val helper = new StageTestHelper

    val futResult = helper.runStage(List(op1, op2))
    val opInP1 = helper.expectOperation()
    val opInP2 = helper.expectOperation()
    opInP1.operation should be(op1)
    opInP2.operation should be(op2)
    opInP1.handle()
    opInP2.handle()
    futureResult(futResult) should contain only(SyncOperationResult(op1, None),
      SyncOperationResult(op2, None))

  "ProtocolOperationHandlerStage" should "process a successful operation" in {
    val helper = new StageTestHelper

    val futResult = helper.runStage(List(TestOp))
    helper.expectAndHandleOperation(TestOp)
    futureResult(futResult) should contain only SyncOperationResult(TestOp, None)
  }

  it should "process a failed operation" in {
    val exception = new IOException("Test exception: Operation failed")
    val helper = new StageTestHelper

    val futResult = helper.runStage(List(TestOp))
    helper.expectAndHandleOperation(TestOp, Some(exception))
    futureResult(futResult) should contain only SyncOperationResult(TestOp, Some(exception))
  }

  it should "stop the internal actor after the stream is done" in {
    val helper = new StageTestHelper

    val futResult = helper.runStage(List(TestOp))
    helper.expectAndHandleOperation(TestOp)
    futureResult(futResult)

    val probe = testKit.createDeadLetterProbe()
    val ref = helper.handlerActorRef()
    val msg = mock[ProtocolOperationHandlerStage.OperationHandlerCommand]
    ref ! msg
    probe.expectMessageType[DeadLetter].message should be(msg)
  }

  it should "block a create folder operation of a higher level until a folder was created" in {
    val createOp1 = createOp(createFolder(1), ActionCreate)
    val createOp2 = createOp(createFolder(2), ActionCreate, level = Level + 1)
    checkSerialExecution(createOp1, createOp2)
  }

  it should "keep the order of pending operations when they are unblocked" in {
    val op1 = createOp(createFolder(1), ActionCreate)
    val op2 = createOp(createFolder(2), ActionCreate, level = Level + 1)
    val op3 = createOp(createFolder(3), ActionCreate, level = Level + 1)
    val op4 = createOp(createFolder(4), ActionCreate, level = Level + 1)
    val operations = List(op1, op2, op3, op4)
    val opResults = operations map (op => SyncOperationResult(op, None))
    val helper = new StageTestHelper

    val futResult = helper.runStage(operations)
    operations foreach { op =>
      helper.expectAndHandleOperation(op)
    }
    futureResult(futResult) should contain theSameElementsAs opResults
  }

  it should "run a create folder operation on the same level in parallel to another create folder operation" in {
    val createOp1 = createOp(createFolder(1), ActionCreate)
    val createOp2 = createOp(createFolder(2), ActionCreate)
    checkParallelExecution(createOp1, createOp2)
  }

  it should "run a delete folder operation in parallel to a create folder operation" in {
    val createFolderOp = createOp(createFolder(1), ActionCreate)
    val deleteFolderOp = createOp(createFolder(1), ActionRemove, level = Level + 1)
    checkParallelExecution(createFolderOp, deleteFolderOp)
  }

  it should "block a create file operation of a higher level until a folder was created" in {
    val createOp1 = createOp(createFolder(1), ActionCreate)
    val createOp2 = createOp(createFile(2), ActionCreate, level = Level + 1)
    checkSerialExecution(createOp1, createOp2)
  }

  it should "run a create file operation on the same level in parallel to a create folder operation" in {
    val createOp1 = createOp(createFolder(1), ActionCreate)
    val createOp2 = createOp(createFile(2), ActionCreate)
    checkParallelExecution(createOp1, createOp2)
  }

  it should "block a delete folder operation until a folder on a higher level is deleted" in {
    val deleteOp1 = createOp(createFolder(1), ActionRemove, level = Level + 1)
    val deleteOp2 = createOp(createFolder(2), ActionRemove)
    checkSerialExecution(deleteOp1, deleteOp2)
  }

  it should "block a delete folder operation until a file on a higher level is deleted" in {
    val deleteOp1 = createOp(createFile(1), ActionRemove, level = Level + 1)
    val deleteOp2 = createOp(createFolder(1), ActionRemove)
    checkSerialExecution(deleteOp1, deleteOp2)
  }

  it should "run delete file operations in parallel" in {
    val deleteOp1 = createOp(createFile(1), ActionRemove, level = Level + 1)
    val deleteOp2 = createOp(createFile(2), ActionRemove)
    checkParallelExecution(deleteOp1, deleteOp2)
  }

  it should "run override file operations in parallel to create folder operations" in {
    val createFolderOp = createOp(createFolder(1), ActionCreate)
    val overrideOp = createOp(createFile(1), ActionOverride, level = Level + 1)
    checkParallelExecution(createFolderOp, overrideOp)
  }

  it should "run override file operations in parallel to delete folder operations" in {
    val overrideOp = createOp(createFile(1), ActionOverride, level = Level + 1)
    val deleteOp = createOp(createFolder(1), ActionRemove)
    checkParallelExecution(overrideOp, deleteOp)
  }

  /**
    * A test helper class managing a stage to be tested and its dependencies.
    */
  private class StageTestHelper:
    /** The queue for tracking the operations to be handled. */
    private val operationQueue = new LinkedBlockingQueue[OperationInProgress]

    /** A queue that stores the references to newly created actors. */
    private val actorQueue = new LinkedBlockingQueue[ActorRef[ProtocolOperationHandlerStage.OperationHandlerCommand]]

    /**
      * Runs a stream with the specified operations against a test stage. The
      * processed operations are collected and can be obtained via the future
      * returned.
      *
      * @param operations the operations to execute
      * @return a ''Future'' with the resulting operations
      */
    def runStage(operations: List[SyncOperation]): Future[List[SyncOperationResult]] =
      val source = Source(operations)
      implicit val timeout: Timeout = Timeout(10.seconds)
      val stage = ProtocolOperationHandlerStage(createHandler(operationQueue), createSpawner(), Some(ActorName))
      val sink = Sink.fold[List[SyncOperationResult], SyncOperationResult](List.empty) { (lst, e) => e :: lst }
      source.via(stage).runWith(sink)

    /**
      * Expects that an operation was passed to the handler and returns data
      * about it.
      *
      * @return the data about the operation
      */
    def expectOperation(): OperationInProgress =
      val op = operationQueue.poll(QueueTimeout.toMillis, TimeUnit.MILLISECONDS)
      op should not be null
      op

    /**
      * Expects that the given operation was passed next to the protocol
      * handler and handles it.
      *
      * @param expOp      the expected operation
      * @param optFailure optional failure to mark the op as failed
      * @return this test helper
      */
    def expectAndHandleOperation(expOp: SyncOperation, optFailure: Option[Throwable] = None): StageTestHelper =
      val op = expectOperation()
      op.operation should be(expOp)
      op.handle(optFailure)
      this

    /**
      * Expects that no operation has been passed to the protocol handler for
      * a given time frame. This is used to test that some operation wait for
      * others to be completed.
      *
      * @return this test helper
      */
    def expectNoOperation(): StageTestHelper =
      operationQueue.poll(NoOperationTimeout.toMillis, TimeUnit.MILLISECONDS) should be(null)
      this

    /**
      * Returns the reference to the internal handler actor that was created
      * by the test stage.
      *
      * @return the internal handler actor
      */
    def handlerActorRef(): ActorRef[ProtocolOperationHandlerStage.OperationHandlerCommand] =
      val ref = actorQueue.poll(QueueTimeout.toMillis, TimeUnit.MILLISECONDS)
      ref should not be null
      ref

    /**
      * Creates a mock operation handler that records the operations to execute
      * in a queue. From there they can be obtained and handled manually.
      *
      * @param queue the queue to record operations
      * @return the mock operation handler
      */
    private def createHandler(queue: BlockingQueue[OperationInProgress]): ProtocolOperationHandler =
      val handler = mock[ProtocolOperationHandler]
      when(handler.execute(any(classOf[SyncOperation]))).thenAnswer((invocation: InvocationOnMock) => {
        val promise = Promise[Unit]()
        val op: SyncOperation = invocation.getArgument(0)
        queue.offer(OperationInProgress(op, promise))
        promise.future
      })
      handler

    /**
      * Creates a spawner that spawns new actors using the test kit.
      *
      * @return the spawner
      */
    private def createSpawner(): Spawner =
      new Spawner {
        override def spawn[T](behavior: Behavior[T], optName: Option[String], props: Props): ActorRef[T] =
          optName should be(Some(ActorName))
          val ref = testKit.spawn(behavior)
          actorQueue.offer(ref.asInstanceOf[ActorRef[ProtocolOperationHandlerStage.OperationHandlerCommand]])
          ref
      }
