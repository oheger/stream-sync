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
import java.time.temporal.ChronoUnit

import akka.actor.ActorSystem
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.{ActorMaterializer, ClosedShape}
import akka.testkit.TestKit
import com.github.sync.SyncTypes._
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
    * @param level        the level of the file
    * @param optOrgUri    an option with the original URI
    * @return the file element
    */
  private def createFile(uri: String, lastModified: Instant = FileTime, level: Int = 0,
                         optOrgUri: Option[String] = None): FsFile =
    FsFile(relativeUri = uri, size = uri.length, level = level, lastModified = lastModified,
      optOriginalUri = optOrgUri)

  /**
    * Generates a test folder element with the specified URI. (Levels are
    * irrelevant for the sync stage; therefore an arbitrary value can be used.)
    *
    * @param uri       the URI of the folder
    * @param level     the level of the folder
    * @param optOrgUri an option with the original URI
    * @return the folder element
    */
  private def createFolder(uri: String, level: Int = 0, optOrgUri: Option[String] = None): FsFolder =
    FsFolder(uri, level)

  /**
    * Convenience function to create a sync operation. The function applies
    * some defaults for missing properties.
    *
    * @param element   the element subject to the operation
    * @param action    the action
    * @param level     the level
    * @param optSrcUri an option source URI
    * @param optDstUri an optional destination URI
    * @return the sync operation
    */
  private def createOp(element: FsElement, action: SyncAction, level: Int = 0,
                       optSrcUri: Option[String] = None, optDstUri: Option[String] = None): SyncOperation =
    SyncOperation(element, action, level, optSrcUri getOrElse element.relativeUri,
      optDstUri getOrElse element.relativeUri)
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
    * @param source1         source 1 to sync
    * @param source2         source 2 to sync
    * @param ignoreTimeDelta a time delta in files to be ignored
    * @return the sequence with sync operations
    */
  private def runStage(source1: Source[FsElement, Any], source2: Source[FsElement, Any],
                       ignoreTimeDelta: Int = 0): Seq[SyncOperation] = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val foldSink =
      Sink.fold[List[SyncOperation], SyncOperation](List.empty[SyncOperation]) { (lst, e) =>
        e :: lst
      }
    val g = RunnableGraph.fromGraph(GraphDSL.create(foldSink) { implicit builder =>
      sink =>
        import GraphDSL.Implicits._
        val syncStage = builder.add(new SyncStage(ignoreTimeDelta))
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
    val expOps = files map (e => createOp(element = e, action = ActionCreate))

    runStage(Source(files), Source.empty) should contain theSameElementsAs expOps
  }

  it should "handle an empty destination directory" in {
    val files = List(createFile("song1.mp3"), createFile("song2.mp3"),
      createFile("songOther.ogg"))
    val expOps = files map (e => createOp(element = e, action = ActionRemove))

    runStage(Source.empty, Source(files)) should contain theSameElementsAs expOps
  }

  it should "set correct URIs for create operations" in {
    val files = List(createFile("/song1.mp3", optOrgUri = Some("/foo1.mp3")),
      createFile("/song2.mp3", optOrgUri = Some("/foo2.mp3")),
      createFile("songOther.ogg", optOrgUri = Some("/bar.ogg")))
    val expOps = files map { e =>
      createOp(element = e, action = ActionCreate, optSrcUri = Some(e.originalUri), optDstUri = Some(e.originalUri))
    }

    runStage(Source(files), Source.empty) should contain theSameElementsAs expOps
  }

  it should "set correct URIs for remove operations" in {
    val files = List(createFile("/song1.mp3", optOrgUri = Some("/org1.mp3")),
      createFile("/song2.mp3", optOrgUri = Some("/org2.mp3")),
      createFile("/songOther.ogg", optOrgUri = Some("/otherOrg.ogg")))
    val expOps = files map (e => createOp(element = e, action = ActionRemove, optSrcUri = Some(e.originalUri),
      optDstUri = Some(e.originalUri)))

    runStage(Source.empty, Source(files)) should contain theSameElementsAs expOps
  }

  it should "handle an empty target directory with a delay" in {
    val sourceTarget = Source.single(createFile("song0.mp3"))
      .delay(200.millis)
      .filterNot(_.relativeUri endsWith ".mp3")
    val files = List(createFile("song1.mp3"), createFile("song2.mp3"))
    val expOps = files map (e => createOp(element = e, action = ActionCreate))

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
    val expSync = List(createOp(file1, ActionCreate), createOp(file3, ActionRemove))

    runStage(Source(filesSource), Source(filesDest)) should contain theSameElementsAs expSync
  }

  it should "respect original URIs when syncing file structures" in {
    val file1 = createFile("/song1.mp3", optOrgUri = Some("/org1.mp3"))
    val file2 = createFile("/song2.mp3")
    val file3 = createFile("/song3.mp3", optOrgUri = Some("/org3.mp3"))
    val filesSource = List(file1, file2)
    val filesDest = List(file2, file3)
    val expSync = List(createOp(file1, ActionCreate, optSrcUri = Some(file1.originalUri),
      optDstUri = Some(file1.originalUri)),
      createOp(file3, ActionRemove, optSrcUri = Some(file3.originalUri), optDstUri = Some(file3.originalUri)))

    runStage(Source(filesSource), Source(filesDest)) should contain theSameElementsAs expSync
  }

  it should "sync files with a different modification time" in {
    val FileUri = "testFile.dat"
    val fileSrc = createFile(FileUri)
    val fileDest = createFile(FileUri,
      lastModified = Instant.parse("2018-08-10T22:13:55.45Z"))
    val sourceOrg = Source.single(fileSrc)
    val sourceTarget = Source.single(fileDest)
    val expOp = createOp(fileSrc, ActionOverride)

    runStage(sourceOrg, sourceTarget) should contain only expOp
  }

  it should "respect original URIs when overriding files" in {
    val FileUri = "/testFile.dat"
    val OrgUriSrc = "/testFileInSource.dat"
    val OrgUriDst = "/testFileInDestination.dat"
    val fileSrc = createFile(FileUri, optOrgUri = Some(OrgUriSrc))
    val fileDest = createFile(FileUri, optOrgUri = Some(OrgUriDst),
      lastModified = Instant.parse("2018-08-10T22:13:55.45Z"))
    val sourceOrg = Source.single(fileSrc)
    val sourceTarget = Source.single(fileDest)
    val expOp = createOp(fileSrc, ActionOverride, optSrcUri = Some(OrgUriSrc), optDstUri = Some(OrgUriDst))

    runStage(sourceOrg, sourceTarget) should contain only expOp
  }

  it should "ignore time differences below the configured threshold" in {
    val TimeDelta = 5
    val FileUri = "/equalFile.dat"
    val fileSrc = createFile(FileUri)
    val fileDest = createFile(FileUri,
      lastModified = FileTime.plus(TimeDelta - 1, ChronoUnit.SECONDS).plusMillis(999))
    val sourceOrg = Source.single(fileSrc)
    val sourceTarget = Source.single(fileDest)

    runStage(sourceOrg, sourceTarget, TimeDelta) should have size 0
  }

  it should "sync files with a different size" in {
    val fileSrc = createFile("test.txt")
    val fileDest = fileSrc.copy(size = fileSrc.size - 1)
    val sourceOrg = Source.single(fileSrc)
    val sourceTarget = Source.single(fileDest)
    val expOp = createOp(fileSrc, ActionOverride)

    runStage(sourceOrg, sourceTarget) should contain only expOp
  }

  it should "handle deleted folders" in {
    val elemA = createFile("/a.txt")
    val elemB = createFile("/b.txt")
    val elemC = createFolder("/c", optOrgUri = Some("/c-org"))
    val elemD = createFile("/d.txt")
    val elemE = createFile("/e.txt", optOrgUri = Some("/eOrg.txt"))
    val elemF = createFolder("/f")
    val elemCSubFile = createFile("/c/file.sub", level = 1)
    val elemCSubFolder = createFolder("/c/subFolder", 1)
    val elemCSubSubFile = createFile("/c/subFolder/deep.file", level = 2)
    val elemFSubFileA = createFile("/f/a.sub", level = 1)
    val elemFSubFileB = createFile("/f/b.sub", level = 1)
    val sourceOrg = Source(List(elemA, elemB, elemD, elemF, elemFSubFileA, elemFSubFileB))
    val sourceTarget = Source(List(elemA, elemC, elemE, elemCSubFile, elemCSubFolder,
      elemCSubSubFile, elemF, elemFSubFileA))
    val expOps = List(createOp(elemB, ActionCreate), createOp(elemD, ActionCreate),
      createOp(elemE, ActionRemove, optSrcUri = Some(elemE.originalUri), optDstUri = Some(elemE.originalUri)),
      createOp(elemCSubFile, ActionRemove), createOp(elemCSubSubFile, ActionRemove),
      createOp(elemFSubFileB, ActionCreate, 1), createOp(elemCSubFolder, ActionRemove),
      createOp(elemC, ActionRemove, optSrcUri = Some(elemC.originalUri), optDstUri = Some(elemC.originalUri)))

    runStage(sourceOrg, sourceTarget) should contain theSameElementsInOrderAs expOps
  }

  it should "handle a file that has been converted to a folder" in {
    val elemOrgFile = createFile("/my.dat", optOrgUri = Some("/org.dat"))
    val elemConvertedFolder = createFolder(elemOrgFile.relativeUri)
    val elemOther = createFile("other.foo")
    val elemSubFile = createFile(elemConvertedFolder.relativeUri + "/sub.dat", level = 1)
    val sourceOrg = Source(List(elemConvertedFolder, elemOther, elemSubFile))
    val sourceTarget = Source(List(elemOrgFile))
    val expOps = List(createOp(elemOrgFile, ActionRemove, optSrcUri = Some(elemOrgFile.originalUri),
      optDstUri = Some(elemOrgFile.originalUri)),
      createOp(elemConvertedFolder, ActionCreate),
      createOp(elemOther, ActionCreate),
      createOp(elemSubFile, ActionCreate, 1))

    runStage(sourceOrg, sourceTarget) should contain theSameElementsInOrderAs expOps
  }

  it should "handle a folder that has been converted to a file" in {
    val elemOrgFolder = createFolder("/test/my.data", level = 1, optOrgUri = Some("/org/folder"))
    val elemConvertedFile = createFile(elemOrgFolder.relativeUri, level = 1)
    val elemOther = createFile("/test/other.foo", level = 1)
    val elemSubFile = createFile(elemOrgFolder.relativeUri + "/sub.dat", level = 2)
    val sourceOrg = Source(List(elemConvertedFile, elemOther))
    val sourceTarget = Source(List(elemOrgFolder, elemSubFile))
    val expOps = List(createOp(elemSubFile, ActionRemove, 1),
      createOp(elemOther, ActionCreate, 1),
      createOp(elemOrgFolder, ActionRemove, 1, optSrcUri = Some(elemOrgFolder.originalUri),
        optDstUri = Some(elemOrgFolder.originalUri)),
      createOp(elemConvertedFile, ActionCreate, 1))

    runStage(sourceOrg, sourceTarget) should contain theSameElementsInOrderAs expOps
  }

  it should "handle folder hierarchies correctly" in {
    val elemFolderA = createFolder("a")
    val elemFileAPlus = createFile("aFile.txt")
    val sourceOrg = Source(List(elemFileAPlus))
    val sourceTarget = Source(List(elemFolderA, elemFileAPlus))
    val expOp = createOp(elemFolderA, ActionRemove)

    runStage(sourceOrg, sourceTarget) should contain only expOp
  }

  it should "handle a comparison with source level < destination level" in {
    val elemFolderA = createFolder("a")
    val elemFolderSub = createFolder("a/subA", 1)
    val elemFolderB = createFolder("b")
    val sourceOrg = Source(List(elemFolderA, elemFolderB, elemFolderSub))
    val sourceTarget = Source(List(elemFolderA, elemFolderSub))
    val expOp = createOp(elemFolderB, ActionCreate)

    runStage(sourceOrg, sourceTarget) should contain only expOp
  }

  it should "handle a comparison with source level > destination level" in {
    val elemFolderA = createFolder("a")
    val elemFolderSub = createFolder("a/subA", 1)
    val elemFolderB = createFolder("b")
    val sourceOrg = Source(List(elemFolderA, elemFolderSub))
    val sourceTarget = Source(List(elemFolderA, elemFolderB, elemFolderSub))
    val expOp = createOp(elemFolderB, ActionRemove)

    runStage(sourceOrg, sourceTarget) should contain only expOp
  }
}
