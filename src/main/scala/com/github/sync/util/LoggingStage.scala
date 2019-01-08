/*
 * Copyright 2018-2019 The Developers Team.
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

package com.github.sync.util

import akka.event.LoggingAdapter
import akka.stream._
import akka.stream.scaladsl.Flow
import akka.stream.stage._

object LoggingStage {
  /**
    * Adds a logging function to the given flow. All elements passed downstream
    * from the given flow are passed to the logging function.
    *
    * @param flow    the flow to be enhanced with logging
    * @param logFunc the logging function
    * @tparam I the input type of the flow
    * @tparam O the output type of the flow
    * @return the enhanced flow
    */
  def withLogging[I, O](flow: Flow[I, O, Any])
                       (logFunc: (O, LoggingAdapter) => Unit): Flow[I, O, Any] = {
    val logStage = new LoggingStage[O](logFunc)
    flow.via(logStage)
  }
}

/**
  * A helper stage implementation which allows logging of elements passed
  * through the stream.
  *
  * This stage does not manipulate stream elements, but passes them
  * down-stream without any changes. For each element passed through the given
  * logging function is invoked. It has the chance to produce some diagnostic
  * output.
  *
  * @param logFunc the log function to be invoked for each element
  * @tparam T the type of elements to be processed
  */
class LoggingStage[T](logFunc: (T, LoggingAdapter) => Unit) extends GraphStage[FlowShape[T, T]] {
  val in: Inlet[T] = Inlet[T]("LoggingStage.in")
  val out: Outlet[T] = Outlet[T]("LoggingStage.out")

  override def shape: FlowShape[T, T] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val elem = grab(in)
          logFunc(elem, log)
          push(out, elem)
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
    }
}
