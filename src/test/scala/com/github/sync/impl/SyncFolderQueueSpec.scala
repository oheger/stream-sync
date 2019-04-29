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

package com.github.sync.impl

import com.github.sync.SyncTypes.{FsFolder, SyncFolderData}
import org.scalatest.{FlatSpec, Matchers}

object SyncFolderQueueSpec {

  /**
    * Reads all elements stored in the given queue and returns them ordered in
    * a list.
    *
    * @param queue the queue to be read
    * @return a list with the elements extracted from the queue
    */
  private def readQueue(queue: SyncFolderQueue[Int]): List[SyncFolderData[Int]] = {
    def dequeueElement(q: SyncFolderQueue[Int],
                       resultList: List[SyncFolderData[Int]]): List[SyncFolderData[Int]] =
      if (q.isEmpty) resultList.reverse
      else {
        val (d, q2) = q.dequeue()
        dequeueElement(q2, d :: resultList)
      }

    dequeueElement(queue, Nil)
  }

  /**
    * Convenience function to create a folder data object with the given
    * properties.
    *
    * @param uri   the folder URI
    * @param level the folder level
    * @return the resulting ''SyncFolderData''
    */
  private def folderData(uri: String, level: Int): SyncFolderData[Int] = {
    val theFolder = FsFolder(uri, level)
    SyncFolderData(theFolder, level + 1)
  }
}

/**
  * Test class for ''SyncFolderQueue''.
  */
class SyncFolderQueueSpec extends FlatSpec with Matchers {

  import SyncFolderQueueSpec._

  "A SyncFolderQueue" should "order elements by their URI" in {
    val elemA = folderData("A", 0)
    val elemB = folderData("B", 0)
    val elemC = folderData("C", 0)
    val elemD = folderData("D", 0)
    val q1 = SyncFolderQueue(elemB)
    val q2 = q1 + elemC
    val q3 = q2 ++ List(elemA, elemD)

    readQueue(q3) should be(List(elemA, elemB, elemC, elemD))
  }

  it should "order elements by level first" in {
    val elem1 = folderData("top", 0)
    val elem2 = folderData("level_1_1", 1)
    val elem3 = folderData("level_1_2", 1)
    val elem4 = folderData("bottom", 2)
    val q1 = SyncFolderQueue(elem1)
    val q2 = q1 ++ List(elem3, elem4, elem2)

    readQueue(q2) should be(List(elem4, elem2, elem3, elem1))
  }

  it should "implement nonEmpty correctly" in {
    val queueFull = SyncFolderQueue(folderData("test", 42))
    queueFull.nonEmpty shouldBe true

    val (_, queueEmpty) = queueFull.dequeue()
    queueEmpty.nonEmpty shouldBe false
  }
}
