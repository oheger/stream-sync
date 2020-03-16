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

package com.github.sync.impl

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import com.github.sync.AsyncTestHelper
import com.github.sync.impl.StatefulStage.StateMapFunction
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Future

object StatefulStageSpec {
  /** A list with elements to flow through the test stream. */
  private val SourceData = List("A", "B", "C")

  /** The expected stream result with the default mapping function. */
  private val ExpResult = SourceData.zipWithIndex.map(t => t._1 + t._2)

}

/**
  * Test class for ''StatefulStage''.
  */
class StatefulStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with BeforeAndAfterAll
  with Matchers with AsyncTestHelper {
  def this() = this(ActorSystem("StatefulStageSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import StatefulStageSpec._
  import system.dispatcher

  /**
    * Runs a stream with test data and returns a list with all elements
    * (in reverse order) that have been arrived at the sink.
    *
    * @param func the mapping function
    * @return a future with the stream result
    */
  private def runStream(func: StateMapFunction[String, String, Int]): Future[List[String]] = {
    val source = Source(SourceData)
    val sink = Sink.fold[List[String], String](Nil)((lst, e) => e :: lst)
    source.via(new StatefulStage(0)(func))
      .runWith(sink)
  }

  "A StatefulStage" should "map stream elements with a state" in {
    val mapFunc: StateMapFunction[String, String, Int] = (e, s) => Future {
      (e + s, s + 1)
    }

    futureResult(runStream(mapFunc)).reverse should be(ExpResult)
  }

  it should "correctly finish the stream for a fast mapping function" in {
    val mapFunc: StateMapFunction[String, String, Int] = (e, s) => Future.successful((e + s, s + 1))

    futureResult(runStream(mapFunc)).reverse should be(ExpResult)
  }

  it should "handle a failed future from the mapping function" in {
    val exception = new IllegalStateException("Test exception")
    val mapFunc: StateMapFunction[String, String, Int] = (_, _) => Future.failed(exception)

    expectFailedFuture[IllegalStateException](runStream(mapFunc)) should be(exception)
  }
}
