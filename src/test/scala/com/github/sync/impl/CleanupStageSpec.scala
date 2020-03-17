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

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import com.github.sync.AsyncTestHelper
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''CleanupStage''.
  */
class CleanupStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper {
  def this() = this(ActorSystem("CleanupStageSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A CleanupStage" should "forward stream elements and invoke the cleanup action" in {
    val Elements = List(1, 2, 3, 4, 42)
    val source = Source(Elements)
    val sink = Sink.fold[List[Int], Int](List.empty)((lst, e) => e :: lst)
    val cleanUpCount = new AtomicInteger
    val stage = new CleanupStage[Int](() => cleanUpCount.incrementAndGet())

    val result = futureResult(source.via(stage).runWith(sink)).reverse
    result should contain theSameElementsInOrderAs Elements
    cleanUpCount.get() should be(1)
  }
}
