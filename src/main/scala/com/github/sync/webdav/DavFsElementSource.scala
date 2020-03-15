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

package com.github.sync.webdav

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalQuery

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream._
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.sync.SyncTypes._
import com.github.sync.http.HttpFsElementSource.{ElemData, HttpFolder, HttpIterationState, ParsedFolderData}
import com.github.sync.http.{HttpFsElementSource, HttpRequestActor}
import com.github.sync.util.UriEncodingHelper

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.xml._

/**
  * A module providing a stream source for listing the content of a folder
  * structure located on a WebDav server.
  *
  * This source sends ''PROPFIND'' requests to the server specified by the
  * given configuration. From the responses information about the files and
  * sub folders is extracted, so that the whole structure can be iterated over.
  *
  * Results ([[FsFolder]] and [[FsFile]] elements) are already returned in the
  * correct order; so no sorting is needed as post-processing step.
  */
object DavFsElementSource extends HttpFsElementSource[DavConfig] {
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
    * @param config         the configuration
    * @param sourceFactory  the factory for the element source
    * @param requestActor   the actor for sending HTTP requests
    * @param startFolderUri URI of a folder to start the iteration
    * @param system         the actor system
    * @param mat            the object to materialize streams
    * @return the new source
    */
  def apply(config: DavConfig, sourceFactory: ElementSourceFactory, requestActor: ActorRef,
            startFolderUri: String = "")
           (implicit system: ActorSystem, mat: ActorMaterializer):
  Source[FsElement, NotUsed] = createSource(config, sourceFactory, requestActor, startFolderUri)

  /**
    * Parses the response received for a folder request. The data of the
    * entity is read, converted to XML, and processed to extract the
    * elements contained in this folder.
    *
    * @param state  the current state
    * @param folder the folder whose content is to be computed
    * @param result the result of the request for this folder
    * @param ec     the execution context
    * @param mat    the object to materialize streams
    * @return a ''Future'' with the result of the parse operation
    */
  override protected def parseFolderResponse(state: HttpIterationState[DavConfig], folder: FsFolder)
                                            (result: HttpRequestActor.Result)
                                            (implicit ec: ExecutionContext, mat: ActorMaterializer):
  Future[ParsedFolderData] =
    readResponse(result.response)
      .map(toXml)
      .map(elem => extractFolderElements(state, elem, folder.level + 1))
      .map(elements => ParsedFolderData(elements, None))

  /**
    * Parses the given string to an XML document.
    *
    * @param data the string
    * @return the parsed XML document
    */
  private def toXml(data: ByteString): Elem = {
    val stream = new ByteArrayInputStream(data.toArray)
    XML.load(stream)
  }

  /**
    * Extracts a sequence of ''FsElement'' objects from the XML result
    * received for a specific folder.
    *
    * @param state the current state
    * @param elem  the XML with the content of the folder
    * @param level the level of the elements to extract
    * @return a list with elements that have been extracted
    */
  private def extractFolderElements(state: HttpIterationState[DavConfig], elem: Elem, level: Int): List[ElemData] =
    (elem \ ElemResponse).drop(1) // first element is the folder itself
      .map(node => extractFolderElement(state, node, level)).toList

  /**
    * Extracts an ''FsElement'' from the specified XML node.
    *
    * @param state the current state
    * @param node  the node representing data of an element
    * @param level the level for this element
    * @return the element that was extracted
    */
  private def extractFolderElement(state: HttpIterationState[DavConfig], node: Node, level: Int): ElemData = {
    val ref = UriEncodingHelper.removeTrailingSeparator(elemText(node, ElemHref))
    val uri = extractElementUri(state, ref)
    val propNode = node \ ElemPropStat \ ElemProp
    val isFolder = isCollection(propNode)
    if (isFolder) ElemData(ref, FsFolder(uri, level))
    else {
      val modifiedTime = obtainModifiedTime(propNode, state.config)
      val fileSize = elemText(propNode, ElemContentLength).toLong
      ElemData(ref, FsFile(uri, level, modifiedTime, fileSize))
    }
  }

  /**
    * Creates a request for the specified folder.
    *
    * @param state      the current state
    * @param folderData the data of the folder to be loaded
    * @return the request to query the content of this folder
    */
  override protected def createFolderRequest(state: HttpIterationState[DavConfig],
                                             folderData: SyncFolderData[HttpFolder]):
  HttpRequestActor.SendRequest = {
    val httpRequest = HttpRequest(method = MethodPropFind, uri = folderData.data.normalizedRef,
      headers = List(HeaderAccept, HeaderDepth))
    HttpRequestActor.SendRequest(httpRequest, null)
  }

  /**
    * Obtains the URI of an element based on the reference URI from the server.
    *
    * @param state the current state
    * @param ref   the reference URI of the element
    * @return the extracted element URI
    */
  private def extractElementUri(state: HttpIterationState[DavConfig], ref: String): String =
    UriEncodingHelper.decode(ref) drop state.decodedRootUriPrefixLen

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
    @tailrec def obtainModifiedTimeFromProperty(properties: List[String]): Instant =
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
