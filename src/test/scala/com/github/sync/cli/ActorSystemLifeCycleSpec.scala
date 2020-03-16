/*
 * Copyright 2018-2020 The Developers Team.
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

import java.io.ByteArrayOutputStream

import akka.actor.{Actor, Props}
import akka.pattern.ask
import akka.util.Timeout
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Test class for ''ActorSystemLifeCycle''.
  */
class ActorSystemLifeCycleSpec extends FlatSpec with Matchers {
  "ActorSystemLifeCycle" should "manage the life-cycle of an actor system" in {
    val CommandLine = Array("1", "2", "3")
    val myApp = new ActorSystemLifeCycle {
      override val name: String = "TestApp"

      override protected def runApp(args: Array[String]): Future[String] = {
        args should be(CommandLine)
        val actor = actorSystem.actorOf(Props(new Actor {
          override def receive: Receive = {
            case x: Int => sender() ! x + 1
          }
        }))

        implicit val timeout: Timeout = Timeout(3.seconds)
        val futResult = actor ? 41
        futResult.map(_.toString)
      }
    }

    val outStream = new ByteArrayOutputStream
    Console.withOut(outStream) {
      myApp.run(CommandLine)
      val output = new String(outStream.toByteArray)
      output should be("42" + System.getProperty("line.separator"))
    }
    myApp.actorSystem.whenTerminated.isCompleted shouldBe true
  }

  it should "handle a failed Future returned by the application logic" in {
    val exception = new IllegalStateException("App crashed!")
    val myApp = new ActorSystemLifeCycle {
      override val name: String = "failingApp"

      override protected def runApp(args: Array[String]): Future[String] =
        Future.failed(exception)
    }

    val outStream = new ByteArrayOutputStream
    Console.withOut(outStream) {
      myApp.run(Array.empty)
      val output = new String(outStream.toByteArray)
      output should include(exception.getClass.getName)
      output should include(exception.getMessage)
    }
    myApp.actorSystem.whenTerminated.isCompleted shouldBe true
  }
}
