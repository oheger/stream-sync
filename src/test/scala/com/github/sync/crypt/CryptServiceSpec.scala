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

package com.github.sync.crypt

import java.time.Instant
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.{ActionCreate, ActionOverride, ActionRemove, FsElement, FsFile, FsFolder, IterateResult, SyncFolderData, SyncOperation}
import com.github.sync.crypt.CryptService.IterateSourceFunc
import com.github.sync.util.{LRUCache, UriEncodingHelper}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.Future

object CryptServiceSpec {
  /** A key used for crypt operations. */
  private val SecretKey = CryptStage.keyFromString("A_Secr3t.Key!")

  /** Name of a test file. */
  private val TestFileName = "test.txt"

  /**
    * Creates a test file based on the given parameters.
    *
    * @param prefix the prefix of the file's URI
    * @param index  the index of the test file
    * @return the resulting file
    */
  private def createTestFile(prefix: String, index: Int): FsFile =
    FsFile(UriEncodingHelper.withTrailingSeparator(prefix) + "test" + index + ".tst", 3, Instant.now(),
      20190407 + index)

  /**
    * Creates a test folder based on the given parameters.
    *
    * @param prefix the prefix for the folder's URI
    * @param index  the index of the test folder
    * @return the resulting folder
    */
  private def createTestFolder(prefix: String, index: Int): FsFolder =
    FsFolder(UriEncodingHelper.withTrailingSeparator(prefix) + "subFolder" + index, 3)

  /**
    * Creates a test folder data object based on the given parameters.
    *
    * @param prefix the prefix of the folder's URI
    * @param index  the index of the test folder
    * @return the resulting folder data
    */
  private def createTestFolderData(prefix: String, index: Int): SyncFolderData[Int] =
    SyncFolderData(createTestFolder(prefix, index), index)

  /**
    * Generates a list with test files in the given directory.
    *
    * @param directory the directory
    * @param from      the start index (including)
    * @param to        the end index (including)
    * @return the list with test files
    */
  private def createTestFiles(directory: String, from: Int, to: Int): List[FsFile] =
    (from to to).map(i => createTestFile(directory, i)).toList

  /**
    * Generates a list with test folder data objects in the given directory.
    *
    * @param directory the directory
    * @param from      the start index (including)
    * @param to        the end index (including)
    * @return the list with folder data objects
    */
  private def createTestFolders(directory: String, from: Int, to: Int): List[SyncFolderData[Int]] =
    (from to to).map(i => createTestFolderData(directory, i)).toList

  /**
    * Transforms the given files to have the original URI set from the files of
    * the encrypted result object.
    *
    * @param files  the list with files
    * @param result the result object
    * @return the list of files with their original URI set
    */
  private def initOriginalFileNames(files: List[FsFile], result: IterateResult[Int]): List[FsFile] =
    files.zip(result.files).map(t => t._1.copy(optOriginalUri = Some(t._2.relativeUri)))

  /**
    * Transforms the given folders to have the original URI set from the
    * folders of the encrypted result object.
    *
    * @param folders the list with folders
    * @param result  the result object
    * @return the list of folders with their original URI set
    */
  private def initOriginalFolderNames(folders: List[SyncFolderData[Int]], result: IterateResult[Int]):
  List[SyncFolderData[Int]] =
    folders.zip(result.folders) map { t =>
      t._1.copy(folder = t._1.folder.copy(optOriginalUri = Some(t._2.folder.relativeUri)))
    }
}

/**
  * Test class for ''CryptService''.
  */
class CryptServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with BeforeAndAfterAll
  with Matchers with AsyncTestHelper {
  def this() = this(ActorSystem("CryptServiceSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import CryptServiceSpec._
  import system.dispatcher

  /** The object to materialize streams. */
  implicit val mat: ActorMaterializer = ActorMaterializer()

  /**
    * A convenience function to encrypt some text and wait for the result.
    *
    * @param data the text to be encrypted
    * @return the encrypted text
    */
  private def encrypt(data: String): String =
    futureResult(CryptService.encryptName(SecretKey, data))

  /**
    * A convenience function to decrypt some text and wait for the result.
    *
    * @param data the text to be decrypted
    * @return the decrypted text
    */
  private def decrypt(data: String): String =
    futureResult(CryptService.decryptName(SecretKey, data))

  /**
    * Encrypts the given path. The path is split into its components, and then
    * each component is encrypted. The resulting encrypted path is returned.
    *
    * @param path the path to be encrypted
    * @return the encrypted path
    */
  private def encryptPath(path: String): String =
    UriEncodingHelper.mapComponents(path)(encrypt)

  /**
    * Generates an encrypted file URI based on the given parameters. The passed
    * in directory string is assumed to be already encrypted. From the element
    * the name is extracted and encrypted as well. The directory plus the
    * encrypted file name is the resulting URI.
    *
    * @param directory the encrypted directory path
    * @param file      the file
    * @return the encrypted URI of this file
    */
  private def encryptElementUri(directory: String, file: FsElement): String = {
    val (_, name) = UriEncodingHelper.splitParent(file.relativeUri)
    UriEncodingHelper.withTrailingSeparator(directory) + encrypt(name)
  }

  /**
    * Generates a file with an encrypted path based on the given parameters.
    *
    * @param directory the encrypted directory path
    * @param file      the file
    * @return the file with an encrypted URI
    */
  private def encryptedFile(directory: String, file: FsFile): FsFile =
    file.copy(relativeUri = encryptElementUri(directory, file),
      size = EncryptOpHandler.processedSize(file.size))

  /**
    * Generates a folder with an encrypted path based on the given parameters.
    *
    * @param directory the encrypted directory path
    * @param folder    the folder
    * @return the folder with an encrypted URI
    */
  private def encryptedFolder(directory: String, folder: FsFolder): FsFolder =
    folder.copy(relativeUri = encryptElementUri(directory, folder))

  "CryptService" should "return a transformation function that adapts file sizes" in {
    val files = List(FsFile("/f1.txt", 2, Instant.now(), 180106),
      FsFile("/f2.doc", 2, Instant.now(), 180209),
      FsFile("/f3.dat", 2, Instant.now(), 180228))
    val expFiles = files.map { f =>
      f.copy(size = DecryptOpHandler.processedSize(f.size))
    }
    val result = IterateResult(FsFolder("/aFolder", 2), files, List.empty[SyncFolderData[Unit]])

    val transformer = CryptService.cryptTransformer(None, 128)
    val transResult = futureResult(transformer.transform(result, transformer.initialState))._1
    transResult.currentFolder should be(result.currentFolder)
    transResult.folders should be(result.folders)
    transResult.files should be(expFiles)
  }

  it should "encrypt names" in {
    val Name = "A test name"

    val encName = futureResult(CryptService.encryptName(SecretKey, Name))
    encName should not be Name
  }

  it should "use a proper encoding for encrypted names" in {
    val Name = "ThisIsANameThatIsGoingToBeEncrypted.test"

    val encName = futureResult(CryptService.encryptName(SecretKey, Name))
    encName.filterNot { c =>
      c.isLetterOrDigit || c == '-' || c == '_' || c == '='
    } should be("")
  }

  it should "support a round-trip of encrypting and decrypting names" in {
    val Name = "ThisNameWillBeEncryptedAndDecrypted.test"

    val processedName = futureResult(CryptService.encryptName(SecretKey, Name)
      .flatMap(n => CryptService.decryptName(SecretKey, n)))
    processedName should be(Name)
  }

  /**
    * Creates an iteration result object with encrypted URIs based on the
    * given parameters.
    *
    * @param directory  the current directory URI
    * @param files      the files of the result
    * @param folderData the folder data objects of the result
    * @return the result object
    */
  private def createEncryptedIterateResult(directory: String, files: List[FsFile],
                                           folderData: List[SyncFolderData[Int]]): IterateResult[Int] = {
    val encryptedDir = encryptPath(directory)
    val encryptedFiles = files.map(encryptedFile(encryptedDir, _))
    val encryptedFolderData = folderData.map(f => f.copy(folder = encryptedFolder(encryptedDir, f.folder)))
    IterateResult(FsFolder(encryptedDir, 2, Some(encryptedDir)), encryptedFiles, encryptedFolderData)
  }

  /**
    * Creates an iteration result object with encrypted URIs based on the given
    * parameters and adapts the files and folders to reference their original
    * (encrypted) URI.
    *
    * @param directory  the current directory URI
    * @param files      the files of the result
    * @param folderData the folder data objects of the result
    * @return a tuple with the result and the adapted lists of elements
    */
  private def createEncryptedResultAndAdaptElements(directory: String, files: List[FsFile],
                                                    folderData: List[SyncFolderData[Int]]):
  (IterateResult[Int], List[FsFile], List[SyncFolderData[Int]]) = {
    val result = createEncryptedIterateResult(directory, files, folderData)
    val newFiles = initOriginalFileNames(files, result)
    val newFolders = initOriginalFolderNames(folderData, result)
    (result, newFiles, newFolders)
  }

  it should "decrypt file names in the transformer function" in {
    val directory = "/some/test/dir"
    val (result, files, folderData) = createEncryptedResultAndAdaptElements(directory,
      createTestFiles(directory, 1, 8), createTestFolders(directory, 1, 4))

    val transformer = CryptService.cryptTransformer(Some(SecretKey), 128)
    val transResult = futureResult(transformer.transform(result, transformer.initialState))._1
    transResult.currentFolder should be(FsFolder(directory, 2, Some(result.currentFolder.relativeUri)))
    transResult.files should be(files)
    transResult.folders should be(folderData)
  }

  it should "use the cache to reduce the number of decrypt operations" in {
    val directory = "/this/is/a/deeply/nested/fancy/directory/uri"
    val (result, files, folderData) = createEncryptedResultAndAdaptElements(directory,
      createTestFiles(directory, 1, 2), createTestFolders(directory, 1, 2))
    val dirParts = UriEncodingHelper splitComponents directory
    val encDirParts = UriEncodingHelper splitComponents result.currentFolder.relativeUri
    val cryptCount = new AtomicInteger
    val transformer = CryptService.cryptTransformer(Some(SecretKey), 128, optCryptCount = Some(cryptCount))

    def checkTransformation(cache: LRUCache[String, String]): Long = {
      cryptCount.set(0)
      val transResult = futureResult(transformer.transform(result, cache))._1
      transResult.currentFolder.relativeUri should be(directory)
      transResult.files should be(files)
      transResult.folders should be(folderData)
      cryptCount.get()
    }

    def pathPrefix(parts: Array[String], length: Int): String =
      UriEncodingHelper.UriSeparator + parts.take(length).mkString(UriEncodingHelper.UriSeparator)

    def populateCache(partCount: Int): LRUCache[String, String] = {
      val path = pathPrefix(dirParts, partCount)
      val cryptPath = pathPrefix(encDirParts, partCount)
      LRUCache(32).put(cryptPath -> path)
    }

    val cnt1 = checkTransformation(transformer.initialState)
    val cnt2 = checkTransformation(populateCache(2))
    val cnt3 = checkTransformation(populateCache(5))
    val cnt4 = checkTransformation(LRUCache(32).put(result.currentFolder.relativeUri -> directory))
    cnt4 should be < cnt3
    cnt3 should be < cnt2
    cnt2 should be < cnt1
  }

  it should "add new folders to the cache" in {
    val directory = "/the/current/folder"
    val (result, _, _) = createEncryptedResultAndAdaptElements(directory,
      createTestFiles(directory, 1, 2), createTestFolders(directory, 1, 4))
    val transformer = CryptService.cryptTransformer(Some(SecretKey), 128)

    val (_, cache) = futureResult(transformer.transform(result, transformer.initialState))
    cache.contains(result.currentFolder.relativeUri) shouldBe true
    result.folders.foreach { f =>
      cache.contains(f.folder.relativeUri) shouldBe true
    }
    cache.size should be(3 + result.folders.size) // components of current dir
  }

  /**
    * Simulates the function to create an iteration source for a specific
    * directory. This function expects requests for a fixed list of folder
    * URIs. For each URI a list with the relative element URIs to be returned
    * is provided. A corresponding source producing these elements is then
    * returned.
    *
    * @param data the data for generating source functions
    * @return the ''IterateSourceFunc'' to be used
    */
  private def sourceFunction(data: List[(String, List[String])]): IterateSourceFunc = {
    val refData = new AtomicReference(data)
    uri => {
      refData.get() match {
        case h :: t if h._1 == uri =>
          refData set t
          val file = FsFile(UriEncodingHelper.withTrailingSeparator(uri) + encrypt(TestFileName), 1,
            Instant.now(), 12345)
          Future.successful(Source(file :: h._2.map(p => FsFolder(p, 2))))

        case _ => fail("Unexpected source request for " + uri)
      }
    }
  }

  /**
    * Obtains the mapping function for sync operations with the given
    * parameters and invokes it.
    *
    * @param srcData the data for the simulated source function
    * @param op      the sync operation
    * @param cache   the cache
    * @return the result from the function invocation
    */
  private def invokeMapOpFunc(srcData: List[(String, List[String])], op: SyncOperation, cache: LRUCache[String, String]):
  (SyncOperation, LRUCache[String, String]) = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val mapFunc = CryptService.mapOperationFunc(SecretKey, sourceFunction(srcData))
    futureResult(mapFunc(op, cache))
  }

  /**
    * Helper function to check the mapping of a sync operation which should not
    * be changed.
    *
    * @param op the operation to check
    */
  private def checkMappedOperationUnchanged(op: SyncOperation): Unit = {
    val cache = LRUCache[String, String](4)

    val (mappedOp, _) = invokeMapOpFunc(Nil, op, cache)
    mappedOp should be theSameInstanceAs op
  }

  it should "pass through delete operations" in {
    val op = SyncOperation(createTestFile("/test", 1), ActionRemove, 1, "/src", "/dst")

    checkMappedOperationUnchanged(op)
  }

  it should "pass through override operations" in {
    val op = SyncOperation(createTestFile("/foo", 2), ActionOverride, 1, "/src/dat.txt", "/dst.txt")

    checkMappedOperationUnchanged(op)
  }

  it should "add a deleted folder to the cache" in {
    val folder = createTestFolder("/data", 1)
    val op = SyncOperation(folder, ActionRemove, 1, "irrelevant", "/originalDestFolder")
    val cache = LRUCache[String, String](8)

    val (_, nextCache) = invokeMapOpFunc(Nil, op, cache)
    nextCache(folder.relativeUri) should be(op.dstUri)
  }

  it should "add the folder of an overridden file to the cache" in {
    val FolderUri = "/the/folder"
    val OrgFolderUri = "/original/uri"
    val file = createTestFile(FolderUri, 2)
    val op = SyncOperation(file, ActionOverride, 1, "irrelevant", OrgFolderUri + "/orgFile.dat")
    val cache = LRUCache[String, String](8)

    val (_, nextCache) = invokeMapOpFunc(Nil, op, cache)
    nextCache(FolderUri) should be(OrgFolderUri)
  }

  it should "adapt the URI of a create operation if the parent is in cache" in {
    val FolderUri = "/plain/folder"
    val OrgFolderUri = "/encrypted/uri"
    val file = createTestFile(FolderUri, 1)
    val op = SyncOperation(file, ActionCreate, 1, "irrelevant", "overridden")
    val cache = LRUCache[String, String](8).put(FolderUri -> OrgFolderUri)

    val (mappedOp, nextCache) = invokeMapOpFunc(Nil, op, cache)
    val (mappedFolder, mappedName) = UriEncodingHelper.splitParent(mappedOp.dstUri)
    mappedFolder should be(OrgFolderUri)
    decrypt(mappedName) should be(UriEncodingHelper.splitParent(file.relativeUri)._2)
    nextCache should be theSameInstanceAs cache
  }

  it should "store the URI of a create folder operation in the cache" in {
    val ParentUri = "/plain/parent"
    val OrgParentUri = "/enc/top"
    val Name = "theFolder"
    val folder = FsFolder(UriEncodingHelper.withTrailingSeparator(ParentUri) + Name, 2)
    val op = SyncOperation(folder, ActionCreate, 0, "irrelevant", "overridden")
    val cache = LRUCache[String, String](8).put(ParentUri -> OrgParentUri)

    val (mappedOp, nextCache) = invokeMapOpFunc(Nil, op, cache)
    val (mappedFolder, mappedName) = UriEncodingHelper.splitParent(mappedOp.dstUri)
    mappedFolder should be(OrgParentUri)
    decrypt(mappedName) should be(Name)
    nextCache(folder.relativeUri) should be(UriEncodingHelper.withTrailingSeparator(OrgParentUri) + mappedName)
  }

  it should "encrypt the name for a create operation on the first level" in {
    val Name = "foo"
    val folder = FsFolder("/" + Name, 1)
    val op = SyncOperation(folder, ActionCreate, 0, "irrelevant", "overridden")

    val (mappedOp, nextCache) = invokeMapOpFunc(Nil, op, LRUCache(4))
    decrypt(UriEncodingHelper.removeLeadingSeparator(mappedOp.dstUri)) should be(Name)
    nextCache(folder.relativeUri) should be(mappedOp.dstUri)
  }

  it should "search the parent URI(s) using the source function" in {
    val Comp1 = "top"
    val Comp2 = "next"
    val Comp3 = "sub"
    val Name = "theActualName"
    val plainComponents = List(Comp1, Comp2, Comp3)
    val cryptComponents = plainComponents map encrypt
    val otherNames = (1 to 3).map(i => "/" + encrypt("wrong" + i))

    def generateSrcData(cryptPrefix: String, lstCrypt: List[String]):
    List[(String, List[String])] = lstCrypt match {
      case h :: t =>
        val encName = cryptPrefix + "/" + h
        val content = List(cryptPrefix + otherNames(0), cryptPrefix + otherNames(1),
          encName, cryptPrefix + otherNames(2))
        val mapping = (cryptPrefix, content)
        mapping :: generateSrcData(encName, t)
      case _ => Nil
    }

    val srcData = generateSrcData("", cryptComponents)
    val folder = FsFolder(UriEncodingHelper.fromComponents(plainComponents) + "/" + Name, 2)
    val op = SyncOperation(folder, ActionCreate, 0, "irrelevant", "overridden")

    val (mappedOp, nextCache) = invokeMapOpFunc(srcData, op, LRUCache(4))
    val (mappedFolder, mappedName) = UriEncodingHelper.splitParent(mappedOp.dstUri)
    decrypt(mappedName) should be(Name)
    mappedFolder should be(UriEncodingHelper.fromComponents(cryptComponents))
    (1 to plainComponents.size) foreach { i =>
      val cryptUri = UriEncodingHelper.fromComponents(cryptComponents take i)
      val plainUri = UriEncodingHelper.fromComponents(plainComponents take i)
      nextCache(plainUri) should be(cryptUri)
    }
    nextCache(folder.relativeUri) should be(mappedOp.dstUri)
  }

  it should "filter out files from the source function" in {
    val folder = FsFolder(s"/$TestFileName/someUri", 2)
    val op = SyncOperation(folder, ActionCreate, 0, "irrelevant", "irrelevant")
    val cache = LRUCache[String, String](4)
    val srcData = List(("", List("/" + encrypt("foo"))))

    intercept[NoSuchElementException] {
      invokeMapOpFunc(srcData, op, cache)
    }
  }

  it should "stop the iteration of the source function when leaving the current folder" in {
    val Name = "/parent"
    val encName = encryptPath(Name)
    val folder = FsFolder(Name + "/someUri", 2)
    val op = SyncOperation(folder, ActionCreate, 0, "irrelevant", "irrelevant")
    val cache = LRUCache[String, String](4)
    val srcData = List(("", List("/" + encrypt("wrong"), encryptPath(Name + "/sub/other"), encName)))

    intercept[NoSuchElementException] {
      invokeMapOpFunc(srcData, op, cache)
    }
  }

  it should "stop the iteration of the source function when the prefix changes" in {
    val Root = "/parent_folder"
    val encRoot = encryptPath(Root)
    val Parent = "/sub"
    val encParent = encRoot + encryptPath(Parent)
    val folder = FsFolder(Root + Parent + "/someUri", 2)
    val op = SyncOperation(folder, ActionCreate, 0, "irrelevant", "irrelevant")
    val cache = LRUCache[String, String](4).put(Root -> encRoot)
    val srcData = List((encRoot, List(encRoot + "/" + encrypt("other"),
      encryptPath("/another/path"), encParent)))

    intercept[NoSuchElementException] {
      invokeMapOpFunc(srcData, op, cache)
    }
  }
}
