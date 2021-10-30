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

package com.github.sync

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import org.scalatest.{BeforeAndAfterAll, Suite}

/**
  * A helper trait making an ''ActorTestKit'' and its actor system available.
  *
  * This trait is a temporary solution for an incompatibility between Akka's
  * ''ScalaTestWithActorTestKit'' class (in the 2.13 version) and ScalaTest
  * under Scala 3. Basically, this trait duplicates the logic of Akka's helper
  * class, but as it is a Scala 3 class, there is no incompatibility.
  *
  * The plan is to switch back to ''ScalaTestWithActorTestKit'' when possible.
  */
trait ActorTestKitSupport extends BeforeAndAfterAll {
  this: Suite =>

  /** The test kit managed by this class. */
  protected val testKit = ActorTestKit()

  /**
    * Provides the actor system in implicit scope.
    *
    * @return the actor system
    */
  protected implicit def system: ActorSystem[Nothing] = testKit.system

  override protected def afterAll(): Unit = {
    testKit.shutdownTestKit()
    super.afterAll()
  }
}
