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

import com.github.sync.SyncTypes.*
import com.github.sync.SyncTypes.SyncAction.*
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.Await
import scala.concurrent.duration.*

object MirrorStageSpec:
  /** The default timestamp used for test files. */
  private val FileTime = Instant.parse("2018-08-05T14:22:55.00Z")

  /**
    * Generates a synthetic ID of an element based on its URI. The ID can be
    * generated either for the source or the destination structure.
    *
    * @param uri    the URI of this element
    * @param source flag whether this is an ID for the source structure
    * @return the ID for this element
    */
  private def elementID(uri: String, source: Boolean = true): String =
    val idPrefix = if source then "src" else "dst"
    s"$idPrefix:$uri"

  /**
    * Generates a test file element with the specified settings.
    *
    * @param uri          the URI of the file
    * @param lastModified the last modified time
    * @param level        the level of the file
    * @return the file element
    */
  private def createFile(uri: String, lastModified: Instant = FileTime, level: Int = 0): FsFile =
    FsFile(elementID(uri), relativeUri = uri, size = uri.length, level = level, lastModified = lastModified)

  /**
    * Generates a test folder element with the specified URI.
    *
    * @param uri       the URI of the folder
    * @param level     the level of the folder
    * @param optOrgUri an option with the original URI
    * @return the folder element
    */
  private def createFolder(uri: String, level: Int = 0, optOrgUri: Option[String] = None): FsFolder =
    FsFolder(elementID(uri), uri, level)

  /**
    * Converts the given element to an element from the destination structure
    * by adapting its ID accordingly.
    *
    * @param elem the element to convert
    * @return the element from the destination structure
    */
  private def destinationElem(elem: FsElement): FsElement =
    val newID = elementID(elem.relativeUri, source = false)
    elem match
      case fsFile: FsFile => fsFile.copy(id = newID)
      case fsFolder: FsFolder => fsFolder.copy(id = newID)

  /**
    * Returns a destination ID for the given element. The ID is derived from
    * the element's URI. The result can be passed to the ''createOp()''
    * function.
    *
    * @param elem the element
    * @return the corresponding ID of the destination element
    */
  private def destinationID(elem: FsElement): Some[String] =
    Some(elementID(elem.relativeUri, source = false))

  /**
    * Converts all the elements in the given list to elements from the
    * destination structure.
    *
    * @param elems the list with elements
    * @return the list with converted elements
    */
  private def destinationElems(elems: List[FsElement]): List[FsElement] = elems map destinationElem

  /**
    * Convenience function to create a sync operation. The function applies
    * some defaults for missing properties.
    *
    * @param element  the element subject to the operation
    * @param action   the action
    * @param level    the level
    * @param optDstID an optional destination ID
    * @return the sync operation
    */
  private def createOp(element: FsElement, action: SyncAction, level: Int = 0,
                       optDstID: Option[String] = None): SyncOperation =
    SyncOperation(element, action, level, optDstID getOrElse "-")

/**
  * Test class for ''SyncStage''.
  */
class MirrorStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("SyncStageSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  import MirrorStageSpec.*

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
                       ignoreTimeDelta: Int = 0): Seq[SyncOperation] =
    val foldSink =
      Sink.fold[List[SyncOperation], SyncOperation](List.empty[SyncOperation]) { (lst, e) =>
        e :: lst
      }
    val g = RunnableGraph.fromGraph(GraphDSL.createGraph(foldSink) { implicit builder =>
      sink =>
        import GraphDSL.Implicits.*
        val mirrorStage = builder.add(new MirrorStage(IgnoreTimeDelta(ignoreTimeDelta.seconds)))
        source1 ~> mirrorStage.in0
        source2 ~> mirrorStage.in1
        mirrorStage.out ~> sink
        ClosedShape
    })

    val futSyncOps = g.run()
    Await.result(futSyncOps, 3.seconds).reverse

  "A MirrorStage" should "correctly handle two empty sources" in {
    runStage(Source.empty, Source.empty) should have size 0
  }

  it should "handle an empty destination directory" in {
    val files = List(createFile("song1.mp3"), createFile("song2.mp3"),
      createFile("songOther.ogg"))
    val expOps = files map (e => createOp(element = e, action = ActionCreate))

    runStage(Source(files), Source.empty) should contain theSameElementsAs expOps
  }

  it should "handle an empty source directory" in {
    val files = List(createFile("song1.mp3"), createFile("song2.mp3"),
      createFile("songOther.ogg"))
    val expOps = files map (e => createOp(element = e, action = ActionRemove, optDstID = Some(e.id)))

    runStage(Source.empty, Source(files)) should contain theSameElementsAs expOps
  }

  it should "handle an empty target directory with a delay" in {
    val sourceTarget = Source.single(createFile("song0.mp3"))
      .delay(200.millis)
      .filterNot(_.relativeUri.endsWith(".mp3"))
    val files = List(createFile("song1.mp3"), createFile("song2.mp3"))
    val expOps = files map (e => createOp(element = e, action = ActionCreate))

    runStage(Source(files), sourceTarget) should contain theSameElementsAs expOps
  }

  it should "produce Noop actions if both structures are identical" in {
    val files = List(createFile("song1.mp3"), createFile("song2.mp3"),
      createFile("songOther.ogg"))
    val sourceOrg = Source(files)
    val sourceTarget = Source(destinationElems(files))
    val expOps = files map (e => createOp(element = e, action = ActionNoop))

    runStage(sourceOrg, sourceTarget) should contain theSameElementsAs expOps
  }

  it should "sync simple file structures" in {
    val file1 = createFile("song1.mp3")
    val file2 = createFile("song2.mp3")
    val file3 = createFile("song3.mp3")
    val filesSource = List(file1, file2)
    val filesDest = destinationElems(List(file2, file3))
    val expSync = List(createOp(file1, ActionCreate),
      createOp(file2, ActionNoop),
      createOp(destinationElem(file3), ActionRemove, optDstID = destinationID(file3)))

    runStage(Source(filesSource), Source(filesDest)) should contain theSameElementsAs expSync
  }

  it should "respect original URIs when syncing file structures" in {
    val file1 = createFile("/song1.mp3")
    val file2 = createFile("/song2.mp3")
    val file3 = createFile("/song3.mp3")
    val filesSource = List(file1, file2)
    val filesDest = destinationElems(List(file2, file3))
    val expSync = List(createOp(file1, ActionCreate),
      createOp(file2, ActionNoop),
      createOp(destinationElem(file3), ActionRemove, optDstID = destinationID(file3)))

    runStage(Source(filesSource), Source(filesDest)) should contain theSameElementsAs expSync
  }

  it should "sync files with a different modification time" in {
    val FileUri = "testFile.dat"
    val fileSrc = createFile(FileUri)
    val fileDest = destinationElem(createFile(FileUri,
      lastModified = Instant.parse("2018-08-10T22:13:55.45Z")))
    val sourceOrg = Source.single(fileSrc)
    val sourceTarget = Source.single(fileDest)
    val expOp = createOp(fileSrc, ActionOverride, optDstID = Some(fileDest.id))

    runStage(sourceOrg, sourceTarget) should contain only expOp
  }

  it should "respect original URIs when overriding files" in {
    val FileUri = "/testFile.dat"
    val fileSrc = createFile(FileUri)
    val fileDest = destinationElem(createFile(FileUri,
      lastModified = Instant.parse("2018-08-10T22:13:55.45Z")))
    val sourceOrg = Source.single(fileSrc)
    val sourceTarget = Source.single(fileDest)
    val expOp = createOp(fileSrc, ActionOverride, optDstID = Some(fileDest.id))

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
    val expOp = createOp(fileSrc, ActionNoop)

    runStage(sourceOrg, sourceTarget, TimeDelta) should contain only expOp
  }

  it should "sync files with a different size" in {
    val fileSrc = createFile("test.txt")
    val fileDest = destinationElem(fileSrc.copy(size = fileSrc.size - 1))
    val sourceOrg = Source.single(fileSrc)
    val sourceTarget = Source.single(fileDest)
    val expOp = createOp(fileSrc, ActionOverride, optDstID = Some(fileDest.id))

    runStage(sourceOrg, sourceTarget) should contain only expOp
  }

  it should "handle deleted folders" in {
    val elemA = createFile("/a.txt")
    val elemB = createFile("/b.txt")
    val elemC = createFolder("/c", optOrgUri = Some("/c-org"))
    val elemD = createFile("/d.txt")
    val elemE = createFile("/e.txt")
    val elemF = createFolder("/f")
    val elemCSubFile = createFile("/c/file.sub", level = 1)
    val elemCSubFolder = createFolder("/c/subFolder", 1)
    val elemCSubSubFile = createFile("/c/subFolder/deep.file", level = 2)
    val elemFSubFileA = createFile("/f/a.sub", level = 1)
    val elemFSubFileB = createFile("/f/b.sub", level = 1)
    val sourceOrg = Source(List(elemA, elemB, elemD, elemF, elemFSubFileA, elemFSubFileB))
    val sourceTarget = Source(destinationElems(List(elemA, elemC, elemE, elemCSubFile, elemCSubFolder,
      elemCSubSubFile, elemF, elemFSubFileA)))
    val expOps = List(createOp(elemA, ActionNoop), createOp(elemB, ActionCreate), createOp(elemD, ActionCreate),
      createOp(destinationElem(elemE), ActionRemove, optDstID = destinationID(elemE)),
      createOp(destinationElem(elemCSubFile), ActionRemove, optDstID = destinationID(elemCSubFile)),
      createOp(destinationElem(elemCSubSubFile), ActionRemove, optDstID = destinationID(elemCSubSubFile)),
      createOp(elemF, ActionNoop), createOp(elemFSubFileA, ActionNoop, 1),
      createOp(elemFSubFileB, ActionCreate, 1),
      createOp(destinationElem(elemCSubFolder), ActionRemove, optDstID = destinationID(elemCSubFolder)),
      createOp(destinationElem(elemC), ActionRemove, optDstID = destinationID(elemC)))

    runStage(sourceOrg, sourceTarget) should contain theSameElementsInOrderAs expOps
  }

  it should "handle a file that has been converted to a folder" in {
    val elemOrgFile = createFile("/my.dat")
    val elemConvertedFolder = createFolder(elemOrgFile.relativeUri)
    val elemOther = createFile("other.foo")
    val elemSubFile = createFile(elemConvertedFolder.relativeUri + "/sub.dat", level = 1)
    val sourceOrg = Source(List(elemConvertedFolder, elemOther, elemSubFile))
    val sourceTarget = Source(List(destinationElem(elemOrgFile)))
    val expOps = List(createOp(destinationElem(elemOrgFile), ActionRemove, optDstID = destinationID(elemOrgFile)),
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
    val sourceTarget = Source(destinationElems(List(elemOrgFolder, elemSubFile)))
    val expOps = List(createOp(destinationElem(elemSubFile), ActionRemove, 1,
      optDstID = destinationID(elemSubFile)),
      createOp(elemOther, ActionCreate, 1),
      createOp(destinationElem(elemOrgFolder), ActionRemove, 1, optDstID = destinationID(elemOrgFolder)),
      createOp(elemConvertedFile, ActionCreate, 1))

    runStage(sourceOrg, sourceTarget) should contain theSameElementsInOrderAs expOps
  }

  it should "handle folder hierarchies correctly" in {
    val elemFolderA = createFolder("a")
    val elemFileAPlus = createFile("aFile.txt")
    val sourceOrg = Source(List(elemFileAPlus))
    val sourceTarget = Source(destinationElems(List(elemFolderA, elemFileAPlus)))
    val expOps = List(createOp(elemFileAPlus, ActionNoop),
      createOp(destinationElem(elemFolderA), ActionRemove, optDstID = destinationID(elemFolderA)))

    runStage(sourceOrg, sourceTarget) should contain theSameElementsInOrderAs expOps
  }

  it should "handle a comparison with source level < destination level" in {
    val elemFolderA = createFolder("a")
    val elemFolderSub = createFolder("a/subA", 1)
    val elemFolderB = createFolder("b")
    val sourceOrg = Source(List(elemFolderA, elemFolderB, elemFolderSub))
    val sourceTarget = Source(List(elemFolderA, elemFolderSub))
    val expOps = List(createOp(elemFolderA, ActionNoop), createOp(elemFolderB, ActionCreate),
      createOp(elemFolderSub, ActionNoop, 1))

    runStage(sourceOrg, sourceTarget) should contain theSameElementsInOrderAs expOps
  }

  it should "handle a comparison with source level > destination level" in {
    val elemFolderA = createFolder("a")
    val elemFolderSub = createFolder("a/subA", 1)
    val elemFolderB = createFolder("b")
    val sourceOrg = Source(List(elemFolderA, elemFolderSub))
    val sourceTarget = Source(destinationElems(List(elemFolderA, elemFolderB, elemFolderSub)))
    val expOps = List(createOp(elemFolderA, ActionNoop),
      createOp(elemFolderSub, ActionNoop, 1),
      createOp(destinationElem(elemFolderB), ActionRemove, optDstID = destinationID(elemFolderB)))

    runStage(sourceOrg, sourceTarget) should contain theSameElementsInOrderAs expOps
  }

  it should "handle new files in a sub directory correctly" in {
    val file1 = createFile("/file_top.txt")
    val folder1 = createFolder("/folder")
    val sub1 = createFolder("/folder/sub1", 1)
    val sub2 = createFolder("/folder/sub2", 1)
    val file2 = createFile(sub1.relativeUri + "/file_sub1.txt", level = 2)
    val file3 = createFile(sub1.relativeUri + "/file_sub2.txt", level = 2)
    val file4 = createFile(sub2.relativeUri + "/other_sub_file.txt", level = 2)
    val folder2 = createFolder("/folder2")
    val sourceOrg = Source(List(file1, folder1, folder2, sub1, sub2, file2, file3, file4))
    val sourceTarget = Source(List(file1, folder1, folder2, sub1, sub2, file4))
    val expOps = List(createOp(file1, ActionNoop), createOp(folder1, ActionNoop), createOp(folder2, ActionNoop),
      createOp(sub1, ActionNoop, level = 1), createOp(sub2, ActionNoop, level = 1),
      createOp(file2, ActionCreate, level = 2), createOp(file3, ActionCreate, level = 2),
      createOp(file4, ActionNoop, level = 2))

    runStage(sourceOrg, sourceTarget) should contain theSameElementsAs expOps
  }

  it should "correctly handle new files in a folder" in {
    val folder1 = createFolder("/folder1")
    val subFolder = createFolder(folder1.relativeUri + "/sub", level = 1)
    val folder2 = createFolder("/folder2")
    val file1 = createFile(subFolder.relativeUri + "/file1.txt", level = 2)
    val fileNew = createFile(subFolder.relativeUri + "/new.txt", level = 2)
    val file2 = createFile(folder2.relativeUri + "/file2.txt", level = 1)
    val sourceOrg = Source(List(folder1, folder2, subFolder, file2, file1, fileNew))
    val sourceTarget = Source(destinationElems(List(folder1, folder2, subFolder, file2, file1)))
    val expOps = List(createOp(folder1, ActionNoop), createOp(folder2, ActionNoop),
      createOp(subFolder, ActionNoop, level = 1), createOp(file1, ActionNoop, level = 2),
      createOp(fileNew, ActionCreate, level = 2), createOp(file2, ActionNoop, level = 1))

    runStage(sourceOrg, sourceTarget) should contain theSameElementsAs expOps
  }

  it should "correctly handle the removal of the last element on a level" in:
    val folder1 = createFolder("/a")
    val folder2 = createFolder("/b")
    val folder3 = createFolder("/c")
    val fileA = createFile("/a/testA.txt", level = 1)
    val fileB = createFile("/b/testB.txt", level = 1)
    val fileC1 = createFile("/c/testC1.txt", level = 1)
    val fileC2 = createFile("/c/testC2.txt", level = 1)
    val folderASub = createFolder("/a/testSubA", 1)
    val fileASub1 = createFile("/a/testSubA/sub1.txt", level = 2)
    val fileASub2 = createFile("/a/testSubA/sub2.txt", level = 2)
    val sourceOrg = Source(List(folder1, folder2, folder3, fileA, folderASub, fileB, fileC1, fileASub1, fileASub2))
    val sourceTarget = Source(
      destinationElems(
        List(folder1, folder2, folder3, fileA, folderASub, fileB, fileC1, fileC2, fileASub1, fileASub2)
      )
    )
    val expOps = List(
      createOp(folder1, ActionNoop),
      createOp(folder2, ActionNoop),
      createOp(folder3, ActionNoop),
      createOp(fileA, ActionNoop, level = 1),
      createOp(folderASub, ActionNoop, level = 1),
      createOp(fileB, ActionNoop, level = 1),
      createOp(fileC1, ActionNoop, level = 1),
      createOp(destinationElem(fileC2), ActionRemove, level = 1, optDstID = destinationID(fileC2)),
      createOp(fileASub1, ActionNoop, level = 2),
      createOp(fileASub2, ActionNoop, level = 2)
    )

    runStage(sourceOrg, sourceTarget) should contain theSameElementsAs expOps
