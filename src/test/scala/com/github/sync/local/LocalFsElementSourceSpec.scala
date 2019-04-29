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

package com.github.sync.local

import java.io.IOException
import java.nio.file.{DirectoryStream, Files, NoSuchFileException, Path}
import java.util
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.testkit.TestKit
import com.github.sync.FileTestHelper
import com.github.sync.SyncTypes._
import com.github.sync.impl.ElementSource
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Test class for ''LocalFsElementSource''.
  */
class LocalFsElementSourceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfter with BeforeAndAfterAll with Matchers with FileTestHelper {
  def this() = this(ActorSystem("LocalFsElementSourceSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  after {
    tearDownTestFile()
  }

  /**
    * Obtains the URI from the given parent element. Adds a slash as necessary.
    *
    * @param parent the parent element
    * @return the URI to be used for this element
    */
  private def parentUri(parent: FsElement): String = parent.relativeUri + "/"

  /**
    * Generates the URI of a child element based on its parent.
    *
    * @param parent the parent element
    * @param name   the name of the child
    * @return the resulting URI of the child
    */
  private def childUri(parent: FsElement, name: String): String =
    parentUri(parent) + name

  /**
    * Maps the given relative URI to a path. Removes the leading slash to
    * ensure that the URI is interpreted as relative URI.
    *
    * @param uri the relative URI
    * @return the resulting path
    */
  private def pathForUri(uri: String): Path =
    testDirectory resolve uri.drop(1)

  /**
    * Creates a file with a given name in a given directory. The content of the
    * file is its name as string.
    *
    * @param dir  the directory
    * @param name the name of the file to be created
    * @return the new file element and its path
    */
  private def createFile(dir: FsFolder, name: String): (FsFile, Path) = {
    val uri = childUri(dir, name)
    val path = writeFileContent(pathForUri(uri), name)
    (FsFile(uri, dir.level + 1, Files.getLastModifiedTime(path).toInstant, name.length), path)
  }

  /**
    * Creates a directory below the given parent directory.
    *
    * @param parent the parent directory
    * @param name   the name of the new directory
    * @return the newly created directory
    */
  private def createDir(parent: FsFolder, name: String): (FsFolder, Path) = {
    val uri = childUri(parent, name)
    (FsFolder(uri, parent.level + 1), Files.createDirectory(pathForUri(uri)))
  }

  /**
    * Creates a directory structure with some test files and directories.
    *
    * @return a map with the elements created and their associated paths
    */
  private def setUpDirectoryStructure(): Map[FsElement, Path] = {
    val rootFolder = FsFolder("", -1)
    val rootFiles = List(createFile(rootFolder, "test.txt"),
      createFile(rootFolder, "noMedium1.mp3"))
    val dir1 = createDir(rootFolder, "Medium1")
    val dir2 = createDir(rootFolder, "Medium2")
    val dir1Files = List(
      createFile(dir1._1, "noMedium2.mp3"),
      createFile(dir1._1, "README.TXT"),
      createFile(dir1._1, "medium1.settings"))
    val sub1 = createDir(dir1._1, "aSub1")
    val sub1Files = List(createFile(sub1._1, "medium1Song1.mp3"))
    val sub1Sub = createDir(sub1._1, "subSub")
    val sub1SubFiles = List(
      createFile(sub1Sub._1, "medium1Song2.mp3"),
      createFile(sub1Sub._1, "medium1Song3.mp3"))
    val sub2 = createDir(dir1._1, "anotherSub")
    val sub2Files = List(createFile(sub2._1, "song.mp3"))
    val dir2Files = List(
      createFile(dir2._1, "medium2Song(1).mp3"),
      createFile(dir2._1, "medium2Song(2).mp3")
    )
    val sub3 = createDir(dir2._1, "medium2Sub")
    val sub3Files = List(createFile(sub3._1, "medium2 Song3.mp3"))

    val dirs = List(dir1, dir2, sub1, sub2, sub1Sub, sub3)
    List(dirs, rootFiles, dir1Files, sub1Files, sub1SubFiles, sub2Files,
      dir2Files, sub3Files).flatten.toMap
  }

  /**
    * Returns a sink for collecting the items produced by the test source.
    *
    * @return the sink
    */
  private def foldSink(): Sink[FsElement, Future[List[FsElement]]] =
    Sink.fold[List[FsElement], FsElement](List.empty[FsElement])((lst, p) => p :: lst)

  /**
    * Executes the test source on a directory structure.
    *
    * @param source the source to be run
    * @return a sequence with the paths produced by the source
    */
  private def runSource(source: Source[FsElement, Any]): Seq[FsElement] = {
    val futRun = obtainSourceFuture(source)
    Await.result(futRun, 5.seconds).reverse
  }

  /**
    * Executes the test source on a directory structure and returns the
    * resulting future.
    *
    * @param source the source to be run
    * @return the future with the result of the execution
    */
  private def obtainSourceFuture(source: Source[FsElement, Any]): Future[List[FsElement]] = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    source.runWith(foldSink())
  }

