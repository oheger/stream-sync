/*
 * Copyright 2018 The Developers Team.
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

package com.github.sync

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ClosedShape}
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

object SyncStageSpec {
  /** The default timestamp used for test files. */
  private val FileTime = Instant.parse("2018-08-05T14:22:55.00Z")

  /**
    * Generates a test file element with the specified settings.
    *
    * @param uri          the URI of the file
    * @param lastModified the last modified time
    * @return the file element
    */
  private def createFile(uri: String, lastModified: Instant = FileTime): FsFile =
    FsFile(relativeUri = uri, size = uri.length, level = 1, lastModified = lastModified)
}

/**
  * Test class for ''SyncStage''.
  */
class SyncStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with
  BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("SyncStageSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import SyncStageSpec._

  /**
    * Runs the sync stage with the sources specified and returns the resulting
    * sequence of sync operations.
    *
    * @param source1 source 1 to sync
    * @param source2 source 2 to sync
    * @return the sequence with sync operations
    */
  private def runStage(source1: Source[FsElement, Any], source2: Source[FsElement, Any]):
  Seq[SyncOperation] = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val foldSink =
      Sink.fold[List[SyncOperation], SyncOperation](List.empty[SyncOperation]) { (lst, e) =>
        e :: lst
      }
    val g = RunnableGraph.fromGraph(GraphDSL.create(foldSink) { implicit builder =>
      sink =>
        import GraphDSL.Implicits._
        val syncStage = builder.add(new SyncStage)
        source1 ~> syncStage.in0
        source2 ~> syncStage.in1
        syncStage.out ~> sink
        ClosedShape
    })

    val futSyncOps = g.run()
    Await.result(futSyncOps, 3.seconds).reverse
  }

  "A SyncStage" should "correctly handle two empty sources" in {
    runStage(Source.empty, Source.empty) should have size 0
  }

  it should "handle an empty target directory" in {
    val files = List(createFile("song1.mp3"), createFile("song2.mp3"),
      createFile("songOther.ogg"))
    val expOps = files map (e => SyncOperation(element = e, action = ActionCreate))

    runStage(Source(files), Source.empty) should contain theSameElementsAs expOps
  }

  it should "handle an empty destination directory" in {
    val files = List(createFile("song1.mp3"), createFile("song2.mp3"),
      createFile("songOther.ogg"))
    val expOps = files map (e => SyncOperation(element = e, action = ActionRemove))

    runStage(Source.empty, Source(files)) should contain theSameElementsAs expOps
  }

  it should "handle an empty target directory with a delay" in {
    val sourceTarget = Source.single(createFile("song0.mp3"))
      .delay(200.millis)
      .filterNot(_.relativeUri endsWith ".mp3")
    val files = List(createFile("song1.mp3"), createFile("song2.mp3"))
    val expOps = files map (e => SyncOperation(element = e, action = ActionCreate))

    runStage(Source(files), sourceTarget) should contain theSameElementsAs expOps
  }

  it should "generate an empty stream if both structures are identical" in {
    val files = List(createFile("song1.mp3"), createFile("song2.mp3"),
      createFile("songOther.ogg"))
    val sourceOrg = Source(files)
    val sourceTarget = Source(files)

    runStage(sourceOrg, sourceTarget) should have size 0
  }

  it should "sync simple file structures" in {
    val file1 = createFile("song1.mp3")
    val file2 = createFile("song2.mp3")
    val file3 = createFile("song3.mp3")
    val filesSource = List(file1, file2)
    val filesDest = List(file2, file3)
    val expSync = List(SyncOperation(file1, ActionCreate), SyncOperation(file3, ActionRemove))

    runStage(Source(filesSource), Source(filesDest)) should contain theSameElementsAs expSync
  }

  it should "sync files with a different modification time" in {
    val FileUri = "testFile.dat"
    val fileSrc = createFile(FileUri)
    val fileDest = createFile(FileUri,
      lastModified = Instant.parse("2018-08-10T22:13:55.45Z"))
    val sourceOrg = Source.single(fileSrc)
    val sourceTarget = Source.single(fileDest)
    val expOp = SyncOperation(fileSrc, ActionOverride)

    runStage(sourceOrg, sourceTarget) should contain only expOp
  }

  it should "sync files with a different size" in {
    val fileSrc = createFile("test.txt")
    val fileDest = fileSrc.copy(size = fileSrc.size - 1)
    val sourceOrg = Source.single(fileSrc)
    val sourceTarget = Source.single(fileDest)
    val expOp = SyncOperation(fileSrc, ActionOverride)

    runStage(sourceOrg, sourceTarget) should contain only expOp
  }
}
