/*
 * Copyright 2018-2025 The Developers Team.
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

import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder, SyncOperation}
import com.github.sync.stream.LocalStateStage.ElementWithDelta
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant

object LocalStateStageSpec:
  /** Constant for the current sync time. */
  private val SyncTime = Instant.parse("2021-11-14T17:04:50Z")

/**
  * Test class for ''LocalStageStage''.
  */
class LocalStateStageSpec(testSystem: ActorSystem) extends AbstractStageSpec(testSystem):
  def this() = this(ActorSystem("LocalStateStageSpec"))

  import AbstractStageSpec.*
  import LocalStateStageSpec.*

  /**
    * Runs the test stage with sources containing the specified elements and
    * returns the resulting sequence of results.
    *
    * @param elements a list with local elements
    * @param state    a list with elements from the recorded state
    * @return the sequence with elements with enriched delta information
    */
  private def runStage(elements: List[FsElement], state: List[FsElement]): Seq[ElementWithDelta] =
    runStage(LocalStateStage(SyncTime), elements, state)

  "LocalStageStage" should "correctly handle two empty sources" in {
    runStage(Nil, Nil) shouldBe empty
  }

  it should "handle a process without local state" in {
    val elements = List(createFolder(1), createFile(2), createFile(3), createFolder(4))
    val expectedResult = elements.map(e => ElementWithDelta(e, LocalStateStage.ChangeType.Created, SyncTime))

    runStage(elements, Nil) should contain theSameElementsInOrderAs expectedResult
  }

  it should "handle a process with local state, but no elements" in {
    val state = List(createFile(1), createFolder(2), createFolder(3), createFile(4))
    val expectedResult = state.map { e =>
      val changeTime = e match
        case FsFile(_, _, _, time, _) => time
        case _ => null
      ElementWithDelta(e, LocalStateStage.ChangeType.Removed, changeTime)
    }

    runStage(Nil, state) should contain theSameElementsInOrderAs expectedResult
  }

  it should "handle an unchanged local state" in {
    val elem1 = createFolder(1)
    val elem2 = createFile(2)
    val elem3 = createFolder(3)
    val elem4 = createFile(4)
    val state = List(elem1, elem2, elem3, elem4)
    val expectedResult = List(ElementWithDelta(elem1, LocalStateStage.ChangeType.Unchanged, null),
      ElementWithDelta(elem2, LocalStateStage.ChangeType.Unchanged, elem2.lastModified),
      ElementWithDelta(elem3, LocalStateStage.ChangeType.Unchanged, null),
      ElementWithDelta(elem4, LocalStateStage.ChangeType.Unchanged, elem4.lastModified))

    runStage(state, state) should contain theSameElementsInOrderAs expectedResult
  }

  it should "detect an element that has been modified" in {
    val elem1 = createFile(1)
    val elem2 = createFile(2)
    val elem3 = createFile(3)
    val elemModified = createFile(2, deltaTime = 10)
    val elements = List(elem1, elemModified, elem3)
    val state = List(elem1, elem2, elem3)
    val expectedResult = List(ElementWithDelta(elem1, LocalStateStage.ChangeType.Unchanged, elem1.lastModified),
      ElementWithDelta(elemModified, LocalStateStage.ChangeType.Changed, elem2.lastModified),
      ElementWithDelta(elem3, LocalStateStage.ChangeType.Unchanged, elem3.lastModified))

    runStage(elements, state) should contain theSameElementsInOrderAs expectedResult
  }

  it should "detect an element that has been removed" in {
    val elem1 = createFile(1)
    val elem2 = createFile(2)
    val elem3 = createFile(3)
    val elements = List(elem1, elem3)
    val state = List(elem1, elem2, elem3)
    val expectedResult = List(ElementWithDelta(elem1, LocalStateStage.ChangeType.Unchanged, elem1.lastModified),
      ElementWithDelta(elem2, LocalStateStage.ChangeType.Removed, elem2.lastModified),
      ElementWithDelta(elem3, LocalStateStage.ChangeType.Unchanged, elem3.lastModified))

    runStage(elements, state) should contain theSameElementsInOrderAs expectedResult
  }

  it should "detect an element that has been created" in {
    val elem1 = createFile(1)
    val elem2 = createFile(2)
    val elem3 = createFile(3)
    val elements = List(elem1, elem2, elem3)
    val state = List(elem1, elem3)
    val expectedResult = List(ElementWithDelta(elem1, LocalStateStage.ChangeType.Unchanged, elem1.lastModified),
      ElementWithDelta(elem2, LocalStateStage.ChangeType.Created, SyncTime),
      ElementWithDelta(elem3, LocalStateStage.ChangeType.Unchanged, elem3.lastModified))

    runStage(elements, state) should contain theSameElementsInOrderAs expectedResult
  }

  it should "handle multiple changes in series" in {
    val elem1 = createFile(1)
    val elem2 = createFolder(2)
    val elem3 = createFile(3)
    val elem4 = createFile(4)
    val elem5 = createFolder(5)
    val elem6 = createFolder(6)
    val elem7 = createFile(7)
    val elem8 = createFile(8)
    val elem8Modified = createFile(8, deltaTime = 60)
    val elements = List(elem2, elem3, elem5, elem6, elem8Modified)
    val state = List(elem1, elem3, elem4, elem7, elem8)
    val expectedResult = List(ElementWithDelta(elem1, LocalStateStage.ChangeType.Removed, elem1.lastModified),
      ElementWithDelta(elem2, LocalStateStage.ChangeType.Created, SyncTime),
      ElementWithDelta(elem3, LocalStateStage.ChangeType.Unchanged, elem3.lastModified),
      ElementWithDelta(elem4, LocalStateStage.ChangeType.Removed, elem4.lastModified),
      ElementWithDelta(elem5, LocalStateStage.ChangeType.Created, SyncTime),
      ElementWithDelta(elem6, LocalStateStage.ChangeType.Created, SyncTime),
      ElementWithDelta(elem7, LocalStateStage.ChangeType.Removed, elem7.lastModified),
      ElementWithDelta(elem8Modified, LocalStateStage.ChangeType.Changed, elem8.lastModified))

    runStage(elements, state) should contain theSameElementsInOrderAs expectedResult
  }

  it should "detect a file that was converted to a folder" in {
    val elem1 = createFolder(1)
    val elemFolder2 = createFolder(2)
    val elemFile2 = createFile(2)
    val elem3 = createFolder(3)
    val elements = List(elem1, elemFolder2, elem3)
    val state = List(elem1, elemFile2, elem3)
    val expectedResult = List(ElementWithDelta(elem1, LocalStateStage.ChangeType.Unchanged, null),
      ElementWithDelta(elemFolder2, LocalStateStage.ChangeType.TypeChanged, elemFile2.lastModified),
      ElementWithDelta(elem3, LocalStateStage.ChangeType.Unchanged, null))

    runStage(elements, state) should contain theSameElementsInOrderAs expectedResult
  }

  it should "detect a folder that was converted to a file" in {
    val elem1 = createFolder(1)
    val elemFolder2 = createFolder(2)
    val elemFile2 = createFile(2)
    val elem3 = createFolder(3)
    val elements = List(elem1, elemFile2, elem3)
    val state = List(elem1, elemFolder2, elem3)
    val expectedResult = List(ElementWithDelta(elem1, LocalStateStage.ChangeType.Unchanged, null),
      ElementWithDelta(elemFile2, LocalStateStage.ChangeType.TypeChanged, SyncTime),
      ElementWithDelta(elem3, LocalStateStage.ChangeType.Unchanged, null))

    runStage(elements, state) should contain theSameElementsInOrderAs expectedResult
  }
