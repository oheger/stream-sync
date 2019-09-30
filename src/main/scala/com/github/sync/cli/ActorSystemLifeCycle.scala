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

package com.github.sync.cli

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * A trait that supports managing an actor system and some related objects for
  * command line applications.
  *
  * This trait provides an actor system in implicit scope and defines a
  * ''run()'' function that is invoked with current command line arguments.
  * After the ''run()'' function returns, its result message is printed, and
  * the actor system is properly shutdown.
  */
trait ActorSystemLifeCycle {
  /**
    * A name for the application implementing this trait. This name is also
    * used for the managed actor system.
    */
  val name: String

  /** The field that stores the managed actor system. */
  private var system: ActorSystem = _

  /** The field that stores the managed object to materialize streams. */
  private var mat: ActorMaterializer = _

  /**
    * Returns the actor system managed by this trait.
    *
    * @return the managed actor system
    */
  implicit def actorSystem: ActorSystem = system

  /**
    * Returns the object to materialize streams managed by this trait.
    *
    * @return the object to materialize streams
    */
  implicit def streamMat: ActorMaterializer = mat

  /**
    * Returns an execution context for concurrent operations.
    *
    * @return an execution context
    */
  implicit def ec: ExecutionContext = system.dispatcher

  /**
    * The main ''run()'' method. This method executes the whole logic of the
    * application implementing this trait. It delegates to ''runApp()'' for the
    * actual execution and then releases all resources and terminates the actor
    * system. If the application logic returns a failed future, a message is
    * generated based on the exception and logged to the console instead of the
    * application's result message.
    *
    * @param args the array with command line arguments
    */
  def run(args: Array[String]): Unit = {
    system = createActorSystem()
    mat = createStreamMat()
    val futResult = futureWithShutdown(runApp(args))
    val resultMsg = Await.result(futResult, 365.days)
    println(resultMsg)
  }

  /**
    * Creates the actor system managed by this object. This method is called
    * before invoking the ''runApp()'' method. This base implementation creates
    * a standard actor system with a name defined by the ''name'' property.
    *
    * @return the newly created actor system
    */
  protected def createActorSystem(): ActorSystem = ActorSystem(name)

  /**
    * Creates the object to materialize streams. This base implementation
    * creates a default ''ActorMaterializer''.
    *
    * @return the object to materialize streams
    */
  protected def createStreamMat(): ActorMaterializer = ActorMaterializer()

  /**
    * Executes the logic of this application. A concrete implementation can
    * process the passed in command line arguments and return a result. The
    * result is simply printed to the console.
    *
    * @param args the array with command line arguments
    * @return a ''Future'' with the result message of this application
    */
  protected def runApp(args: Array[String]): Future[String]

  /**
    * Returns a ''Future'' that is completed after all resources used by this
    * application have been released. The future contains either the result
    * message of the application or - in cause the application logic returned a
    * failure - a message derived from the corresponding exception.
    *
    * @param resultFuture the ''Future'' with the result of the application
    * @return a ''Future'' with the application result, including shutdown
    *         handling
    */
  private def futureWithShutdown(resultFuture: Future[String]): Future[String] = {
    val fallback = resultFuture recover {
      case ex => errorMessage(ex)
    } // this is guaranteed to succeed

    for {msg <- fallback
         _ <- Http().shutdownAllConnectionPools()
         _ <- system.terminate()
         } yield msg
  }

  /**
    * Returns an error message from the given exception.
    *
    * @param ex the exception
    * @return the error message derived from this exception
    */
  private def errorMessage(ex: Throwable): String =
    s"[${ex.getClass.getName}]: ${ex.getMessage}"
}
