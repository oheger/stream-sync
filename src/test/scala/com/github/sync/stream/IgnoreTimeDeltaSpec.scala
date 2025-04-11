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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
  * Test class for ''IgnoreTimeDelta''.
  */
class IgnoreTimeDeltaSpec extends AnyFlatSpec, Matchers:
  "IgnoreTimeDelta" should "define a constant to ignore no deltas" in {
    IgnoreTimeDelta.Zero.deltaSec should be(0)
  }

  it should "prevent the creation of instances with a negative delta" in {
    intercept[IllegalArgumentException] {
      IgnoreTimeDelta(-1.second)
    }
  }

  it should "allow creating an instance with a delta of 0" in {
    val ignoreDelta = IgnoreTimeDelta(0.seconds)

    ignoreDelta.deltaSec should be(0)
  }

  it should "compare two files with a delta greater than the configured threshold" in {
    val ignoreDelta = IgnoreTimeDelta(5.seconds)
    val file1 = AbstractStageSpec.createFile(1)
    val file2 = AbstractStageSpec.createFile(1, deltaTime = ignoreDelta.deltaSec + 1)

    ignoreDelta.isDifferentFileTimes(file1, file2) shouldBe true
  }

  it should "compare two files with a delta equal to the configured threshold" in {
    val ignoreDelta = IgnoreTimeDelta(11.seconds)
    val file1 = AbstractStageSpec.createFile(1)
    val file2 = AbstractStageSpec.createFile(1, deltaTime = ignoreDelta.deltaSec)

    ignoreDelta.isDifferentFileTimes(file1, file2) shouldBe false
  }

  it should "use the absolute time delta when comparing to elements" in {
    val ignoreDelta = IgnoreTimeDelta(5.seconds)
    val file1 = AbstractStageSpec.createFile(1, deltaTime = ignoreDelta.deltaSec + 1)
    val file2 = AbstractStageSpec.createFile(1)

    ignoreDelta.isDifferentFileTimes(file1, file2) shouldBe true
  }

  it should "compare two files with identical timestamps" in {
    val file1 = AbstractStageSpec.createFile(1)
    val file2 = AbstractStageSpec.createFile(1)

    IgnoreTimeDelta.Zero.isDifferentFileTimes(file1, file2) shouldBe false
  }
