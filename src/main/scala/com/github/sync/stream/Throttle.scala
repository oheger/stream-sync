/*
 * Copyright 2018-2024 The Developers Team.
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

import com.github.sync.SyncTypes.{FsElement, SyncAction, SyncOperation, SyncOperationResult}
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FanInShape2, FlowShape, Inlet, Outlet}

import scala.concurrent.duration.*

/**
  * A module to support limiting the throughput of operations in the sync
  * stream.
  *
  * While Akka Streams already offers such functionality via the ''throttle''
  * operator, sync streams have more complex requirements: Not all stream
  * elements are subject to throttling, but only operations that actually cause
  * changes on the destination. This especially excludes the Noop actions
  * generated for elements that do not change.
  *
  * This module therefore constructs a sub graph that splits operations and
  * applies the ''throttle'' operator only to the subset that have a real
  * effect.
  */
object Throttle:
  /**
    * An enum to allow defining time units for throttling operations.
    *
    * The cases defined here can be used to define throttling in a
    * fine-granular way, such as "20 ops per minute" or "500 ops per hour".
    */
  enum TimeUnit(val baseDuration: FiniteDuration):
    case Second extends TimeUnit(1.second)
    case Minute extends TimeUnit(1.minute)
    case Hour extends TimeUnit(1.hour)

  /**
    * Returns a ''Flow'' that applies throttling to the given stage.
    *
    * @param stage             the stage subject to throttling
    * @param operationsPerUnit the number of operations to pass per time unit
    * @param timeUnit          the time unit
    * @return the decorated ''Flow'' respecting the specified limit
    */
  def apply(stage: Flow[SyncOperation, SyncOperationResult, Any], operationsPerUnit: Int,
            timeUnit: TimeUnit = TimeUnit.Second): Flow[SyncOperation, SyncOperationResult, Any] =
    val filterThrottle = Flow[SyncOperation].filter(needsThrottling)
      .throttle(operationsPerUnit, timeUnit.baseDuration)
    val mapToResult = Flow[SyncOperation].map(toResult)
    Flow.fromGraph(GraphDSL.create() {
      implicit builder =>
        import GraphDSL.Implicits.*
        val broadcast = builder.add(Broadcast[SyncOperation](2))
        val merge = builder.add(new ThrottleMergeStage)
        broadcast ~> mapToResult ~> merge.in0
        broadcast ~> filterThrottle ~> stage ~> merge.in1
        FlowShape(broadcast.in, merge.out)
    })

  /**
    * Generates a ''SyncOperationResult'' for a ''SyncOperation'' that is not
    * passed to the throttled stage.
    *
    * @param op the ''SyncOperation''
    * @return the result for this operation
    */
  private def toResult(op: SyncOperation): SyncOperationResult = SyncOperationResult(op, None)

  /**
    * Checks whether the given operation is subject to throttling.
    *
    * @param op the operation in question
    * @return a flag whether throttling needs to be applied to this operation
    */
  private def needsThrottling(op: SyncOperation): Boolean = op.action != SyncAction.ActionNoop

  /**
    * A specialized stage implementation that handles the merging of elements
    * in a throttled sub graph.
    *
    * The basic merge stages cannot be used for this purpose, since they do not
    * retain the order of the original elements. This stage expects that on one
    * input all stream elements are passed, while on the second input only the
    * throttled elements come in. If an element that requires throttling is
    * received over the first input, the second one is pulled, and the element
    * obtained from there is emitted. Otherwise, the first input channel is
    * passed downstream.
    */
  private class ThrottleMergeStage
    extends GraphStage[FanInShape2[SyncOperationResult, SyncOperationResult, SyncOperationResult]] :
    val out: Outlet[SyncOperationResult] = Outlet[SyncOperationResult]("ThrottleMerge.out")
    val inAll: Inlet[SyncOperationResult] = Inlet[SyncOperationResult]("ThrottleMerge.inAll")
    val inThrottled: Inlet[SyncOperationResult] = Inlet[SyncOperationResult]("ThrottleMerge.inThrottle")

    override def shape: FanInShape2[SyncOperationResult, SyncOperationResult, SyncOperationResult] =
      new FanInShape2[SyncOperationResult, SyncOperationResult, SyncOperationResult](inAll, inThrottled, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        /**
          * The number of input ports that are finished. It is important to
          * complete this stage only if all ports are finished; otherwise,
          * elements at the end can be missed.
          */
        private var finishedPorts = 0

        setHandler(out, new OutHandler {
          override def onPull(): Unit =
            if finishedPorts == 0 then pull(inAll)
        })

        setHandler(inAll, new InHandler {
          override def onPush(): Unit =
            val result = grab(inAll)
            if needsThrottling(result.op) then pull(inThrottled)
            else push(out, result)

          override def onUpstreamFinish(): Unit = portFinished()
        })

        setHandler(inThrottled, new InHandler {
          override def onPush(): Unit =
            push(out, grab(inThrottled))

          override def onUpstreamFinish(): Unit = portFinished()
        })

        /**
          * Handles a finished input port. If all ports are done, this stage can
          * be completed.
          */
        private def portFinished(): Unit =
          finishedPorts += 1
          if finishedPorts == 2 then completeStage()
      }
