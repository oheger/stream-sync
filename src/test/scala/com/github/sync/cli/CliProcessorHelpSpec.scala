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

import com.github.sync.cli.ParameterManager.{CliProcessor, Parameters}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object CliProcessorHelpSpec {
  /** Key for a test option. */
  private val Key = "testOption"

  /** A test help text. */
  private val HelpText = "Test help text for the test help option."

  /**
    * Runs the given ''CliProcessor'' and returns the resulting help context.
    *
    * @param proc the processor to be executed
    * @return the resulting help context
    */
  private def generateHelpContext(proc: CliProcessor[_]): CliHelpContext = {
    val params = Parameters(Map.empty, Set.empty)
    implicit val reader: ConsoleReader = DefaultConsoleReader
    val (_, ctx) = ParameterManager.runProcessor(proc, params)
    ctx.helpContext
  }
}

/**
  * Test class for testing whether help and usage texts can be generated
  * correctly from ''CliProcessor'' objects.
  */
class CliProcessorHelpSpec extends AnyFlatSpec with Matchers {

  import CliProcessorHelpSpec._

  "The CLI library" should "store a help text for an option" in {
    val proc = ParameterManager.optionValue(Key, help = Some(HelpText))

    val helpContext = generateHelpContext(proc)
    helpContext.options.keys should contain only Key
    val helpData = helpContext.options(Key)
    helpData.attributes should be(Map(CliHelpContext.AttrHelpText -> HelpText))
  }
}
