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

import java.security.Key
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.github.sync.SyncTypes.{FsFile, IterateResult, ResultTransformer}
import com.github.sync.util.{LRUCache, UriEncodingHelper}

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

/**
  * A module offering functionality related to encryption and decryption.
  *
  * The functions provided by this object are used to implement support for
  * cryptographic operations in the regular sync process.
  */
object CryptService {
  /**
    * Returns a ''ResultTransformer'' that supports the iteration over an
    * encrypted folder structure.
    *
    * @param optNameDecryptKey an ''Option'' with the key for file name
    *                          encryption; if set, names are decrypted
    * @param optCryptCount     an optional counter for crypt operations; mainly
    *                          used for testing purposes
    * @param ec                the execution context
    * @param mat               the object to materialize streams
    * @return the transformer for encrypted folder structures
    */
  def cryptTransformer(optNameDecryptKey: Option[Key], optCryptCount: Option[AtomicInteger] = None)
                      (implicit ec: ExecutionContext, mat: ActorMaterializer):
  ResultTransformer[LRUCache[String, String]] =
    new ResultTransformer[LRUCache[String, String]] {
      override def initialState: LRUCache[String, String] = LRUCache(1024)

      override def transform[F](result: IterateResult[F], cache: LRUCache[String, String]):
      Future[(IterateResult[F], LRUCache[String, String])] = optNameDecryptKey match {
        case None =>
          Future.successful((transformEncryptedFileSizes(result), cache))
        case Some(key) =>
          implicit val cryptCount: AtomicInteger = optCryptCount getOrElse new AtomicInteger
          createDecryptedResult(key, result, cache)
      }
    }

  /**
    * Adapts the sizes of encrypted files in the given result object. This is
    * necessary because the files store some extra information.
    *
    * @param result the result to be transformed
    * @tparam F the type of the folders in the result
    * @return the transformed result with file sizes adapted
    */
  def transformEncryptedFileSizes[F](result: IterateResult[F]): IterateResult[F] =
    result.copy(files = result.files map transformEncryptedFileSize)

  /**
    * Encrypts a name (e.g. a component of a path) using the given key. The
    * operation is done in background. The encrypted result is returned as a
    * base64-encoded string.
    *
    * @param key  the key to be used for encrypting
    * @param name the name to be encrypted
    * @param ec   the execution context
    * @param mat  the object to materialize streams
    * @return a future with the encrypted result (in base64)
    */
  def encryptName(key: Key, name: String)(implicit ec: ExecutionContext, mat: ActorMaterializer): Future[String] =
    cryptName(key, name, EncryptOpHandler)(ByteString(_))(buf => Base64.getUrlEncoder.encodeToString(buf.toArray))

  /**
    * Decrypts a name using the given key. This function performs the reverse
    * transformation of ''encryptName()''. It assumes that the passed in name
    * is base64-encoded. It is decoded and then decrypted using the provided
    * key. The result is converted to a string.
    *
    * @param key  the key to be used for decrypting
    * @param name the name to be decrypted (in base64-encoding)
    * @param ec   the execution context
    * @param mat  the object to materialize streams
    * @return a future with the decrypted name
    */
  def decryptName(key: Key, name: String)(implicit ec: ExecutionContext, mat: ActorMaterializer): Future[String] =
    cryptName(key, name, DecryptOpHandler)(n => ByteString(Base64.getUrlDecoder.decode(n)))(_.utf8String)

  /**
    * A generic function to encrypt or decrypt a name. It implements the logic
    * to decode the input string to a ''ByteString'', run a stream with a
    * [[CryptStage]], and encode the resulting ''ByteString'' again to a
    * string. The handler for the crypt operations and functions for the
    * decoding and encoding are expected as arguments.
    *
    * @param key       the key for the crypt operation
    * @param name      the name to be processed
    * @param opHandler the handler for the crypt operation
    * @param decode    the function to decode the input
    * @param encode    the function to encode the output
    * @param ec        the execution context
    * @param mat       the object to materialize streams
    * @return a future with the processed string
    */
  private def cryptName(key: Key, name: String, opHandler: CryptOpHandler)(decode: String => ByteString)
                       (encode: ByteString => String)
                       (implicit ec: ExecutionContext, mat: ActorMaterializer): Future[String] = {
    val source = Source.single(decode(name))
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    val stage = new CryptStage(opHandler, key)
    source.via(stage).runWith(sink) map encode
  }

