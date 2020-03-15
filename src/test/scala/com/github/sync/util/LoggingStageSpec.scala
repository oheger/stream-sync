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

package com.github.sync.util

import java.util
import java.util.{Collections, Locale}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.testkit.TestKit
import com.github.sync.AsyncTestHelper
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import scala.collection.JavaConverters._

/**
  * Test class for ''LoggingStage''.
  */
class LoggingStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper {
  def this() = this(ActorSystem("LoggingStageSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A LoggingStage" should "invoke the logging function for each element" in {
    val elements = List("These", "elements", "go", "through", "the", "stream.")
    val outElements = elements map (_.toUpperCase(Locale.ROOT))
    val loggedElements = Collections.synchronizedList(new util.ArrayList[String])
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val source = Source(elements)
    val sink = Sink.fold[List[String], String](List.empty)((lst, e) => e :: lst)
    val operator =
      LoggingStage.withLogging(Flow[String].map(_.toUpperCase(Locale.ROOT))) { (s, log) =>
        if (log != null) loggedElements add s
      }

    val result = futureResult(source.via(operator).runWith(sink))
    result.reverse should contain theSameElementsInOrderAs outElements
    loggedElements.asScala should contain theSameElementsInOrderAs outElements
  }
}
