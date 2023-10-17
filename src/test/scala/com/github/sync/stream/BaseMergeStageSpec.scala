/*
 * Copyright 2018-2023 The Developers Team.
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

/**
  * Test class for basic functionlity provided by ''BaseMergeStage''. The main
  * functionality is tested together with concrete stage implementations.
  */
class BaseMergeStageSpec extends AnyFlatSpec, Matchers:
  "Input" should "return the opposite inlet" in {
    import BaseMergeStage.Input

    Input.Inlet1.opposite should be(Input.Inlet2)
    Input.Inlet2.opposite should be(Input.Inlet1)
  }