  /**
    * Transforms the given file to have the decrypted file size.
    *
    * @param f the file
    * @return the transformed file
    */
  private def transformEncryptedFileSize(f: FsFile): FsFile =
    f.copy(size = calculateEncryptedFileSize(f))

  /**
    * Calculates the size of an encrypted file.
    *
    * @param f the file object
    * @return the actual size of this file
    */
  private def calculateEncryptedFileSize(f: FsFile): Long = DecryptOpHandler.processedSize(f.size)

  /**
    * Transforms the given result object to contain only decrypted paths. The
    * cache is used to avoid unnecessary decrypt operations and updated with
    * the new paths encountered.
    *
    * @param key    the key for decryption
    * @param result the result object to be transformed
    * @param cache  the cache
    * @param ec     the execution context
    * @param mat    the object to materialize streams
    * @param cnt    a counter for crypt operations
    * @tparam F the type of folder data
    * @return a future with the transformed result and the updated cache
    */
  private def createDecryptedResult[F](key: Key, result: IterateResult[F], cache: LRUCache[String, String])
                                      (implicit ec: ExecutionContext, mat: ActorMaterializer,
                                       cnt: AtomicInteger):
  Future[(IterateResult[F], LRUCache[String, String])] = {
    val futDecryptedFolderUri = decryptCurrentDirectory(key, result, cache)
    for {(decryptedFolderUri, cache2) <- futDecryptedFolderUri
         (files, folders) <- decryptResultElements(key, result,
           UriEncodingHelper.withTrailingSeparator(decryptedFolderUri))
    } yield {
      val decryptedResult = createDecryptedResult(result, decryptedFolderUri, files, folders)
      val updatedCache = updateCacheWithResultFolders(decryptedResult, cache2)
      (decryptedResult, updatedCache)
    }
  }

  /**
    * Decrypts the current directory path in the given result object. The cache
    * is used to decrypt as few path components as possible; it is updated with
    * new parts as necessary.
    *
    * @param key    the key for decryption
    * @param result the result object to be transformed
    * @param cache  the cache
    * @param ec     the execution context
    * @param mat    the object to materialize streams
    * @param cnt    a counter for crypt operations
    * @tparam F the type of folder data
    * @return a future with the decrypted directory path and the updated cache
    */
  private def decryptCurrentDirectory[F](key: Key, result: IterateResult[F], cache: LRUCache[String, String])
                                        (implicit ec: ExecutionContext, mat: ActorMaterializer,
                                         cnt: AtomicInteger):
  Future[(String, LRUCache[String, String])] = {
    val (prefixCrypt, components) = splitUnknownPathComponents(result.currentFolder.relativeUri, cache)
    cnt.addAndGet(components.size)
    Future.sequence(components.map(decryptName(key, _)))
      .map { decryptComponents =>
        val prefix = cache get prefixCrypt getOrElse ""
        val cacheNext = updateCacheForDirectoryPath(prefix, prefixCrypt, decryptComponents.zip(components), cache)
        (cacheNext(result.currentFolder.relativeUri), cacheNext)
      }
  }

  /**
    * Checks which parts of the given directory path is already contained in
    * the cache. We will later only decrypt the unknown parts.
    *
    * @param dir   the directory to check
    * @param cache the cache
    * @return a tuple with the known prefix and a list with unknown components
    */
  private def splitUnknownPathComponents(dir: String, cache: LRUCache[String, String]): (String, List[String]) = {
    @tailrec def checkComponent(path: String, results: List[String]): (String, List[String]) = {
      if (path.length <= 0 || cache.contains(path)) (path, results)
      else {
        val (parent, name) = UriEncodingHelper.splitParent(path)
        checkComponent(parent, name :: results)
      }
    }

    checkComponent(dir, Nil)
  }

