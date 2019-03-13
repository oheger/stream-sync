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

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder, IterateFunc, NextFolderFunc, ReadResult, SyncFolderData}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Future

object ElementSourceSpec {

  /**
    * Tests implementation of folder data.
    *
    * @param folder the folder this object is about
    */
  case class SyncFolderDataImpl(override val folder: FsFolder) extends SyncFolderData

  /** Constant for the top-level folder. */
  private val RootFolder = SyncFolderDataImpl(FsFolder("root", 1))

  /** The initial state value. */
  private val InitState = 1

  /**
    * Helper function to create a file element.
    *
    * @param uri   the URI
    * @param level the level
    * @return the file
    */
  private def createFile(uri: String, level: Int): FsFile =
    FsFile(uri, level, Instant.now(), uri.length * 100)

  /**
    * Helper function to create a file element that is a child of the given
    * folder.
    *
    * @param parent the parent folder
    * @param name   the file name; the URI is derived from this
    * @return the file
    */
  private def createFile(parent: FsFolder, name: String): FsFile =
    createFile(parent.relativeUri + "/" + name, parent.level + 1)

  /**
    * Convenience function to create a result object that reports a single
    * file.
    *
    * @param elem  the file the result is about
    * @param state the next state
    * @return the result object
    */
  private def createSimpleResult(elem: FsFile, state: Int = InitState): ReadResult[SyncFolderDataImpl, Int] =
    ReadResult(RootFolder.folder, List(elem), Nil, state)
}

/**
  * Test class for ''ElementSource''.
  */
class ElementSourceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with BeforeAndAfterAll with Matchers with AsyncTestHelper {
  def this() = this(ActorSystem("ElementSourceSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import ElementSourceSpec._

  "An ElementSource" should "return the elements received from the iterate function" in {
    val files = List(createFile("/a", 2), createFile("/b", 2), createFile("/c", 2))
    val results = files map (f => createSimpleResult(f))
    val helper = new SourceTestHelper(results)

    helper.runSource() should be(files)
  }

  it should "pass correct state values to the iterate function" in {
    val results = List(
      createSimpleResult(createFile("/1", 2), 2),
      createSimpleResult(createFile("/2", 2), 3),
      createSimpleResult(createFile("/3", 2), 4)
    )
    val helper = new SourceTestHelper(results)

    helper.runSource()
    helper.states should be(List(1, 2, 3, 4))
  }

  it should "return folders as well received from the iterate function" in {
    val file = createFile("/a.txt", 2)
    val folder = FsFolder("/f", 2)
    val results = List(createSimpleResult(file),
      ReadResult(RootFolder.folder, Nil, List(SyncFolderDataImpl(folder)), 2))
    val helper = new SourceTestHelper(results)

    helper.runSource() should contain only(file, folder)
  }

  it should "handle the content of a folder at once" in {
    val file1 = createFile("/a.dat", 2)
    val file2 = createFile("/b.txt", 2)
    val subFolder = FsFolder("/sub", 2)
    val subFile1 = createFile(subFolder, "sub1.txt")
    val subFile2 = createFile(subFolder, "sub2.doc")
    val results = List(
      ReadResult(RootFolder.folder, List(file1, file2), List(SyncFolderDataImpl(subFolder)), 42),
      ReadResult[SyncFolderDataImpl, Int](subFolder, List(subFile1, subFile2), Nil, 100)
    )
    val helper = new SourceTestHelper(results)

    helper.runSource() should contain theSameElementsInOrderAs List(file1, file2, subFolder, subFile1, subFile2)
  }

  it should "manage pending folders in a SyncFolderQueue" in {
    val folder1 = SyncFolderDataImpl(FsFolder("/folder1", 2))
    val folder2 = SyncFolderDataImpl(FsFolder("/folder2", 2))
    val folder3 = SyncFolderDataImpl(FsFolder("/folder3", 2))
    val results = List(ReadResult(RootFolder.folder,
      List(createFile("/file1.txt", 2), createFile("/otherFile.dat", 2)),
      List(folder2, folder3, folder1), 11))
    val helper = new SourceTestHelper(results)

    helper.runSource()
    val folderFunc = helper.nextFolderFunc
    folderFunc() should be(Some(RootFolder))
    List(folder1, folder2, folder3) foreach { f =>
      folderFunc().get should be(f)
    }
    folderFunc() shouldBe 'empty
  }

  it should "order the elements of a single directory" in {
    val file1 = createFile("/aFile", 2)
    val file2 = createFile("/bFile", 2)
    val file3 = createFile("/xFile", 2)
    val folder1 = FsFolder("/aFolder", 2)
    val folder2 = FsFolder("/anotherFolder", 2)
    val folder3 = FsFolder("/oneMoreFolder", 2)
    val results = List(ReadResult(RootFolder.folder, List(file2, file1, file3),
      List(SyncFolderDataImpl(folder3), SyncFolderDataImpl(folder2), SyncFolderDataImpl(folder1)), 0))
    val expElements = List(file1, folder1, folder2, file2, folder3, file3)
    val helper = new SourceTestHelper(results)

    helper.runSource() should contain theSameElementsInOrderAs expElements
  }

  it should "handle failures in the iteration function" in {
    val results = List(
      ReadResult[SyncFolderDataImpl, Int](RootFolder.folder, List(createFile("/foo", 2)), Nil, 1),
      ReadResult[SyncFolderDataImpl, Int](RootFolder.folder, Nil, Nil, 2)
    )
    val helper = new SourceTestHelper(results)

    expectFailedFuture[IllegalStateException](helper.executeStream())
  }

  /**
    * A test helper class managing the environment for testing an element
    * source.
    *
    * @param readResults the results to be returned from an iteration function
    */
  private class SourceTestHelper(readResults: List[ReadResult[SyncFolderDataImpl, Int]]) {

    import system.dispatcher

    /**
      * Stores the results to be returned by the iteration function. Here an
      * atomic reference is used because the list is accessed from another
      * thread.
      */
    private val refResults = new AtomicReference(readResults)

    /** Stores the state values passed to the iterate function. */
    private val refStates = new AtomicReference(List.empty[Int])

    /** Stores the function for fetching the next folder. */
    private val refFolderFunc = new AtomicReference[NextFolderFunc[SyncFolderDataImpl]]

    /**
      * Executes a stream with the test source and returns the resulting
      * future.
      *
      * @return the future result of the stream execution
      */
    def executeStream(): Future[List[FsElement]] = {
      implicit val mat: ActorMaterializer = ActorMaterializer()
      val source = new ElementSource(InitState, RootFolder)(iterateFunc)
      val sink = Sink.fold[List[FsElement], FsElement](Nil)((lst, e) => e :: lst)
      Source.fromGraph(source).runWith(sink)
    }

    /**
      * Runs a stream with the test source and returns the result.
      *
      * @return the elements emitted by the test source
      */
    def runSource(): List[FsElement] =
      futureResult(executeStream()).reverse

    /**
      * Returns the states that have been passed to the iterate function (in
      * order).
      *
      * @return the list with state values
      */
    def states: List[Int] = refStates.get().reverse

    /**
      * Returns the function to obtain the next folder that was passed to the
      * iterate function.
      *
      * @return the next folder function
      */
    def nextFolderFunc: NextFolderFunc[SyncFolderDataImpl] = refFolderFunc.get()

    /**
      * Returns an iterate function. The function returns the single result
      * objects. It also stores the passed in data. If a result object contains
      * no result, an exception is thrown, simulating a processing error.
      *
      * @return the iterate function
      */
    private def iterateFunc: IterateFunc[SyncFolderDataImpl, Int] = (state, next) => {
      val states = refStates.get()
      refStates.set(state :: states)
      if (refFolderFunc.get() == null) refFolderFunc.set(next)
      else {
        next should be(refFolderFunc.get())
      }
      Future {
        refResults.get() match {
          case h :: t =>
            refResults.set(t)
            if (h.files.nonEmpty || h.folders.nonEmpty)
              Some(h)
            else throw new IllegalStateException("Simulated processing error!")
          case _ =>
            None
        }
      }
    }
  }

}