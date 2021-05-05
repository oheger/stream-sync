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

package com.github.sync.impl

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import com.github.sync.SyncTypes.{FsElement, FsFolder}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test class for ''FolderSortStage''.
  */
class FolderSortStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("FolderSortStageSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates an ''FsElement'' with the given URI. Other attributes are
    * irrelevant for the stage under test.
    *
    * @param uri the URI
    * @return the element with this URI
    */
  private def createElem(uri: String): FsElement = FsFolder(uri, 1)

  /**
    * Generates a test ''FsElement'' that represents a file in a directory.
    * From the indices provided a canonical URI is generated.
    *
    * @param dirIdx  the index of the directory
    * @param fileIdx the index of the file in this directory
    * @return the generated element
    */
  private def createFile(dirIdx: Int, fileIdx: Int): FsElement =
    createElem(s"dir$dirIdx/file$fileIdx.txt")

  /**
    * Runs a stream with the given source over the test stage and collects the
    * output.
    *
    * @param source the source of the stream
    * @return the output produced by the stream
    */
  private def runStage(source: Source[FsElement, Any]): Seq[FsElement] = {
    val sink = Sink.fold[List[FsElement], FsElement](List.empty) { (lst, e) => e :: lst }
    val futStream = source.via(new FolderSortStage)
      .runWith(sink)
    Await.result(futStream, 3.seconds).reverse
  }

  "A FolderSortStage" should "handle an empty source" in {
    val srcEmpty = Source.empty[FsElement]

    val result = runStage(srcEmpty)
    result should have size 0
  }

  it should "order the elements in a folder" in {
    val elements = List(createElem("file3"), createElem("file1"),
      createElem("file2"))
    val sortedElements = List(createElem("file1"), createElem("file2"),
      createElem("file3"))

    val result = runStage(Source(elements))
    result should contain theSameElementsInOrderAs sortedElements
  }

  it should "deal with elements in multiple folders" in {
    val rootElem = createElem("root.txt")
    val subElem1 = createElem("dir3/sub/fileA.mp3")
    val subElem2 = createElem("dir3/sub/fileB.mp3")
    val elements = List(rootElem, createFile(1, 3),
      createFile(1, 2), createFile(1, 1),
      createFile(2, 2), createFile(2, 1),
      createFile(2, 3), createFile(3, 3),
      createFile(3, 1), createFile(3, 2),
      subElem2, subElem1)
    val sortedElements = List(rootElem, createFile(1, 1),
      createFile(1, 2), createFile(1, 3),
      createFile(2, 1), createFile(2, 2),
      createFile(2, 3), createFile(3, 1),
      createFile(3, 2), createFile(3, 3),
      subElem1, subElem2)

    val result = runStage(Source(elements))
    result should contain theSameElementsInOrderAs sortedElements
  }

  it should "handle an empty root directory correctly" in {
    val elements = List(createFile(1, 2), createFile(1, 1),
      createFile(2, 1), createFile(3, 1),
      createFile(3, 3), createFile(3, 2))
    val sortedElements = List(createFile(1, 1), createFile(1, 2),
      createFile(2, 1), createFile(3, 1),
      createFile(3, 2), createFile(3, 3))

    val result = runStage(Source(elements))
    result should contain theSameElementsInOrderAs sortedElements
  }
}
