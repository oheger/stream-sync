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
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder, IterateResult, SyncFolderData}
import com.github.sync.util.{LRUCache, UriEncodingHelper}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object CryptServiceSpec {
  /** A key used for crypt operations. */
  private val SecretKey = CryptStage.keyFromString("A_Secr3t.Key!")

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

    val transformer = CryptService.cryptTransformer(None)
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
    IterateResult(FsFolder(encryptedDir, 2), encryptedFiles, encryptedFolderData)
  }

  /**
    * Creates an iteration result object with encrypted URIs based on the given
    * parameters and adapt the files and folders to reference their original
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

    val transformer = CryptService.cryptTransformer(Some(SecretKey))
    val transResult = futureResult(transformer.transform(result, transformer.initialState))._1
    transResult.currentFolder should be(FsFolder(directory, 2))
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
    val transformer = CryptService.cryptTransformer(Some(SecretKey), optCryptCount = Some(cryptCount))

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
    val transformer = CryptService.cryptTransformer(Some(SecretKey))

    val (_, cache) = futureResult(transformer.transform(result, transformer.initialState))
    cache.contains(result.currentFolder.relativeUri) shouldBe true
    result.folders.foreach { f =>
      cache.contains(f.folder.relativeUri) shouldBe true
    }
    cache.size should be(3 + result.folders.size) // components of current dir
  }
}
