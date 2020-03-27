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

import com.github.sync.cli.CliHelpContext.InputParameterRef
import com.github.sync.cli.ParameterManager._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Success

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
    val proc = optionValue(Key, help = Some(HelpText))

    val helpContext = generateHelpContext(proc)
    helpContext.options.keys should contain only Key
    val helpData = helpContext.options(Key)
    helpData.attributes should be(Map(CliHelpContext.AttrHelpText -> HelpText))
  }

  it should "support a description for a constant value processor" in {
    val FallbackDesc = "This is the fallback value."
    val constProcValue: OptionValue[String] = Success(List("foo"))
    val fallbackProc = constantProcessor(constProcValue, Some(FallbackDesc))
    val proc = optionValue(Key)
      .fallback(fallbackProc)

    val helpContext = generateHelpContext(proc)
    val optionAttrs = helpContext.options(Key)
    optionAttrs.attributes should be(Map(CliHelpContext.AttrFallbackValue -> FallbackDesc))
  }

  it should "support a description for constant values" in {
    val ValueDesc = "This is a meaningful default value."
    val valueProc = constantOptionValueWithDesc(Some(ValueDesc), "foo", "bar")
    val proc = optionValue(Key)
      .fallback(valueProc)

    val helpContext = generateHelpContext(proc)
    val optionAttrs = helpContext.options(Key)
    optionAttrs.attributes should be(Map(CliHelpContext.AttrFallbackValue -> ValueDesc))
  }

  it should "support skipping a description for a constant value" in {
    val valueProc = constantOptionValueWithDesc(None, "foo", "bar")
    val proc = optionValue(Key)
      .fallback(valueProc)

    val helpContext = generateHelpContext(proc)
    val optionAttrs = helpContext.options(Key)
    optionAttrs.attributes should have size 0
  }

  it should "support a description for constant values via the DSL" in {
    val ValueDesc = "Description of this value."
    val proc = optionValue(Key)
      .fallbackValuesWithDesc(Some(ValueDesc), "foo", "bar", "baz")

    val helpContext = generateHelpContext(proc)
    val optionAttrs = helpContext.options(Key)
    optionAttrs.attributes should be(Map(CliHelpContext.AttrFallbackValue -> ValueDesc))
  }

  it should "generate a description for constant values" in {
    val Values = List("val1", "val2", "val3")
    val ValueDesc = s"<${Values.head}, ${Values(1)}, ${Values(2)}>"
    val proc = optionValue(Key)
      .fallbackValues(Values.head, Values.tail: _*)

    val helpContext = generateHelpContext(proc)
    val optionAttrs = helpContext.options(Key)
    optionAttrs.attributes should be(Map(CliHelpContext.AttrFallbackValue -> ValueDesc))
  }

  it should "generate a description for a single constant value" in {
    val Value = "test"
    val proc = optionValue(Key)
      .fallbackValues(Value)

    val helpContext = generateHelpContext(proc)
    val optionAttrs = helpContext.options(Key)
    optionAttrs.attributes should be(Map(CliHelpContext.AttrFallbackValue -> Value))
  }

  it should "handle an uninitialized help context gracefully" in {
    val proc = CliProcessor(context => {
      (42, context.updateHelpContext("test", "success"))
    })

    val helpContext = generateHelpContext(proc)
    helpContext.options should have size 0
  }

  it should "support descriptions and keys for input parameters" in {
    val Key2 = "target"
    val Key3 = "sourceFiles"
    val Help2 = "The target directory"
    val Help3 = "List of the files to be copied"
    val ExpInputs = List(InputParameterRef(0, Key2), InputParameterRef(1, Key), InputParameterRef(2, Key3))
    val procInp1 = inputValue(1, optKey = Some(Key), optHelp = Some(HelpText))
    val procInp2 = inputValue(0, optKey = Some(Key2), optHelp  = Some(Help2))
    val procInp3 = inputValues(2, -1, optKey = Some(Key3), optHelp  = Some(Help3))
    val proc = for {
      i1 <- procInp1
      i2 <- procInp2
      i3 <- procInp3
    } yield List(i1, i2, i3)

    val helpContext = generateHelpContext(proc)
    helpContext.options.keySet should contain theSameElementsAs List(Key, Key2, Key3)
    helpContext.options(Key).attributes(CliHelpContext.AttrHelpText) should be(HelpText)
    helpContext.options(Key2).attributes(CliHelpContext.AttrHelpText) should be(Help2)
    helpContext.options(Key3).attributes(CliHelpContext.AttrHelpText) should be(Help3)
    helpContext.inputs should contain theSameElementsInOrderAs ExpInputs
  }

  it should "support attributes for input parameters" in {
    val proc = inputValue(1, Some(Key))
    val helpContext1 = generateHelpContext(proc)

    val helpContext2 = helpContext1.addAttribute("foo", "bar")
    helpContext2.inputs should contain only InputParameterRef(1, Key)
    val attrs = helpContext2.options(Key)
    attrs.attributes("foo") should be("bar")
  }

  it should "support input parameters with negative indices" in {
    val Key1 = "k1"
    val Key2 = "k2"
    val Key3 = "k3"
    val Key4 = "k4"
    val ExpInputs = List(InputParameterRef(0, Key1), InputParameterRef(1, Key2),
      InputParameterRef(-2, Key3), InputParameterRef(-1, Key4))
    val procInp1 = inputValue(0, optKey = Some(Key1), optHelp = Some(HelpText))
    val procInp2 = inputValue(1, optKey = Some(Key2))
    val procInp3 = inputValue(-2, optKey = Some(Key3))
    val procInp4 = inputValue(-1, optKey = Some(Key4))
    val proc = for {
      i1 <- procInp1
      i4 <- procInp4
      i3 <- procInp3
      i2 <- procInp2
    } yield List(i1, i2, i3, i4)

    val helpContext = generateHelpContext(proc)
    helpContext.inputs should contain theSameElementsInOrderAs ExpInputs
  }

  it should "generate a key for an input parameter if necessary" in {
    val Index = 17
    val proc = inputValue(Index)

    val helpContext = generateHelpContext(proc)
    helpContext.inputs should contain only InputParameterRef(Index, CliHelpContext.KeyInput + Index)
  }
}
