/*
 * Copyright 2018-2021 The Developers Team.
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

package com.github.sync.protocol.webdav

import akka.http.scaladsl.model.Uri
import com.github.cloudfiles.webdav.{DavModel, DavParser}
import com.github.sync.SyncTypes
import com.github.sync.protocol.config.DavStructureConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

object DavProtocolConverterSpec {
  /** A test element URI as string. */
  private val TestUriStr = "https://stream.sync.example.org/test"

  /** An element URI used by test cases. */
  private val TestUri = Uri(TestUriStr)

  /** The level used by tests. */
  private val TestLevel = 3

  /** The string representation of the test modified time. */
  private val LastModifiedTimeStr = "2021-06-03T16:55:32Z"

  /** The Instant representation of the test modified time. */
  private val LastModifiedTime = Instant.parse(LastModifiedTimeStr)

  /** Constant for a config with basic settings. */
  private val PlainConfig = DavStructureConfig(optLastModifiedProperty = None, optLastModifiedNamespace = None,
    deleteBeforeOverride = false)

  /**
    * Generates the URI of a specific element derived from the test URI.
    *
    * @param name the element name
    * @return the URI string for this element
    */
  private def elementUri(name: String): String = TestUriStr + name
}

/**
  * Test class for ''DavProtocolConverter''.
  */
class DavProtocolConverterSpec extends AnyFlatSpec with Matchers {

  import DavProtocolConverterSpec._

  "DavProtocolConverter" should "convert a string ID to a URI" in {
    val converter = new DavProtocolConverter(PlainConfig)

    converter.elementIDFromString(TestUri.toString()) should be(TestUri)
  }

  it should "convert a sync folder to a dav folder" in {
    val FolderName = "testFolder"
    val SyncFolder = SyncTypes.FsFolder(null, "/some/rel/uri", 0)
    val converter = new DavProtocolConverter(PlainConfig)

    val fsFolder = converter.toFsFolder(SyncFolder, FolderName)
    fsFolder.name should be(FolderName)
  }

  it should "convert a dav folder to a sync folder" in {
    val FolderName = "davTest Folder"
    val EncFolderName = "davTest%20Folder"
    val ParentPath = "/path/of/the/parent/"
    val fsFolder = DavModel.newFolder(FolderName, id = elementUri(EncFolderName))
    val expSyncFolder = SyncTypes.FsFolder(elementUri(EncFolderName), ParentPath + EncFolderName, TestLevel)
    val converter = new DavProtocolConverter(PlainConfig)

    converter.toFolderElement(fsFolder, ParentPath, TestLevel) should be(expSyncFolder)
  }

  it should "convert a sync file to a dav file" in {
    val FileName = "testFile.txt"
    val SyncFile = SyncTypes.FsFile(id = TestUriStr, relativeUri = "/some/uri/test.txt", size = 8192,
      lastModified = LastModifiedTime, level = TestLevel)
    val converter = new DavProtocolConverter(PlainConfig)

    val fsFile = converter.toFsFile(SyncFile, FileName, useID = true)
    fsFile.id should be(TestUri)
    fsFile.name should be(FileName)
    fsFile.size should be(SyncFile.size)
    fsFile.attributes.values should have size 1
    fsFile.attributes.values(DavParser.AttrModifiedAt) should be(LastModifiedTimeStr)
  }

  it should "convert a sync file to a dav file if an alternative last modified property is set" in {
    val FileName = "testFile.txt"
    val ModifiedProperty = "customLastModified"
    val Namespace = ":custom:ns"
    val SyncFile = SyncTypes.FsFile(id = TestUriStr, relativeUri = "/some/uri/test.txt", size = 8192,
      lastModified = LastModifiedTime, level = TestLevel)
    val config = DavStructureConfig(optLastModifiedProperty = Some(ModifiedProperty),
      optLastModifiedNamespace = Some(Namespace), deleteBeforeOverride = false)
    val converter = new DavProtocolConverter(config)

    val fsFile = converter.toFsFile(SyncFile, FileName, useID = true)
    fsFile.id should be(TestUri)
    fsFile.name should be(FileName)
    fsFile.size should be(SyncFile.size)
    fsFile.attributes.values should have size 1
    fsFile.attributes.values(DavModel.AttributeKey(Namespace, ModifiedProperty)) should be(LastModifiedTimeStr)
  }

