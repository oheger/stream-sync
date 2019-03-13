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

package com.github.sync.util

import com.github.sync.SyncTypes.FsFolder
import org.scalatest.{FlatSpec, Matchers}

object SyncFolderQueueSpec {

  /**
    * Reads all elements stored in the given queue and returns them ordered in
    * a list.
    *
    * @param queue the queue to be read
    * @return a list with the elements extracted from the queue
    */
  private def readQueue(queue: SyncFolderQueue[SyncFolderData]): List[SyncFolderData] = {
    def dequeueElement(q: SyncFolderQueue[SyncFolderData],
                       resultList: List[SyncFolderData]): List[SyncFolderData] =
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
  private def folderData(uri: String, level: Int): SyncFolderData = {
    val theFolder = FsFolder(uri, level)
    new SyncFolderData {
      override val folder: FsFolder = theFolder
    }
  }
}

/**
  * Test class for ''SyncFolderQueue''.
  */
class SyncFolderQueueSpec extends FlatSpec with Matchers {

  import SyncFolderQueue._
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

    readQueue(q2) should be(List(elem1, elem2, elem3, elem4))
  }

  it should "implement nonEmpty correctly" in {
    val queueFull = SyncFolderQueue(folderData("test", 42))
    queueFull.nonEmpty shouldBe true

    val (_, queueEmpty) = queueFull.dequeue()
    queueEmpty.nonEmpty shouldBe false
  }
}
