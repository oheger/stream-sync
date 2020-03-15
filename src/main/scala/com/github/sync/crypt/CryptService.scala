/*
 * Copyright 2018-2020 The Developers Team.
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
import com.github.sync.SyncTypes._
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
    * Definition of a function that creates an element iteration source for a
    * given start folder URI.
    *
    * This function is used when mapping sync operations before they are
    * executed. It is then sometimes necessary to iterate over the destination
    * structure to find specific elements.
    */
  type IterateSourceFunc = String => Future[Source[FsElement, Any]]

  /**
    * Returns a ''ResultTransformer'' that supports the iteration over an
    * encrypted folder structure.
    *
    * @param optNameDecryptKey an ''Option'' with the key for file name
    *                          encryption; if set, names are decrypted
    * @param cryptCacheSize    the size of the cache for encrypted names
    * @param optCryptCount     an optional counter for crypt operations; mainly
    *                          used for testing purposes
    * @param ec                the execution context
    * @param mat               the object to materialize streams
    * @return the transformer for encrypted folder structures
    */
  def cryptTransformer(optNameDecryptKey: Option[Key], cryptCacheSize: Int,
                       optCryptCount: Option[AtomicInteger] = None)
                      (implicit ec: ExecutionContext, mat: ActorMaterializer):
  ResultTransformer[LRUCache[String, String]] =
    new ResultTransformer[LRUCache[String, String]] {
      override def initialState: LRUCache[String, String] = LRUCache(cryptCacheSize)

      override def transform[F](result: IterateResult[F], cache: LRUCache[String, String]):
      Future[(IterateResult[F], LRUCache[String, String])] = optNameDecryptKey match {
        case None =>
          Future.successful((transformEncryptedFileSizes(result), cache))
        case Some(key) =>
          implicit val cryptCount: AtomicInteger = optCryptCount getOrElse new AtomicInteger
          processResult(key, result, cache)
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
    * Returns a function to map sync operations before they are executed. When
    * file names are encrypted in the destination structure, there needs to be
    * some pre-processing on sync operations, especially when new elements are
    * created. In this case, an encryption of file names has to be performed,
    * but it must be guaranteed that encrypted paths match already existing
    * paths in the folder structure. (The encryption algorithm produces
    * different output for each crypt operation, even if the input is the same;
    * therefore, paths cannot simply be encrypted again, but the results of
    * earlier operations must be reused.) This function handles this
    * requirement. If necessary, it browses the existing destination structure
    * to determine the correct paths for elements to be newly created. As this
    * is expensive, a cache is used to store known paths.
    *
    * @param key     the key for crypt operations
    * @param srcFunc a function to obtain a source for a given relative folder
    * @param ec      the execution context
    * @param mat     the object to materialize streams
    * @return the mapping function for sync operations
    */
  def mapOperationFunc(key: Key, srcFunc: IterateSourceFunc)(implicit ec: ExecutionContext, mat: ActorMaterializer):
  (SyncOperation, LRUCache[String, String]) => Future[(SyncOperation, LRUCache[String, String])] =
    (op, cache) => {
      op match {
        case SyncOperation(FsFile(uri, _, _, _, _), ActionCreate, _, _, _) =>
          mapCreateOperation(key, srcFunc, op, cache, uri, updateCache = false)

        case SyncOperation(FsFolder(uri, _, _), ActionCreate, _, _, _) =>
          mapCreateOperation(key, srcFunc, op, cache, uri, updateCache = true)

        case SyncOperation(FsFolder(uri, _, _), _, _, _, _) =>
          Future.successful((op, cache.put(uri -> op.dstUri)))

        case SyncOperation(FsFile(uri, _, _, _, _), _, _, _, _) =>
          Future.successful((op,
            cache.put(UriEncodingHelper.splitParent(uri)._1 -> UriEncodingHelper.splitParent(op.dstUri)._1)))
      }
    }

  /**
    * Implements the mapping of create operations. This is the complex part of
    * the work that needs to be done by the operation mapping function.
    *
    * @param key         the key for crypt operations
    * @param srcFunc     a function to obtain a source for a given relative folder
    * @param op          the operation to be mapped
    * @param cache       the current cache
    * @param uri         the URI of the current element
    * @param updateCache a flag whether the cache should be updated for the
    *                    current element (only true for folders)
    * @param ec          the execution context
    * @param mat         the object to materialize streams
    * @return
    */
  private def mapCreateOperation(key: Key, srcFunc: IterateSourceFunc, op: SyncOperation,
                                 cache: LRUCache[String, String], uri: String, updateCache: Boolean)
                                (implicit ec: ExecutionContext, mat: ActorMaterializer):
  Future[(SyncOperation, LRUCache[String, String])] = {
    val (parent, name) = UriEncodingHelper.splitParent(uri)
    val futEncName = encryptName(key, name)
    val (prefix, components) = splitUnknownPathComponents(parent, cache)
    val encPrefix = fromCache(prefix, cache)
    val futEncPath = resolveEncryptedPath(encPrefix, prefix, components, key, srcFunc, cache)
    for {encName <- futEncName
         (encPath, updCache) <- futEncPath
    } yield {
      val destUri = encPath + UriEncodingHelper.UriSeparator + encName
      val nextCache = if (updateCache) updCache.put(uri -> destUri) else updCache
      (op.copy(dstUri = destUri), nextCache)
    }
  }

  /**
    * Resolves a plain text path against an encrypted folder structure.
    * Starting from the known prefix, all components are searched for using the
    * given iteration function. The cache is updated for each component. Result
    * is a future with the full resolved path and the updated cache.
    *
    * @param encPrefix   the known encrypted prefix
    * @param plainPrefix the corresponding plain prefix
    * @param components  a sequence with unknown plain components
    * @param key         the key for crypt operations
    * @param srcFunc     a function to obtain a source for a given relative folder
    * @param cache       the current cache
    * @param ec          the execution context
    * @param mat         the object to materialize streams
    * @return a future with the resolved path and the updated cache
    */
  private def resolveEncryptedPath(encPrefix: String, plainPrefix: String, components: Seq[String], key: Key,
                                   srcFunc: IterateSourceFunc, cache: LRUCache[String, String])
                                  (implicit ec: ExecutionContext, mat: ActorMaterializer):
  Future[(String, LRUCache[String, String])] = {
    val init = Future.successful((encPrefix, plainPrefix, cache))
    components.foldLeft(init) { (fut, comp) =>
      fut.flatMap { t =>
        val plainPath = t._2 + UriEncodingHelper.UriSeparator + comp
        val futName = searchEncryptedName(t._1, comp, key, srcFunc)
        futName.map { name =>
          val cryptPath = t._1 + UriEncodingHelper.UriSeparator + name
          (cryptPath, plainPath, t._3.put(plainPath -> cryptPath))
        }
      }
    }
  } map (t => (t._1, t._3))

  /**
    * Searches for the element name in a a given directory that corresponds to
    * the given plain text name.
    *
    * @param parent  the parent URI, the directory to be searched
    * @param name    the plain text name to search for
    * @param key     the key for crypt operations
    * @param srcFunc a function to obtain a source for a given relative folder
    * @param ec      the execution context
    * @param mat     the object to materialize streams
    * @return a future with the resolved encrypted name
    */
  private def searchEncryptedName(parent: String, name: String, key: Key, srcFunc: IterateSourceFunc)
                                 (implicit ec: ExecutionContext, mat: ActorMaterializer): Future[String] = {
    val sink = Sink.last[String]
    srcFunc(parent) flatMap { source =>
      source
        .filter(_.isInstanceOf[FsFolder])
        .map(elem => UriEncodingHelper.splitParent(elem.relativeUri))
        .takeWhile(_._1 == parent)
        .map(_._2)
        .mapAsync(1)(name => decryptName(key, name).map((name, _)))
        .filter(_._2 == name)
        .map(_._1)
        .runWith(sink)
    }
  }

  /**
    * Helper method for reading a key from a cache. The cache is accessed only
    * if the key is not empty.
    *
    * @param key   the key
    * @param cache the cache
    * @return the mapped key from the cache
    */
  private def fromCache(key: String, cache: LRUCache[String, String]): String =
    if (key.length > 0) cache(key) else key

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
  private def processResult[F](key: Key, result: IterateResult[F], cache: LRUCache[String, String])
                              (implicit ec: ExecutionContext, mat: ActorMaterializer, cnt: AtomicInteger):
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
    val (prefixCrypt, components) = splitUnknownPathComponents(result.currentFolder.originalUri, cache)
    cnt.addAndGet(components.size)
    Future.sequence(components.map(decryptName(key, _)))
      .map { decryptComponents =>
        val prefix = cache get prefixCrypt getOrElse ""
        val cacheNext = updateCacheForDirectoryPath(prefix, prefixCrypt, decryptComponents.zip(components), cache)
        (fromCache(result.currentFolder.originalUri, cacheNext), cacheNext)
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
    val prefixLength = UriEncodingHelper.withTrailingSeparator(iterateResult.currentFolder.originalUri).length
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
      t._1.copy(relativeUri = t._2, optOriginalUri = Some(originalElemUri(currentFolder, t._1)),
        size = calculateEncryptedFileSize(t._1))
    }
    val folders = source.folders.zip(folderNames).map { t =>
      t._1.copy(folder = t._1.folder.copy(relativeUri = t._2,
        optOriginalUri = Some(originalElemUri(currentFolder, t._1.folder))))
    }
    IterateResult(currentFolder, files, folders)
  }

  /**
    * Generates the original URI of an element based on the current folder.
    *
    * @param currentFolder the folder the element belongs to
    * @param elem          the element in question
    * @return the original URI for this element
    */
  private def originalElemUri(currentFolder: FsFolder, elem: FsElement): String =
    currentFolder.originalUri + UriEncodingHelper.UriSeparator + UriEncodingHelper.splitParent(elem.relativeUri)._2

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
