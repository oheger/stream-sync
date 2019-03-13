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

package com.github.sync.webdav

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalQuery

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler, StageLogging}
import akka.util.ByteString
import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder}
import com.github.sync.util.SyncFolderQueue._
import com.github.sync.util.{SyncFolderData, SyncFolderQueue, UriEncodingHelper}

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.xml._

object DavFsElementSource {
  /** Constant for the slash terminating the URI to a folder. */
  private val Slash = "/"

  /**
    * Data class storing information about a folder that is to be fetched from
    * the WebDav server.
    *
    * @param ref    the URI to reference the folder on the server
    * @param folder the folder element
    */
  case class FolderData(ref: String, override val folder: FsFolder) extends SyncFolderData {
    /**
      * The normalized URI to reference the folder on the server. This URI
      * always ends on a slash which is required by some Dav servers.
      */
    val normalizedRef: String = if(ref endsWith Slash) ref else ref + Slash
  }

  /**
    * A data class holding information about an element that is processed.
    *
    * @param ref  the reference URI for this element
    * @param elem the element itself
    */
  case class ElemData(ref: String, elem: FsElement)

  /** Media type of the data that is expected from the server. */
  private val MediaXML = MediaRange(MediaType.text("xml"))

  /** The Accept header to be used by all requests. */
  private val HeaderAccept = Accept(MediaXML)

  /** The Depth header to be used by all requests. */
  private val HeaderDepth = DepthHeader("1")

  /** Constant for the custom HTTP method used to query folders. */
  private val MethodPropFind = HttpMethod.custom("PROPFIND")

  /** Name of the XML response element. */
  private val ElemResponse = "response"

  /** Name of the XML href element. */
  private val ElemHref = "href"

  /** Name of the XML propstat element. */
  private val ElemPropStat = "propstat"

  /** Name of the XML prop element. */
  private val ElemProp = "prop"

  /** Name of the XML content length element. */
  private val ElemContentLength = "getcontentlength"

  /** Name of the XML resource type element. */
  private val ElemResourceType = "resourcetype"

  /** Name of the XML is collection element. */
  private val ElemCollection = "collection"

  /**
    * Creates a ''Source'' based on this class using the specified
    * configuration.
    *
    * @param config the configuration
    * @param system the actor system
    * @param mat    the object to materialize streams
    * @return the new source
    */
  def apply(config: DavConfig)(implicit system: ActorSystem, mat: ActorMaterializer):
  Source[FsElement, NotUsed] =
    Source.fromGraph(new DavFsElementSource(config))

  /**
    * Parses a date in string form to a corresponding ''Instant''. If this
    * fails, a ''DateTimeParseException'' is thrown.
    *
    * @param strDate the date as string
    * @return the resulting ''Instant''
    */
  private def parseModifiedTime(strDate: String): Instant = {
    val query: TemporalQuery[Instant] = Instant.from _
    DateTimeFormatter.RFC_1123_DATE_TIME.parse(strDate, query)
  }

  /**
    * Extracts the last-modified time from the XML node representing a file.
    * The method checks all properties listed in the config until a match is
    * found.
    *
    * @param nodeSeq the node representing the file
    * @param config  the config
    * @return the last-modified time of this file
    */
  private def obtainModifiedTime(nodeSeq: NodeSeq, config: DavConfig): Instant = {
    def obtainModifiedTimeFromProperty(properties: List[String]): Instant =
      properties match {
        case p :: t =>
          val strTime = elemText(nodeSeq, p)
          if (strTime.length > 0) parseModifiedTime(strTime)
          else obtainModifiedTimeFromProperty(t)
        case _ => throw new SAXException("Could not obtain last-modified time")
      }

    obtainModifiedTimeFromProperty(config.modifiedProperties)
  }

  /**
    * Removes a trailing slash from the given string. This is useful when
    * dealing with URI paths that need to be concatenated of otherwise
    * manipulated.
    *
    * @param s the string to process
    * @return the string with trailing slashes removed
    */
  private def removeTrailingSlash(s: String): String =
    UriEncodingHelper.removeTrailing(s, UriEncodingHelper.UriSeparator)

  /**
    * Extracts the text of a sub element of the given XML node. Handles line
    * breaks in the element.
    *
    * @param node     the node representing the parent element
    * @param elemName the name of the element to be obtained
    * @return the text of this element
    */
  private def elemText(node: NodeSeq, elemName: String): String =
    removeLF((node \ elemName).text)

