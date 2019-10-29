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

package com.github.sync

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.github.sync.http.HttpRequestActor

import scala.concurrent.{ExecutionContext, Future}

/**
  * Object with some common functionality for the ''webdav'' package.
  */
package object webdav {
  /**
    * Sends a request to an HTTP actor and allows processing of the result.
    * This function passes the given request to the actor and expects the
    * future with the result. The future is then mapped with the specified
    * mapping function to come to the final result. Note that error handling is
    * already done by the actor, including an evaluation of the HTTP response
    * status.
    *
    * @param httpActor the actor to execute the request
    * @param request   the request to be executed
    * @param f         the processing function
    * @param ec        the execution context
    * @param timeout   a timeout
    * @tparam T the type of the result of the processing function
    * @return a ''Future'' with the processing result
    */
  def sendAndProcess[T](httpActor: ActorRef, request: HttpRequestActor.SendRequest)
                       (f: HttpRequestActor.Result => T)
                       (implicit ec: ExecutionContext, timeout: Timeout): Future[T] =
    (httpActor ? request)
      .mapTo[HttpRequestActor.Result]
      .map(f)
}