  /**
    * Creates a special directory stream factory that returns stream wrapper
    * objects. All created streams are stored in a queue and can thus be
    * fetched later. So it can be checked whether they all have been closed.
    *
    * @return a tuple with the queue and the factory
    */
  private def createStreamWrapperFactory(failOnClose: Boolean = false):
  (BlockingQueue[DirectoryStreamWrapper], LocalFsElementSource.StreamFactory) = {
    val queue = new LinkedBlockingQueue[DirectoryStreamWrapper]
    val factory: LocalFsElementSource.StreamFactory = p => {
      val stream = new DirectoryStreamWrapper(Files.newDirectoryStream(p), failOnClose)
      queue offer stream
      stream
    }
    (queue, factory)
  }

  /**
    * Checks that all directory streams that have been created have been
    * closed.
    *
    * @param queue the queue with streams
    */
  @tailrec private def checkAllStreamsClosed(queue: BlockingQueue[DirectoryStreamWrapper]):
  Unit = {
    if (!queue.isEmpty) {
      queue.poll().closed.get() shouldBe true
      checkAllStreamsClosed(queue)
    }
  }

  /**
    * Returns a configuration for the source to be tested.
    *
    * @return the test configuration
    */
  private def sourceConfig(): LocalFsConfig = LocalFsConfig(testDirectory, None)

  import system.dispatcher

  /**
    * Returns the factory to create a source.
    *
    * @return the source factory
    */
  private def sourceFactory: ElementSourceFactory = new ElementSourceFactory {
    override def createElementSource[F, S](initState: S, initFolder: SyncFolderData[F],
                                           optCompletionFunc: Option[CompletionFunc[S]])
                                          (iterateFunc: IterateFunc[F, S]):
    Graph[SourceShape[FsElement], NotUsed] = new ElementSource(initState, initFolder, optCompletionFunc)(iterateFunc)
  }

  "A DirectoryStreamSource" should "return all elements in the scanned folder structure" in {
    val fileData = setUpDirectoryStructure()
    val source = LocalFsElementSource(sourceConfig())(sourceFactory)
    val files = runSource(source)

    files should contain theSameElementsAs fileData.keys
  }

  it should "support setting a start directory" in {
    val StartUri = "/Medium2"
    val fileData = setUpDirectoryStructure()
    val expectedElements = fileData.keys.filter { e =>
      e.relativeUri.startsWith(StartUri) && e.relativeUri != StartUri
    }
    val source = LocalFsElementSource(sourceConfig(), startDirectory = StartUri)(sourceFactory)

    val files = runSource(source)
    files should contain theSameElementsAs expectedElements
  }

  it should "close all directory streams it creates" in {
    setUpDirectoryStructure()
    val (queue, factory) = createStreamWrapperFactory()
    val source = LocalFsElementSource(sourceConfig(), streamFactory = factory)(sourceFactory)

    runSource(source)
    queue.isEmpty shouldBe false
    checkAllStreamsClosed(queue)
  }

