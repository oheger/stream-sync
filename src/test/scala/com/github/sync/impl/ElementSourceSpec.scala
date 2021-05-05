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

import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

object ElementSourceSpec {
  /** Constant for the top-level folder. */
  private val RootFolder = SyncFolderData(FsFolder("root", 1), "root")

  /** The initial state value. */
  private val InitState = 1

  /** The end state returned by the test iterate function. */
  private val EndState = 111

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
    * Convenience function to create an iteration result for a single file
    * element.
    *
    * @param elem the single file in the result
    * @return the result object
    */
  private def createFileIterateResult(elem: FsFile): IterateResult[String] =
    IterateResult(RootFolder.folder, List(elem), Nil)

  /**
    * Convenience function to create a result object that reports a single
    * file.
    *
    * @param elem  the file the result is about
    * @param state the next state
    * @return the result object
    */
  private def createSimpleResult(elem: FsFile, state: Int = InitState): IterateFuncResult[String, Int] =
    (state, Some(createFileIterateResult(elem)), None)

  /**
    * Convenience function to create a future result function that returns the
    * specified data.
    *
    * @param result the result to be returned
    * @param state  the next state
    * @return the future result function returning this data
    */
  private def createFutureResultFunc(result: IterateResult[String], state: Int):
  FutureResultFunc[String, Int] = () =>
    Future.successful((state, result))

  /**
    * Convenience function to create a result object for a future result
    * containing only a single file.
    *
    * @param elem  the single file in the result
    * @param state the next state
    * @return the result object
    */
  private def createFutureResult(elem: FsFile, state: Int = InitState): IterateFuncResult[String, Int] =
    (state, None, Some(createFutureResultFunc(createFileIterateResult(elem), state)))
}

/**
  * Test class for ''ElementSource''.
  */
class ElementSourceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper {
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

  it should "return the elements received from a future result function" in {
    val files = List(createFile("/a", 2), createFile("/b", 2), createFile("/c", 2))
    val results = files map (f => createFutureResult(f))
    val helper = new SourceTestHelper(results)

    helper.runSource() should be(files)
  }

  it should "pass correct state values to the iterate function" in {
    val results = List(
      createSimpleResult(createFile("/1", 2), 2),
      createFutureResult(createFile("/2", 2), 3),
      createSimpleResult(createFile("/3", 2), 4)
    )
    val helper = new SourceTestHelper(results)

    helper.runSource()
    helper.states should be(List(1, 2, 3, 4))
  }

  it should "pass the last state to the completion function" in {
    val results = List(
      createSimpleResult(createFile("/1", 2), 2),
      createSimpleResult(createFile("/2", 2), 3),
      createSimpleResult(createFile("/3", 2), 4)
    )
    val helper = new SourceTestHelper(results)

    helper.runSource()
    helper.completionState should be(4)
  }

  it should "return folders received from the iterate function as well" in {
    val file = createFile("/a.txt", 2)
    val folder = FsFolder("/f", 2)
    val results = List(createSimpleResult(file),
      (2, Some(IterateResult(RootFolder.folder, Nil, List(SyncFolderData(folder, "foo")))), None))
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
      (42, Some(IterateResult(RootFolder.folder, List(file1, file2), List(SyncFolderData(subFolder, "1")))), None),
      (100, Some(IterateResult[String](subFolder, List(subFile1, subFile2), Nil)), None)
    )
    val helper = new SourceTestHelper(results)

