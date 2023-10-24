/*
 * Copyright 2018-2023 The Developers Team.
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

import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}

/**
  * A stage implementation that is responsible for executing clean-up steps at
  * the end of stream processing.
  *
  * This stage is integrated into the sync stream when there are objects that
  * require some clean-up logic. It acts as a dummy stage simply forwarding its
  * input to the output. But when the stream completes it executes the clean-up
  * action.
  *
  * The advantage of this approach is that the application executing the sync
  * stream does not have to take care about these clean-up steps itself. As the
  * sync stream is constructed dynamically based on arguments passed by the
  * user, this is a significant simplification.
  *
  * @param cleanup the clean-up action to be invoked
  * @tparam T the type of stream elements
  */
class CleanupStage[T](cleanup: () => Unit) extends GraphStage[FlowShape[T, T]]:
  val in: Inlet[T] = Inlet[T]("CleanupStage.in")
  val out: Outlet[T] = Outlet[T]("CleanupStage.out")

  override val shape: FlowShape[T, T] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          push(out, grab(in))
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })

      override def postStop(): Unit =
        cleanup()
        super.postStop()
    }
