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

import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.github.sync.impl.StatefulStage.StateMapFunction

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object StatefulStage {
  /**
    * A function to map a stream element based on a state.
    *
    * A function of this type is passed to [[StatefulStage]]. It is invoked for
    * each stream element with the current state value and returns a ''Future''
    * with a tuple of the processed element and the updated state.
    */
  type StateMapFunction[A, B, S] = (A, S) => Future[(B, S)]
}

/**
  * A stage implementation that applies a mapping function on stream elements
  * that depends on a state.
  *
  * This stage is similar to the standard map functionality, but the mapping
  * function expects a state value and returns an updated state. This stage
  * implementation mainly manages the state in a safe manner.
  *
  * @param initState the initial state
  * @param mapFunc   the mapping function
  * @param ec        the execution context
  * @tparam A the input type of this stage
  * @tparam B the output type of this stage
  * @tparam S the type of the state
  */
class StatefulStage[A, B, S](initState: S)(mapFunc: StateMapFunction[A, B, S])(implicit ec: ExecutionContext)
  extends GraphStage[FlowShape[A, B]] {
  val in: Inlet[A] = Inlet[A]("StatefulStage.in")
  val out: Outlet[B] = Outlet[B]("StatefulStage.out")

  override val shape: FlowShape[A, B] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      /** The current state managed by this state. */
      private var state = initState

      /** A flag whether an invocation of the mapping function is active. */
      private var callbackPending = false

      /**
        * Flag whether the stream is completed. Completion logic is not trivial
        * because of the asynchronous invocation of the mapping function.
        * Without additional means, the stream can already be finished before
        * the asynchronous callback is triggered. Then the last element is
        * missing.
        */
      private var completed = false

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val callback = getAsyncCallback[Try[(B, S)]](handleResult)
          mapFunc(grab(in), state) onComplete callback.invoke
          callbackPending = true
        }

        override def onUpstreamFinish(): Unit = {
          if (callbackPending) completed = true
          else super.onUpstreamFinish()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })

      /**
        * Handles an incoming result from the mapping function.
        *
        * @param result the result
        */
      private def handleResult(result: Try[(B, S)]): Unit = {
        val (res, next) = result.get // fails with the correct exception if not successful
        push(out, res)
        state = next
        callbackPending = false
        if (completed) completeStage()
      }
    }
}
