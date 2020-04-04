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

package com.github.sync.cli

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''Multiplicity''.
  */
class MultiplicitySpec extends AnyFlatSpec with Matchers {
  "Multiplicity" should "provide a string representation" in {
    val multiplicity = Multiplicity(1, 2)

    multiplicity.toString should be("1..2")
  }

  it should "provide a string representation for an unrestricted upper bound" in {
    val multiplicity = Multiplicity(0, -1)

    multiplicity.toString should be("0..*")
  }

  it should "parse a valid string representation" in {
    val multiplicity = Multiplicity.parse("11..27")

    multiplicity.lower should be(11)
    multiplicity.upper should be(27)
  }

  it should "throw an exception when parsing an invalid multiplicity string" in {
    val str = "1..invalid"
    val ex = intercept[IllegalArgumentException] {
      Multiplicity.parse(str)
    }
    ex.getMessage should include(str)
  }

  it should "parse a valid string representation with an unrestricted upper bound" in {
    val multiplicity = Multiplicity.parse("1..*")

    multiplicity.unbounded shouldBe true
  }

  it should "provide a constant for a single-value multiplicity" in {
    Multiplicity.SingleValue.unbounded shouldBe false
    Multiplicity.SingleValue.optional shouldBe false
    Multiplicity.SingleValue.mandatory shouldBe true
    Multiplicity.SingleValue.upper should be(1)
  }

  it should "provide a constant for an optional single-value multiplicity" in {
    Multiplicity.SingleOptional.unbounded shouldBe false
    Multiplicity.SingleOptional.optional shouldBe true
    Multiplicity.SingleOptional.mandatory shouldBe false
    Multiplicity.SingleOptional.upper should be(1)
  }

  it should "provide a constant for an unbounded multiplicity" in {
    Multiplicity.Unbounded.unbounded shouldBe true
    Multiplicity.Unbounded.optional shouldBe true
    Multiplicity.Unbounded.mandatory shouldBe false
  }
}
