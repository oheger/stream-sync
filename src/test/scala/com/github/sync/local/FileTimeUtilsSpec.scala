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

package com.github.sync.local

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneId}

import com.github.sync.FileTestHelper
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

/**
  * Test class for ''FileTimeUtils''.
  */
class FileTimeUtilsSpec extends FlatSpec with BeforeAndAfterAll with Matchers with FileTestHelper {

  override protected def afterAll(): Unit = {
    tearDownTestFile()
  }

  "FileTimeUtils" should "obtain an instant from an undefined time zone" in {
    val instant = Instant.parse("2018-11-06T20:47:24.05Z")

    FileTimeUtils.instantFromTimeZone(instant, None) should be(instant)
  }

  it should "obtain an instant from a specific time zone" in {
    val instant = Instant.parse("2018-11-06T20:52:08.17Z")
    val zone = ZoneId.of("UTC+01:00")
    val expected = instant.plus(1, ChronoUnit.HOURS)

    FileTimeUtils.instantFromTimeZone(instant, Some(zone)) should be(expected)
  }

  it should "convert an instant to an undefined time zone" in {
    val instant = Instant.parse("2018-11-06T21:02:00.88Z")

    FileTimeUtils.instantToTimeZone(instant, None) should be(instant)
  }

  it should "convert an instant to a specific time zone" in {
    val instant = Instant.parse("2018-11-06T21:03:23.28Z")
    val zone = ZoneId.of("UTC+02:00")
    val expected = instant.minus(2, ChronoUnit.HOURS)

    FileTimeUtils.instantToTimeZone(instant, Some(zone)) should be(expected)
  }

  it should "correctly convert an instant from a time zone and back" in {
    val instant = Instant.parse("2018-11-09T21:05:24.55Z")
    val zone = ZoneId.of("UTC+02:00")

    val conv1 = FileTimeUtils.instantFromTimeZone(instant, Some(zone))
    val conv2 = FileTimeUtils.instantToTimeZone(conv1, Some(zone))
    conv2 should be(instant)
  }

  it should "fetch the timestamp from a file" in {
    val testFile = createDataFile()
    val instant = Instant.parse("2018-11-07T20:39:50.05Z")
    Files.setLastModifiedTime(testFile, FileTime.from(instant))

    FileTimeUtils getLastModifiedTime testFile should be(instant)
  }

  it should "fetch the timestamp from a file in a specific time zone" in {
    val testFile = createDataFile()
    val zone = ZoneId.of("UTC+02:00")
    val instant = Instant.parse("2018-11-07T19:49:20.88Z")
    val expected = instant.plus(2, ChronoUnit.HOURS)
    Files.setLastModifiedTime(testFile, FileTime.from(instant))

    FileTimeUtils.getLastModifiedTimeInTimeZone(testFile, Some(zone)) should be(expected)
  }

  it should "change the timestamp of a file" in {
    val testFile = createDataFile()
    val instant = Instant.parse("2018-11-07T20:53:22.44Z")

    FileTimeUtils.setLastModifiedTime(testFile, instant)
    val lastModified = Files.getLastModifiedTime(testFile).toInstant
    lastModified should be(instant)
  }

  it should "change the timestamp of a file in another time zone" in {
    val testFile = createDataFile()
    val zone = ZoneId.of("UTC+02:00")
    val instant = Instant.parse("2018-11-07T22:00:16.32Z")
    val expected = instant.minus(2, ChronoUnit.HOURS)

    FileTimeUtils.setLastModifiedTimeInTimeZone(testFile, instant, Some(zone))
    val lastModified = Files.getLastModifiedTime(testFile).toInstant
    lastModified should be(expected)
  }
}