  it should "support canceling stream processing" in {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val Count = 32
    val root = FsFolder("", 0)
    (1 to Count).foreach(i => createFile(root, s"test$i.txt"))
    val (queue, factory) = createStreamWrapperFactory()
    val source = LocalFsElementSource(sourceConfig(), streamFactory = factory)(sourceFactory)
    val srcDelay = source.delay(1.second, DelayOverflowStrategy.backpressure)
    val (killSwitch, futSrc) = srcDelay
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(foldSink())(Keep.both)
      .run()

    val streamWrapper = queue.poll(1, TimeUnit.SECONDS)
    killSwitch.shutdown()
    val result = Await.result(futSrc, 5.seconds)
    result.size should be < Count
    streamWrapper.closed.get() shouldBe true
    checkAllStreamsClosed(queue)
  }

  it should "ignore exceptions when closing directory streams" in {
    setUpDirectoryStructure()
    val (queue, factory) = createStreamWrapperFactory(failOnClose = true)
    val source = LocalFsElementSource(sourceConfig(), streamFactory = factory)(sourceFactory)

    runSource(source)
    queue.isEmpty shouldBe false
    checkAllStreamsClosed(queue)
  }

  it should "output the elements of a folder in series" in {
    val fileData = setUpDirectoryStructure()
    val folderCount = fileData.keys.count(_.isInstanceOf[FsFolder])
    val source = LocalFsElementSource(sourceConfig())(sourceFactory)

    val elems = runSource(source)
    val (_, dirChanges) = elems.foldLeft((testDirectory, 0)) { (s, e) =>
      val parentDir = fileData(e).getParent
      if (parentDir != s._1) (parentDir, s._2 + 1)
      else s
    }
    dirChanges should be(folderCount)
  }

  it should "handle a non existing root directory in BFS mode" in {
    val config = LocalFsConfig(createPathInDirectory("nonExisting"), None)
    val source = LocalFsElementSource(config)(sourceFactory)

    intercept[NoSuchFileException] {
      runSource(source)
    }
  }

  it should "iterate over sub folders in alphabetical order" in {
    def rootUri(elem: FsElement): String = {
      val pos = elem.relativeUri.indexOf('/', 1)
      if (pos < 0) "" else elem.relativeUri.substring(0, pos)
    }

    setUpDirectoryStructure()
    val (otherMedium, _) = createDir(FsFolder("", -1), "anotherDir")
    createFile(otherMedium, "data1.txt")
    createFile(otherMedium, "data2.txt")
    val source = LocalFsElementSource(sourceConfig())(sourceFactory)

    val files = runSource(source)
    val folderIteration = files.foldLeft(List.empty[String]) { (lst, e) =>
      val parent = rootUri(e)
      if (!lst.contains(parent)) parent :: lst
      else lst
    }
    folderIteration should be(List("/anotherDir", "/Medium2", "/Medium1", ""))
  }
}

/**
  * Simple case class for testing whether the transformation function is
  * correctly applied.
  *
  * @param path  the wrapped path
  * @param isDir a flag whether the path is a directory
  */
case class PathData(path: Path, isDir: Boolean)

/**
  * A wrapper around a directory stream to verify that the stream is correctly
  * closed and to test exception handling.
  *
  * @param stream      the stream to be wrapped
  * @param failOnClose flag whether the close operation should throw
  */
class DirectoryStreamWrapper(stream: DirectoryStream[Path], failOnClose: Boolean)
  extends DirectoryStream[Path] {
  /** Stores a flag whether the stream was closed. */
  val closed = new AtomicBoolean

  override def iterator(): util.Iterator[Path] = stream.iterator()

  override def close(): Unit = {
    stream.close()
    closed set true
    if (failOnClose)
      throw new IOException("Test exception!")
  }
}
