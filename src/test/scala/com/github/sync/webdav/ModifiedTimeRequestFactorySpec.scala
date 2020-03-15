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

import java.io.StringReader
import java.time.Instant

import akka.util.Timeout
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.xml.{Elem, NodeSeq, XML}

object ModifiedTimeRequestFactorySpec {
  /** Name of a custom modified property. */
  private val CustomModifiedProperty = "special-modified"

  /** A custom namespace. */
  private val CustomNamespace = "urn:my-special-namespace-org:"

  /**
    * Creates a ''DavConfig'' with the specified properties. Irrelevant
    * properties are set to default values.
    *
    * @param property  the modified property
    * @param namespace the namespace
    * @return the resulting ''DavConfig''
    */
  private def createConfig(property: String = DavConfig.DefaultModifiedProperty,
                           namespace: Option[String] = None): DavConfig =
    DavConfig(rootUri = "https://test.dav.org",
      optModifiedProperty = Some(property), optModifiedNamespace = namespace,
      deleteBeforeOverride = false,
      Timeout(16.seconds))

  /**
    * Parses the given template string into an XML element.
    *
    * @param template the template string
    * @return the resulting XML element
    */
  private def toElem(template: String): Elem =
    XML.load(new StringReader(template))

  /**
    * Parses the template string as XML document and returns a sequence with
    * the content of the ''prop'' element. This is the parent of the element
    * defining the modified time.
    *
    * @param template the template string
    * @return the content of the ''prop'' element
    */
  private def parseTemplate(template: String): NodeSeq =
    toElem(template) \ "set" \ "prop"
}

/**
  * Test class for ''ModifiedTimeRequestFactory''.
  */
class ModifiedTimeRequestFactorySpec extends FlatSpec with Matchers {

  import ModifiedTimeRequestFactorySpec._

  /**
    * Verifies a template string generated by the factory. The string is
    * parsed as XML, the ''prop'' element is obtained, and the check function
    * provided is invoked.
    *
    * @param template the template string
    * @param check    a function to validate the ''prop'' element
    */
  private def verifyTemplate(template: String)(check: NodeSeq => Boolean): Unit = {
    val nodeSeq = parseTemplate(template)
    check(nodeSeq) shouldBe true
  }

  /**
    * Checks that the given template string declares the given namespace.
    *
    * @param template the template string
    * @param prefix   the namespace prefix
    * @param urn      the namespace URN
    */
  private def verifyNamespace(template: String, prefix: String, urn: String): Unit = {
    val elem = toElem(template)
    elem.getNamespace(prefix) should be(urn)
  }

  "ModifiedTimeRequestFactory" should "generate a template for the default modified property" in {
    val template = ModifiedTimeRequestFactory.requestTemplate(createConfig())

    verifyNamespace(template, "D", "DAV:")
    verifyTemplate(template) { ns =>
      (ns \ DavConfig.DefaultModifiedProperty).filter(_.prefix == "D").text ==
        ModifiedTimeRequestFactory.PlaceholderTime
    }
  }

  it should "generate a template for a custom property and no namespace" in {
    val config = createConfig(property = CustomModifiedProperty)
    val template = ModifiedTimeRequestFactory.requestTemplate(config)

    verifyTemplate(template) { ns =>
      val seqAll = ns \ CustomModifiedProperty
      val seqFiltered = seqAll.filter(_.prefix == null)
      seqAll.size == seqFiltered.size &&
        seqFiltered.text == ModifiedTimeRequestFactory.PlaceholderTime
    }
  }

  it should "generate a template for a custom property with a namespace" in {
    val config = createConfig(property = CustomModifiedProperty,
      namespace = Some(CustomNamespace))
    val template = ModifiedTimeRequestFactory.requestTemplate(config)

    verifyNamespace(template, "D", "DAV:")
    verifyNamespace(template, ModifiedTimeRequestFactory.SyncNsPrefix, CustomNamespace)
    verifyTemplate(template) { ns =>
      (ns \ CustomModifiedProperty)
        .filter(_.prefix == ModifiedTimeRequestFactory.SyncNsPrefix).text ==
        ModifiedTimeRequestFactory.PlaceholderTime
    }
  }

  it should "generate a correct request" in {
    val ModifiedTime = Instant.parse("2018-10-19T19:27:37.00Z")
    val FormattedTime = "Fri, 19 Oct 2018 19:27:37 GMT"
    val Template = s"<${ModifiedTimeRequestFactory.PlaceholderTime}>"
    val ExpectedRequest = s"<$FormattedTime>"

    ModifiedTimeRequestFactory
      .createModifiedTimeRequest(Template, ModifiedTime) should be(ExpectedRequest)
  }
}
