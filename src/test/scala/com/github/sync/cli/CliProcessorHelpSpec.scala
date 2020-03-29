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

import com.github.sync.cli.CliHelpGenerator.{CliHelpContext, InputParameterRef, OptionAttributes}
import com.github.sync.cli.ParameterManager._
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.SortedSet
import scala.util.Success

object CliProcessorHelpSpec {
  /** Key for a test option. */
  private val Key = "testOption"

  /** A test help text. */
  private val HelpText = "Test help text for the test help option."

  /**
    * Runs the given ''CliProcessor'' and returns the resulting help context.
    *
    * @param proc      the processor to be executed
    * @param optReader optional console reader for the context
    * @return the resulting help context
    */
  private def generateHelpContext(proc: CliProcessor[_], optReader: Option[ConsoleReader] = None): CliHelpContext = {
    val params = Parameters(Map.empty, Set.empty)
    implicit val reader: ConsoleReader = optReader getOrElse DefaultConsoleReader
    val (_, ctx) = ParameterManager.runProcessor(proc, params)
    ctx.helpContext
  }
}

/**
  * Test class for testing whether help and usage texts can be generated
  * correctly from ''CliProcessor'' objects.
  */
class CliProcessorHelpSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import CliProcessorHelpSpec._

  "The CLI library" should "store a help text for an option" in {
    val proc = optionValue(Key, help = Some(HelpText))

    val helpContext = generateHelpContext(proc)
    helpContext.options.keys should contain only Key
    val helpData = helpContext.options(Key)
    helpData.attributes should be(Map(CliHelpGenerator.AttrHelpText -> HelpText))
  }

  it should "support a description for a constant value processor" in {
    val FallbackDesc = "This is the fallback value."
    val constProcValue: OptionValue[String] = Success(List("foo"))
    val fallbackProc = constantProcessor(constProcValue, Some(FallbackDesc))
    val proc = optionValue(Key)
      .fallback(fallbackProc)

    val helpContext = generateHelpContext(proc)
    val optionAttrs = helpContext.options(Key)
    optionAttrs.attributes should be(Map(CliHelpGenerator.AttrFallbackValue -> FallbackDesc))
  }

  it should "support a description for constant values" in {
    val ValueDesc = "This is a meaningful default value."
    val valueProc = constantOptionValueWithDesc(Some(ValueDesc), "foo", "bar")
    val proc = optionValue(Key)
      .fallback(valueProc)

    val helpContext = generateHelpContext(proc)
    val optionAttrs = helpContext.options(Key)
    optionAttrs.attributes should be(Map(CliHelpGenerator.AttrFallbackValue -> ValueDesc))
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
    optionAttrs.attributes should be(Map(CliHelpGenerator.AttrFallbackValue -> ValueDesc))
  }

  it should "generate a description for constant values" in {
    val Values = List("val1", "val2", "val3")
    val ValueDesc = s"<${Values.head}, ${Values(1)}, ${Values(2)}>"
    val proc = optionValue(Key)
      .fallbackValues(Values.head, Values.tail: _*)

    val helpContext = generateHelpContext(proc)
    val optionAttrs = helpContext.options(Key)
    optionAttrs.attributes should be(Map(CliHelpGenerator.AttrFallbackValue -> ValueDesc))
  }

  it should "generate a description for a single constant value" in {
    val Value = "test"
    val proc = optionValue(Key)
      .fallbackValues(Value)

    val helpContext = generateHelpContext(proc)
    val optionAttrs = helpContext.options(Key)
    optionAttrs.attributes should be(Map(CliHelpGenerator.AttrFallbackValue -> Value))
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
    val procInp2 = inputValue(0, optKey = Some(Key2), optHelp = Some(Help2))
    val procInp3 = inputValues(2, -1, optKey = Some(Key3), optHelp = Some(Help3))
    val proc = for {
      i1 <- procInp1
      i2 <- procInp2
      i3 <- procInp3
    } yield List(i1, i2, i3)

    val helpContext = generateHelpContext(proc)
    helpContext.options.keySet should contain theSameElementsAs List(Key, Key2, Key3)
    helpContext.options(Key).attributes(CliHelpGenerator.AttrHelpText) should be(HelpText)
    helpContext.options(Key2).attributes(CliHelpGenerator.AttrHelpText) should be(Help2)
    helpContext.options(Key3).attributes(CliHelpGenerator.AttrHelpText) should be(Help3)
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
    helpContext.inputs should contain only InputParameterRef(Index, CliHelpGenerator.KeyInput + Index)
  }

  it should "merge the attributes of command line options that are added multiple times" in {
    val Attrs1 = OptionAttributes(Map("attr1" -> "value1", "attr2" -> "value2",
      CliHelpGenerator.AttrHelpText -> "old help"))
    val ExpAttrs = OptionAttributes(Attrs1.attributes + (CliHelpGenerator.AttrHelpText -> HelpText))
    val helpContext = new CliHelpContext(Map(Key -> Attrs1), SortedSet.empty, None, Nil)

    val nextContext = helpContext.addOption(Key, Some(HelpText))
    nextContext.options(Key) should be(ExpAttrs)
  }

  it should "set a single-valued attribute for options with a single value" in {
    val Key2 = Key + "_other"
    val proc1 = optionValue(Key).single
    val proc2 = optionValue(Key2)
    val proc = for {
      v1 <- proc1
      v2 <- proc2
    } yield List(v1, v2)

    val helpContext = generateHelpContext(proc)
    helpContext.hasAttribute(Key, CliHelpGenerator.AttrSingleValue) shouldBe true
    helpContext.hasAttribute(Key2, CliHelpGenerator.AttrSingleValue) shouldBe false
  }

  it should "support querying a boolean attribute for a non-existing option" in {
    val helpContext = new CliHelpContext(Map.empty, SortedSet.empty, None, Nil)

    helpContext.hasAttribute(Key, "foo") shouldBe false
  }

  it should "set a mandatory attribute for mandatory options" in {
    val Key2 = Key + "_optional"
    val proc1 = optionValue(Key).single.mandatory
    val proc2 = optionValue(Key2).single
    val proc = for {
      v1 <- proc1
      v2 <- proc2
    } yield List(v1, v2)

    val helpContext = generateHelpContext(proc)
    helpContext.hasAttribute(Key, CliHelpGenerator.AttrMandatory) shouldBe true
    helpContext.hasAttribute(Key2, CliHelpGenerator.AttrMandatory) shouldBe false
  }

  it should "support groups for conditional options" in {
    val procCond = optionValue("condition").isDefined
    val procIf = optionValue("if", Some("help-if"))
    val procElse = optionValue("else", Some("help-else"))
    val procFail = optionValue("fail", Some("help-fail"))
    val procOther = optionValue(Key, Some(HelpText))
    val procCase = conditionalValue(procCond, procIf, procElse, procFail, Some("grp-if"),
      Some("grp-else"), Some("grp-fail"))
    val proc = for {
      v1 <- procCase
      v2 = procOther
    } yield List(v1, v2)

    val helpContext = generateHelpContext(proc)
    helpContext.hasAttribute(Key, CliHelpGenerator.AttrGroup) shouldBe false
    val attrIf = helpContext.options("if")
    CliHelpGenerator.isInGroup(attrIf, "grp-if") shouldBe true
    attrIf.attributes(CliHelpGenerator.AttrHelpText) should be("help-if")
    val attrElse = helpContext.options("else")
    CliHelpGenerator.isInGroup(attrElse, "grp-else") shouldBe true
    attrElse.attributes(CliHelpGenerator.AttrHelpText) should be("help-else")
    val attrFail = helpContext.options("fail")
    CliHelpGenerator.isInGroup(attrFail, "grp-fail") shouldBe true
    attrFail.attributes(CliHelpGenerator.AttrHelpText) should be("help-fail")
  }

  it should "support nested conditional groups" in {
    val procCond1 = optionValue("condition1").isDefined
    val procCond2 = optionValue("condition2").isDefined
    val procIfNested = optionValue("if-nested", Some("help-if-nested"))
    val procElseNested = optionValue("else-nested", Some("help-else-nested"))
    val procElse = optionValue("else", Some("help-else"))
    val procCaseNested = conditionalValue(procCond2, procIfNested, procElseNested,
      ifGroup = Some("grp-if-nested"), elseGroup = Some("grp-else-nested"))
    val procCase = conditionalValue(procCond1, procCaseNested, procElse,
      ifGroup = Some("grp-if"), elseGroup = Some("grp-else"))

    val helpContext = generateHelpContext(procCase)
    val attrIfNested = helpContext.options("if-nested")
    CliHelpGenerator.isInGroup(attrIfNested, "grp-if-nested") shouldBe true
    CliHelpGenerator.isInGroup(attrIfNested, "grp-if") shouldBe true
    CliHelpGenerator.groups(attrIfNested) should contain only("grp-if-nested", "grp-if")
    attrIfNested.attributes(CliHelpGenerator.AttrHelpText) should be("help-if-nested")
    val attrElseNested = helpContext.options("else-nested")
    CliHelpGenerator.groups(attrElseNested) should contain only("grp-else-nested", "grp-if")
    CliHelpGenerator.isInGroup(attrElseNested, "grp-if-nested") shouldBe false
    val attrElse = helpContext.options("else")
    CliHelpGenerator.groups(attrElse) should contain only "grp-else"
  }

  it should "merge the values of group attributes" in {
    val procCond = optionValue("condition").isDefined
    val procIf = optionValue(Key)
    val procElse = optionValue(Key)
    val procCase = conditionalValue(procCond, ifProc = procIf, ifGroup = Some("g1"),
      elseProc = procElse, elseGroup = Some("g2"))

    val helpContext = generateHelpContext(procCase)
    val attr = helpContext.options(Key)
    CliHelpGenerator.groups(attr) should contain only("g1", "g2")
  }

  it should "handle groups whose name is a prefix of another group" in {
    val mapAttr = Map(CliHelpGenerator.AttrGroup -> "groupSub")
    val attr = OptionAttributes(mapAttr)

    CliHelpGenerator.isInGroup(attr, "group") shouldBe false
  }

  it should "correctly execute a group check if no groups are available" in {
    val attr = OptionAttributes(Map.empty)

    CliHelpGenerator.isInGroup(attr, "someGroup") shouldBe false
  }

  it should "returns the groups of an option if no groups are available" in {
    val attr = OptionAttributes(Map.empty)

    CliHelpGenerator.groups(attr) should have size 0
  }

  it should "use a dummy console reader when running processors to get meta data" in {
    val Key2 = "readerOption"
    val procCond = optionValue("condition").isDefined
    val procIf = optionValue(Key).fallback(consoleReaderValue(Key2, password = true))
    val procElse = optionValue(Key2)
    val procCase = conditionalValue(procCond, ifProc = procIf, ifGroup = Some("g1"),
      elseProc = procElse, elseGroup = Some("g2"))
    val reader = mock[ConsoleReader]

    val helpContext = generateHelpContext(procCase, optReader = Some(reader))
    val attr = helpContext.options(Key2)
    CliHelpGenerator.isInGroup(attr, "g2") shouldBe true
    verifyZeroInteractions(reader)
  }
}