  it should "optionally ignore the ID when converting a sync file to a dav file" in {
    val FileName = "testFile.txt"
    val SyncFile = SyncTypes.FsFile(id = TestUriStr, relativeUri = "/some/uri/test.txt", size = 8192,
      lastModified = LastModifiedTime, level = TestLevel)
    val converter = new DavProtocolConverter(PlainConfig)

    val fsFile = converter.toFsFile(SyncFile, FileName, useID = false)
    fsFile.id should be(null)
    fsFile.name should be(FileName)
    fsFile.size should be(SyncFile.size)
    fsFile.attributes.values should have size 1
    fsFile.attributes.values(DavParser.AttrModifiedAt) should be(LastModifiedTimeStr)
  }

  it should "convert a dav file to a sync file" in {
    val FileName = "davTest File.doc"
    val EncFileName = "davTest%20File.doc"
    val ParentPath = "/parent/path/of/test/file/"
    val fsFile = DavModel.DavFile(id = elementUri(EncFileName), name = FileName, description = null,
      size = 16384, createdAt = null, lastModifiedAt = LastModifiedTime, attributes = DavModel.EmptyAttributes)
    val expSyncFile = SyncTypes.FsFile(id = elementUri(EncFileName), relativeUri = ParentPath + EncFileName,
      size = fsFile.size, level = TestLevel, lastModified = LastModifiedTime)
    val converter = new DavProtocolConverter(PlainConfig)

    converter.toFileElement(fsFile, ParentPath, TestLevel) should be(expSyncFile)
  }

  it should "obtain a dav file's modified time if an alternative property is set" in {
    val ModifiedProperty = "OtherModifiedDate"
    val attributes = DavModel.EmptyAttributes.withAttribute("DAV:", ModifiedProperty, LastModifiedTimeStr)
    val fsFile = DavModel.DavFile(id = elementUri("modified.txt"), name = "FileName", description = null,
      size = 16384, createdAt = null, lastModifiedAt = Instant.EPOCH, attributes = attributes)
    val converter = new DavProtocolConverter(PlainConfig.copy(optLastModifiedProperty = Some(ModifiedProperty)))

    val syncFile = converter.toFileElement(fsFile, "/parent/", TestLevel)
    syncFile.lastModified should be(LastModifiedTime)
  }

  it should "obtain a dav file's modified time if an alternative property with namespace is set" in {
    val ModifiedProperty = "OtherModifiedProperty"
    val Namespace = "other:namespace"
    val attributes = DavModel.EmptyAttributes.withAttribute(Namespace, ModifiedProperty, LastModifiedTimeStr)
    val fsFile = DavModel.DavFile(id = elementUri("modified.txt"), name = "FileName", description = null,
      size = 16384, createdAt = null, lastModifiedAt = Instant.EPOCH, attributes = attributes)
    val config = DavStructureConfig(optLastModifiedProperty = Some(ModifiedProperty),
      optLastModifiedNamespace = Some(Namespace), deleteBeforeOverride = false)
    val converter = new DavProtocolConverter(config)

    val syncFile = converter.toFileElement(fsFile, "/parent/", TestLevel)
    syncFile.lastModified should be(LastModifiedTime)
  }

  it should "fall back to the default modified time if the alternative property is undefined" in {
    val fsFile = DavModel.DavFile(id = elementUri("modified.txt"), name = "FileName", description = null,
      size = 16384, createdAt = null, lastModifiedAt = LastModifiedTime, attributes = DavModel.EmptyAttributes)
    val converter = new DavProtocolConverter(PlainConfig.copy(optLastModifiedProperty = Some("undefined")))

    val syncFile = converter.toFileElement(fsFile, "/path/", TestLevel)
    syncFile.lastModified should be(LastModifiedTime)
  }
}