  /**
    * Removes new line and special characters from the given string. Also
    * handles the case that indention after a new line will add additional
    * whitespace; this is collapsed to a single space.
    *
    * @param s the string to be processed
    * @return the string with removed line breaks
    */
  private def removeLF(s: String): String =
    trimMultipleSpaces(s.map(c => if (c < ' ') ' ' else c)).trim

  /**
    * Replaces multiple space characters in a sequence in the given string by a
    * single one.
    *
    * @param s the string to be processed
    * @return the processed string
    */
  @tailrec private def trimMultipleSpaces(s: String): String = {
    val pos = s.indexOf("  ")
    if (pos < 0) s
    else {
      val s1 = s.substring(0, pos + 1)
      val s2 = s.substring(pos).dropWhile(_ == ' ')
      trimMultipleSpaces(s1 + s2)
    }
  }

  /**
    * Checks whether an element is a collection. In this case the element
    * represents a folder rather than a single file.
    *
    * @param propNode the top-level node for the current element
    * @return a flag whether this element is a collection
    */
  private def isCollection(propNode: NodeSeq): Boolean = {
    val elemCollection = propNode \ ElemResourceType \ ElemCollection
    elemCollection.nonEmpty
  }
}

/**
  * A stream source for listing the content of a folder structure located on a
  * WebDav server.
  *
  * This source sends ''PROPFIND'' requests to the server specified by the
  * given configuration. From the responses information about the files and
  * sub folders is extracted, so that the whole structure can be iterated over.
  *
  * Results ([[FsFolder]] and [[FsFile]] elements) are already returned in the
  * correct order; so no sorting is needed as post-processing step.
  *
  * Note that error handling is very basic: a request that fails for whatever
  * reason (non-success server response, I/O error, unexpected data in the
  * response, etc.) causes the source to cancel the whole stream. This is
  * desired because when the server has a (temporary) problem a sync process
  * must be stopped; otherwise, it can have unexpected results.
  *
  * @param config the ''DavConfig''
  * @param system the actor system (required for sending HTTP requests)
  * @param mat    the object to materialize (sub) streams
  */