    helper.runSource() should contain theSameElementsInOrderAs List(file1, file2, subFolder, subFile1, subFile2)
  }

  it should "manage pending folders in a SyncFolderQueue" in {
    val folder1 = SyncFolderData(FsFolder("/folder1", 2), "1")
    val folder2 = SyncFolderData(FsFolder("/folder2", 2), "2")
    val folder3 = SyncFolderData(FsFolder("/folder3", 2), "3")
    val results = List((11, Some(IterateResult(RootFolder.folder,
      List(createFile("/file1.txt", 2), createFile("/otherFile.dat", 2)),
      List(folder2, folder3, folder1))), None))
    val helper = new SourceTestHelper(results)

    helper.runSource()
    val folderFunc = helper.nextFolderFunc
    List(folder1, folder2, folder3) foreach { f =>
      folderFunc().get should be(f)
    }
    folderFunc() should be(Some(RootFolder))
    folderFunc() shouldBe None
  }

  it should "order the elements of a single directory" in {
    val file1 = createFile("/aFile", 2)
    val file2 = createFile("/bFile", 2)
    val file3 = createFile("/xFile", 2)
    val folder1 = FsFolder("/aFolder", 2)
    val folder2 = FsFolder("/anotherFolder", 2)
    val folder3 = FsFolder("/oneMoreFolder", 2)
    val results = List((0, Some(IterateResult(RootFolder.folder, List(file2, file1, file3),
      List(SyncFolderData(folder3, "f3"), SyncFolderData(folder2, "f2"), SyncFolderData(folder1, "f1")))), None))
    val expElements = List(file1, folder1, folder2, file2, folder3, file3)
    val helper = new SourceTestHelper(results)

    helper.runSource() should contain theSameElementsInOrderAs expElements
  }

  it should "handle failures in the iteration function" in {
    val results = List(
      (1, Some(IterateResult[String](RootFolder.folder, List(createFile("/foo", 2)), Nil)),
        None),
      (2, None, None)
    )
    val helper = new SourceTestHelper(results)

    expectFailedFuture[IllegalStateException](helper.executeStream())
  }

  it should "pass the last state to the completion function in case of a failure" in {
    val results = List(
      (1, Some(IterateResult[String](RootFolder.folder, List(createFile("/foo", 2)), Nil)),
        None),
      (2, None, None)
    )
    val helper = new SourceTestHelper(results)

    expectFailedFuture[IllegalStateException](helper.executeStream())
    helper.completionState should be(1)
  }

  it should "handle failed futures returned by the future result func" in {
    val exception = new IOException("BOOM")
    val LastState = 47
    val results = List(
      createFutureResult(createFile("/someFile.txt", 2)),
      (LastState, None, Some(() => Future.failed(exception)))
    )
    val helper = new SourceTestHelper(results)

    expectFailedFuture[IOException](helper.executeStream()) should be(exception)
    helper.completionState should be(LastState)
  }

  it should "deal with results from the iterate func that contain no new data" in {
    val file1 = createFile("/aFile.dat", 2)
    val file2 = createFile("/bFile.data", 2)
    val results = List(createSimpleResult(file1, 2),
      (3, Some(IterateResult(RootFolder.folder, Nil, List.empty[SyncFolderData[String]])), None),
      createSimpleResult(file2, 4))
    val helper = new SourceTestHelper(results)

    helper.runSource() should contain only(file1, file2)
    helper.states should be(List(InitState, 2, 3, 4))
  }

  /**
    * Helper function to check whether a result function is applied. The
    * function can be used together with direct or future results. A function
    * has to be provided which generates the results.
    *
    * @param resultFunc the function to generate results
    */
  private def checkTransformationFunction(resultFunc: List[FsFile] =>
    IterateFuncResult[String, Int]): Unit = {
    def transformFile(file: FsFile, idx: Int): FsFile =
      file.copy(relativeUri = file.relativeUri + ".processed" + idx)

    val transformer: ResultTransformer[Int] = new ResultTransformer[Int] {
      override val initialState = 0

      override def transform[F](result: IterateResult[F], state: Int): Future[(IterateResult[F], Int)] = {
        val processedFiles = result.files map (f => transformFile(f, state))
        Future.successful((result.copy(files = processedFiles), state + 1))
      }
    }
    val files1 = List(createFile("/test1.txt", 2), createFile("/test2.dat", 2),
      createFile("/theLastTest.doc", 2))
    val files2 = List(createFile("/moreData.dat", 2))
    val results = List(resultFunc(files1), resultFunc(files2))
    val expectedFiles = files1.map(transformFile(_, 0)) ++ files2.map(transformFile(_, 1))
    val helper = new SourceTestHelper(results, optTransformFunc = Some(transformer))

    helper.runSource() should contain theSameElementsAs expectedFiles
  }

  it should "invoke a transformation function if present" in {
    checkTransformationFunction { files =>
      (2, Some(IterateResult(RootFolder.folder, files, List.empty[SyncFolderData[String]])), None)
    }
  }

  it should "invoke a transformation function on future results if present" in {
    checkTransformationFunction { files =>
      val result = IterateResult(RootFolder.folder, files, List.empty[SyncFolderData[String]])
      (2, None, Some(createFutureResultFunc(result, 3)))
    }
  }

  it should "handle a failed future returned by the transformation function" in {
    val exception = new IllegalStateException("Crashed transformation")
    val transformer: ResultTransformer[Int] = new ResultTransformer[Int] {
      override val initialState: Int = -1

      override def transform[F](result: IterateResult[F], state: Int): Future[(IterateResult[F], Int)] =
        Future.failed(exception)
    }
    val helper = new SourceTestHelper(List(createSimpleResult(createFile("/foo", 2))),
      optTransformFunc = Some(transformer))

    expectFailedFuture[IllegalStateException](helper.executeStream()) should be(exception)
  }

  /**
    * A test helper class managing the environment for testing an element
    * source.
    *
    * @param readResults      the results to be returned from an iteration function
    * @param optTransformFunc an option with a transformation function
    */
  private class SourceTestHelper(readResults: List[IterateFuncResult[String, Int]],
                                 optTransformFunc: Option[ResultTransformer[Int]] = None) {

    import system.dispatcher

    /**
      * Stores the results to be returned by the iteration function. Here an
      * atomic reference is used because the list is accessed from another
      * thread.
      */
    private val refResults = new AtomicReference(readResults)

    /** Stores the state values passed to the iterate function. */
    private val refStates = new AtomicReference(List.empty[Int])

    /** Stores the state passed to the completion function. */
    private val refCompleteState = new AtomicInteger

    /** Stores the function for fetching the next folder. */
    private val refFolderFunc = new AtomicReference[NextFolderFunc[String]]

    /**
      * Executes a stream with the test source and returns the resulting
      * future.
      *
      * @return the future result of the stream execution
      */
    def executeStream(): Future[List[FsElement]] = {
      val source = new ElementSource(InitState, RootFolder, Some(completionFunc _), optTransformFunc)(iterateFunc)
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
      * Returns the state that was passed to the completion function.
      *
      * @return the completion state
      */
    def completionState: Int = refCompleteState.get()

    /**
      * Returns the function to obtain the next folder that was passed to the
      * iterate function.
      *
      * @return the next folder function
      */
    def nextFolderFunc: NextFolderFunc[String] = refFolderFunc.get()

    /**
      * Returns an iterate function. The function returns the single result
      * objects. It also stores the passed in data. If a result object contains
      * no result, an exception is thrown, simulating a processing error.
      *
      * @return the iterate function
      */
    private def iterateFunc: IterateFunc[String, Int] = (state, next) => {
      val states = refStates.get()
      refStates.set(state :: states)
      if (refFolderFunc.get() == null) refFolderFunc.set(next)
      else {
        next should be(refFolderFunc.get())
      }

      refResults.get() match {
        case h :: t =>
          refResults.set(t)
          if (h._2.nonEmpty || h._3.nonEmpty)
            h
          else throw new IllegalStateException("Simulated processing error!")
        case _ =>
          (EndState, None, None)
      }

    }

    /**
      * The completion function. Stores the last state.
      *
      * @param lastState the last state
      */
    private def completionFunc(lastState: Int): Unit = {
      refCompleteState.set(lastState)
    }
  }

}
