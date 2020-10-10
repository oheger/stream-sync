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
import com.github.scli.ParameterExtractor
import com.github.scli.ParameterManager.ProcessingContext
import com.github.sync.cli.SyncParameterManager.SyncConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

/**
  * Test class for ''ActorSystemLifeCycle''.
  */
class CliActorSystemLifeCycleSpec extends AnyFlatSpec with Matchers {
  "ActorSystemLifeCycle" should "manage the life-cycle of an actor system" in {
    val SrcPath = "/src/sync/data"
    val DstPath = "/dst/sync/target"
    val CommandLine = Array(SrcPath, DstPath)
    val myApp = new CliActorSystemLifeCycleTestImpl {
      override val name: String = "TestApp"

      override protected def runApp(config: SyncConfig): Future[String] = {
        val actor = actorSystem.actorOf(Props(new Actor {
          override def receive: Receive = {
            case conf: SyncConfig =>
              sender() ! conf.srcUri + "," + conf.dstUri
          }
        }))

        implicit val timeout: Timeout = Timeout(3.seconds)
        (actor ? config).mapTo[String]
      }
    }

    val outStream = new ByteArrayOutputStream
    Console.withOut(outStream) {
      myApp.run(CommandLine)
      val output = new String(outStream.toByteArray)
      output should be(SrcPath + "," + DstPath + System.getProperty("line.separator"))
    }
    myApp.actorSystem.whenTerminated.isCompleted shouldBe true
  }

  it should "handle a failed Future returned by the application logic" in {
    val exception = new IllegalStateException("App crashed!")
    val myApp = new CliActorSystemLifeCycleTestImpl {
      override val name: String = "failingApp"

      override protected def runApp(config: SyncConfig): Future[String] =
        Future.failed(exception)
    }

    val outStream = new ByteArrayOutputStream
    Console.withOut(outStream) {
      myApp.run(Array("/src", "/dst"))
      val output = new String(outStream.toByteArray)
      output should include(exception.getClass.getName)
      output should include(exception.getMessage)
    }
    myApp.actorSystem.whenTerminated.isCompleted shouldBe true
  }

  /**
    * A test implementation of ''ActorSystemLifeCycle'' that provides dummy
    * implementations for methods that are not used by the tests.
    */
  private abstract class CliActorSystemLifeCycleTestImpl extends CliActorSystemLifeCycle[SyncConfig] {
    override protected def cliExtractor: ParameterExtractor.CliExtractor[Try[SyncConfig]] =
      SyncParameterManager.syncConfigExtractor()

    override protected def usageCaption(processingContext: ProcessingContext): String =
      "Test usage caption"

    override protected def helpOptionHelp: String = "Test help option help"
  }

}