class DavFsElementSource(config: DavConfig)(implicit system: ActorSystem, mat: ActorMaterializer)
  extends GraphStage[SourceShape[FsElement]] {
  val out: Outlet[FsElement] = Outlet("DavFsElementSource")

  import DavFsElementSource._
  import system.dispatcher

  /**
    * The common prefix of all URIs in the structure to be processed. The URIs
    * generated for ''FsElement'' objects must be relative to this URI.
    */
  private val rootUriPrefix = removeTrailingSlash(config.rootUri.path.toString())

  /** The length of the root URI prefix. */
  private val decodedRootUriPrefixLen = calcDecodedRootUriLength()

  /** The queue for sending HTTP requests. */
  private[webdav] val requestQueue = new RequestQueue(config.rootUri)

  /** The authorization header to be used for all requests. */
  private val HeaderAuth = authHeader(config)

  override val shape: SourceShape[FsElement] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      // A set with folders to be processed in BFS order
      var folders = SyncFolderQueue(FolderData(config.rootUri.toString(), FsFolder("", -1)))

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          processNextFolder()
        }
      })

      override def postStop(): Unit = {
        log.info("Shutting down request queue.")
        requestQueue.shutdown()
      }

      /**
        * Tries to parse a folder from the set of pending folders. All elements
        * contained in this folder are emitted. If there are no more pending
        * folders to parse, the stage is completed.
        */
      private def processNextFolder(): Unit = {
        if (folders.isEmpty) complete(out)
        else {
          val callback = getAsyncCallback[Try[List[ElemData]]](processFolderResult)
          val (nextFolder, queue) = folders.dequeue()
          folders = queue
          loadFolder(nextFolder) onComplete callback.invoke
        }
      }

      /**
        * Sends a request for the content of the specified folder and parses
        * the response.
        *
        * @param folderData the data of the folder to be loaded
        * @return a future with the parsed content of the folder
        */
      private def loadFolder(folderData: FolderData): Future[List[ElemData]] = {
        val request = createFolderRequest(folderData)
        log.info("Sending request {}.", request.uri)
        sendAndProcess(requestQueue, request)(parseFolderResponse(folderData.folder)).flatten
      }

      /**
        * Parses the response received for a folder request. The data of the
        * entity is read, converted to XML, and processed to extract the
        * elements contained in this folder.
        *
        * @param folder   the folder whose content is to be computed
        * @param response the response of the request for this folder
        * @return a ''Future'' with the elements that have been extracted
        */
      private def parseFolderResponse(folder: FsFolder)(response: HttpResponse):
      Future[List[ElemData]] =
        readResponse(response).map(elem => extractFolderElements(elem, folder.level + 1))

      /**
        * Reads the entity of the given response of a folder request and
        * parses it as XML document.
        *
        * @param response the response to be read
        * @return a future with the parsed XML result
        */
      private def readResponse(response: HttpResponse): Future[Elem] = {
        val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
        response.entity.dataBytes.runWith(sink).map { body =>
          val stream = new ByteArrayInputStream(body.toArray)
          XML.load(stream)
        }
      }

      /**
        * Extracts a sequence of ''FsElement'' objects from the XML result
        * received for a specific folder.
        *
        * @param elem  the XML with the content of the folder
        * @param level the level of the elements to extract
        * @return a list with elements that have been extracted
        */
      private def extractFolderElements(elem: Elem, level: Int): List[ElemData] =
        (elem \ ElemResponse).drop(1) // first element is the folder itself
          .map(node => extractFolderElement(node, level)).toList

      /**
        * Extracts an ''FsElement'' from the specified XML node.
        *
        * @param node  the node representing data of an element
        * @param level the level for this element
        * @return the element that was extracted
        */
      private def extractFolderElement(node: Node, level: Int): ElemData = {
        val ref = removeTrailingSlash(elemText(node, ElemHref))
        val uri = extractElementUri(ref)
        val propNode = node \ ElemPropStat \ ElemProp
        val isFolder = isCollection(propNode)
        if (isFolder) ElemData(ref, FsFolder(uri, level))
        else {
          val modifiedTime = obtainModifiedTime(propNode, config)
          val fileSize = elemText(propNode, ElemContentLength).toLong
          ElemData(ref, FsFile(uri, level, modifiedTime, fileSize))
        }
      }

      /**
        * Handles a result of parsing the content of a folder. If the folder
        * contains elements, they are emitted downstream, and the set of sub
        * folders pending to be processed is updated. Otherwise, processing
        * continues with the next folder in the set.
        *
        * @param triedElements a list of elements contained in the folder
        */
      private def processFolderResult(triedElements: Try[List[ElemData]]): Unit =
        triedElements match {
          case Success(elements) =>
            if (elements.nonEmpty) {
              val elemsSorted = elements.sortWith(_.elem.relativeUri < _.elem.relativeUri)
              emitMultiple(out, elemsSorted map (_.elem))
              folders = elemsSorted.foldLeft(folders) { (q, e) =>
                e.elem match {
                  case f: FsFolder => q + FolderData(e.ref, f)
                  case _ => q
                }
              }
            } else {
              processNextFolder()
            }

          case Failure(exception) =>
            log.error(exception, "Failed request!")
            fail(out, exception)
        }

      /**
        * Creates a request for the specified folder.
        *
        * @param folderData the data of the folder to be loaded
        * @return the request to query the content of this folder
        */
      private def createFolderRequest(folderData: FolderData): HttpRequest =
        HttpRequest(method = MethodPropFind, uri = folderData.normalizedRef,
          headers = List(HeaderAuth, HeaderAccept, HeaderDepth))
    }

  /**
    * Obtains the URI of an element based on the reference URI from the server.
    *
    * @param ref the reference URI of the element
    * @return the extracted element URI
    */
  private def extractElementUri(ref: String): String =
    UriEncodingHelper.decode(ref) drop decodedRootUriPrefixLen

  /**
    * Determines the length of the decoded root URI. This is needed to
    * correctly generate relative element URIs.
    *
    * @return the length of the decoded root URI
    */
  private def calcDecodedRootUriLength(): Int =
    UriEncodingHelper.decode(rootUriPrefix).length
}

/**
  * Class representing the ''Depth'' header.
  *
  * This header has to be included to WebDav requests. It defines the depth of
  * sub structures to be returned by a ''PROPFIND'' request.
  *
  * @param depth the value of the header
  */
class DepthHeader(depth: String) extends ModeledCustomHeader[DepthHeader] {
  override val companion: ModeledCustomHeaderCompanion[DepthHeader] = DepthHeader

  override def value(): String = depth

  override def renderInRequests(): Boolean = true

  override def renderInResponses(): Boolean = true
}

object DepthHeader extends ModeledCustomHeaderCompanion[DepthHeader] {
  override val name: String = "Depth"

  override def parse(value: String): Try[DepthHeader] =
    Try(new DepthHeader(value))
}