  /**
    * Adds information about the given path components to the cache.
    *
    * @param prefix      the prefix of the path (decrypted)
    * @param prefixCrypt the prefix of the path (encrypted)
    * @param components  a list with tuples of decrypted and encrypted names
    * @param cache       the cache
    * @return the updated cache
    */
  private def updateCacheForDirectoryPath(prefix: String, prefixCrypt: String, components: List[(String, String)],
                                          cache: LRUCache[String, String]): LRUCache[String, String] =
    components.foldLeft((prefix, prefixCrypt, cache)) { (t, e) =>
      val path = t._1 + UriEncodingHelper.UriSeparator + e._1
      val pathCrypt = t._2 + UriEncodingHelper.UriSeparator + e._2
      (path, pathCrypt, t._3.put(pathCrypt -> path))
    }._3

  /**
    * Decrypts a sequence of URIs starting with a common prefix. On each URI
    * the prefix is removed, the resulting name is processed, and then the
    * other prefix is added.
    *
    * @param key             the key for the crypt operation
    * @param prefixLen       the length of the prefix to be removed
    * @param decryptedPrefix the prefix of the processed URI
    * @param uris            the URIs to be processed
    * @param ec              the execution context
    * @param mat             the object to materialize streams
    * @param cnt             a counter for crypt operations
    * @return a future with the resulting URIs
    */
  private def decryptPaths(key: Key, prefixLen: Int, decryptedPrefix: String, uris: List[String])
                          (implicit ec: ExecutionContext, mat: ActorMaterializer, cnt: AtomicInteger):
  Future[List[String]] = {
    val processedUris = uris.map(_.substring(prefixLen))
      .map(decryptName(key, _).map(decryptedPrefix + _))
    cnt.addAndGet(uris.size)
    Future.sequence(processedUris)
  }

  /**
    * Decrypts the names of the files and folders in an iteration result based
    * on the given parameters.
    *
    * @param key             the key to decrypt the names
    * @param iterateResult   the result to be processed
    * @param decryptedFolder the URI of the decrypted current folder
    * @param ec              the execution context
    * @param mat             the object to materialize streams
    * @param cnt             a counter for crypt operations
    * @tparam F the type of folder results
    * @return a tuple with the decrypted file and folder names
    */
  private def decryptResultElements[F](key: Key, iterateResult: IterateResult[F], decryptedFolder: String)
                                      (implicit ec: ExecutionContext, mat: ActorMaterializer, cnt: AtomicInteger):
  Future[(List[String], List[String])] = {
    val prefixLength = UriEncodingHelper.withTrailingSeparator(iterateResult.currentFolder.relativeUri).length
    val futFiles = decryptPaths(key, prefixLength, decryptedFolder, iterateResult.files.map(_.relativeUri))
    val futFolders = decryptPaths(key, prefixLength, decryptedFolder, iterateResult.folders.map(_.folder.relativeUri))
    for {files <- futFiles
         folders <- futFolders
    } yield (files, folders)
  }

  /**
    * Constructs a new iteration result from the given decrypted paths.
    *
    * @param source          the original result
    * @param decryptedFolder the URI of the decrypted current folder
    * @param fileNames       the decrypted file names
    * @param folderNames     the decrypted folder names
    * @tparam F the type of folder results
    * @return the new iteration result
    */
  private def createDecryptedResult[F](source: IterateResult[F], decryptedFolder: String,
                                       fileNames: List[String], folderNames: List[String]): IterateResult[F] = {
    val currentFolder = source.currentFolder.copy(relativeUri =
      UriEncodingHelper.removeTrailingSeparator(decryptedFolder))
    val files = source.files.zip(fileNames).map { t =>
      t._1.copy(relativeUri = t._2, optOriginalUri = Some(t._1.relativeUri),
        size = calculateEncryptedFileSize(t._1))
    }
    val folders = source.folders.zip(folderNames).map { t =>
      t._1.copy(folder = t._1.folder.copy(relativeUri = t._2, optOriginalUri = Some(t._1.folder.relativeUri)))
    }
    IterateResult(currentFolder, files, folders)
  }

  /**
    * Updates the given cache by adding the information about the folders
    * contained in the result object. (This information is relevant because it
    * is needed when processing the next level of the iteration.)
    *
    * @param result the result object
    * @param cache  the cache to be updated
    * @tparam F the type of additional data in folders
    * @return the updated cache
    */
  private def updateCacheWithResultFolders[F](result: IterateResult[F], cache: LRUCache[String, String]):
  LRUCache[String, String] = {
    val pathMappings = result.folders.map(f => (f.folder.originalUri, f.folder.relativeUri))
    cache.put(pathMappings: _*)
  }
}
