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

import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.{FsElement, SyncAction, SyncOperation, SyncOperationResult}
import com.github.sync.stream.ThrottleSpec.createResult
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.{AnyFlatSpec, AnyFlatSpecLike}
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}

object ThrottleSpec:
  /**
    * Creates a ''SyncOperation'' for the given parameters.
    *
    * @param elem   the affected element
    * @param action the action
    * @return the ''SyncOperation''
    */
  private def createOp(elem: FsElement, action: SyncAction): SyncOperation =
    SyncOperation(elem, action, 1, elem.id)

  /**
    * Creates a result for the given ''SyncOperation''.
    *
    * @param op the operation
    * @return the ''SyncOperationResult'' for the operation
    */
  private def createResult(op: SyncOperation): SyncOperationResult = SyncOperationResult(op, None)

  /**
    * Creates the results for the given sequence of operations.
    *
    * @param operations the operations
    * @return a sequence with the results for these operations
    */
  private def createResults(operations: Seq[SyncOperation]): Seq[SyncOperationResult] = operations map createResult

/**
  * Test class for ''Throttle''.
  */
class ThrottleSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike, BeforeAndAfterAll, Matchers,
  AsyncTestHelper:
  def this() = this(ActorSystem("ThrottleSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  import ThrottleSpec.*

  "TimeUnit" should "support seconds" in {
    Throttle.TimeUnit.Second.baseDuration should be(1.second)
  }

  it should "support minutes" in {
    Throttle.TimeUnit.Minute.baseDuration should be(1.minute)
  }

  it should "support hours" in {
    Throttle.TimeUnit.Hour.baseDuration should be(1.hour)
  }

  "Throttle" should "handle an empty stream" in {
    val helper = new ThrottleTestHelper

    futureResult(helper.runStream(Seq.empty, 100)) shouldBe empty
  }

  it should "handle a stream consisting only of Noop actions" in {
    val operations = (1 to 32) map { idx =>
      createOp(AbstractStageSpec.createFile(idx), SyncAction.ActionNoop)
    }
    val helper = new ThrottleTestHelper

    val results = futureResult(helper.runStream(operations, 1))
    results should contain theSameElementsInOrderAs createResults(operations)
    helper.expectNoMoreThrottledOperations()
  }

  it should "handle a stream consisting only of real actions" in {
    val operations = (1 to 32) map { idx =>
      createOp(AbstractStageSpec.createFile(idx), SyncAction.ActionRemove)
    }
    val expectedResults = createResults(operations)
    val helper = new ThrottleTestHelper

    val results = futureResult(helper.runStream(operations, 100))
    results should contain theSameElementsInOrderAs expectedResults
    helper.nextThrottledOperations(operations.size) should contain theSameElementsInOrderAs operations
  }

  it should "handle a stream containing operations with different types of actions" in {
    val operations = List(
      createOp(AbstractStageSpec.createFile(1), SyncAction.ActionRemove),
      createOp(AbstractStageSpec.createFile(2), SyncAction.ActionNoop),
      createOp(AbstractStageSpec.createFile(3), SyncAction.ActionNoop),
      createOp(AbstractStageSpec.createFile(4), SyncAction.ActionNoop),
      createOp(AbstractStageSpec.createFile(5), SyncAction.ActionRemove),
      createOp(AbstractStageSpec.createFile(6), SyncAction.ActionNoop),
      createOp(AbstractStageSpec.createFile(7), SyncAction.ActionRemove),
      createOp(AbstractStageSpec.createFile(8), SyncAction.ActionRemove),
      createOp(AbstractStageSpec.createFile(9), SyncAction.ActionNoop)
    )
    val expectedResults = createResults(operations)
    val helper = new ThrottleTestHelper

    val results = futureResult(helper.runStream(operations, 100))
    results should contain theSameElementsInOrderAs expectedResults
  }

  it should "apply the throttling to the passed in stage" in {
    val operations = (1 to 4) map { idx =>
      createOp(AbstractStageSpec.createFile(idx), SyncAction.ActionRemove)
    }
    val expectedResults = createResults(operations)
    val helper = new ThrottleTestHelper

    val futResult = helper.runStream(operations, 1)
    intercept[TimeoutException] {
      Await.ready(futResult, 1500.millis)
    }
  }

  it should "allow throttling per time unit" in {
    val NumberOfOps = 20
    val operations = (1 to NumberOfOps) map { idx =>
      createOp(AbstractStageSpec.createFile(idx), SyncAction.ActionRemove)
    }
    val helper = new ThrottleTestHelper

    val futResult = helper.runStream(operations, NumberOfOps, Throttle.TimeUnit.Minute)
    intercept[TimeoutException] {
      Await.ready(futResult, 1500.millis)
    }
  }

  it should "keep the order even if operations are throttled" in {
    val operations = List(
      createOp(AbstractStageSpec.createFile(1), SyncAction.ActionRemove),
      createOp(AbstractStageSpec.createFile(2), SyncAction.ActionNoop),
      createOp(AbstractStageSpec.createFile(3), SyncAction.ActionNoop),
      createOp(AbstractStageSpec.createFile(4), SyncAction.ActionNoop),
      createOp(AbstractStageSpec.createFile(5), SyncAction.ActionRemove),
      createOp(AbstractStageSpec.createFile(6), SyncAction.ActionNoop),
      createOp(AbstractStageSpec.createFile(7), SyncAction.ActionRemove),
      createOp(AbstractStageSpec.createFile(8), SyncAction.ActionRemove),
      createOp(AbstractStageSpec.createFile(9), SyncAction.ActionNoop)
    )
    val expectedResults = createResults(operations)
    val helper = new ThrottleTestHelper

    val results = futureResult(helper.runStream(operations, 2))
    results should contain theSameElementsInOrderAs expectedResults
  }

  private class ThrottleTestHelper:
    /**
      * A queue where to store the operations passed through the throttled
      * stage.
      */
    private val throttledOperations = new LinkedBlockingQueue[SyncOperation]()

    /**
      * Executes a stream that passes the given operations through a throttled
      * test stage.
      *
      * @param operations the operations to pass through the stream
      * @param opsPerUnit the number of allowed operations per time unit
      * @param unit       the time unit
      * @return A ''Future'' with the output of the throttled stage
      */
    def runStream(operations: Seq[SyncOperation], opsPerUnit: Int,
                  unit: Throttle.TimeUnit = Throttle.TimeUnit.Second): Future[Seq[SyncOperationResult]] =
      implicit val ec: ExecutionContext = system.dispatcher
      val source = Source(operations)
      val stage = Flow[SyncOperation].map { op =>
        throttledOperations.offer(op)
        createResult(op)
      }
      val throttledStage = Throttle(stage, opsPerUnit, unit)
      val sink = AbstractStageSpec.foldSink[SyncOperationResult]
      source.via(throttledStage).runWith(sink).map(_.reverse)

    /**
      * Returns the next operation that has been passed to the throttled stage.
      * If there is no such operation, the test fails.
      *
      * @return the next throttled operation
      */
    def nextThrottledOperation: SyncOperation =
      val op = throttledOperations.poll(3, TimeUnit.SECONDS)
      op should not be null
      op

    /**
      * Returns the given number of operations passed to the throttled stage.
      * If there is a timeout when querying these operations, the test fails.
      *
      * @param count the number of operations to query
      * @return a sequence with the operations that have been obtained
      */
    def nextThrottledOperations(count: Int): Seq[SyncOperation] =
      (1 to count) map (_ => nextThrottledOperation)

    /**
      * Checks that no more operations have been passed to the throttled stage.
      */
    def expectNoMoreThrottledOperations(): Unit =
      val op = throttledOperations.poll(500, TimeUnit.MILLISECONDS)
      op should be(null)
