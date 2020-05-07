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

import java.util.Locale

import com.github.sync.cli.CliHelpGenerator.{CliHelpContext, ColumnGenerator, InputParamOverviewSymbols, InputParameterRef, OptionAttributes, OptionFilter, OptionMetaData, OptionSortFunc}
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

  /** The platform-specific line separator. */
  private val CR = System.lineSeparator()

  /** Constant for undefined parameters used per default to run processors. */
  private val EmptyParameters = Parameters(Map.empty, Set.empty)

  /** A test column generator function. */
  private val TestColumnGenerator: ColumnGenerator =
    data => List(data.toString)

  /** A test column generator function that returns the option key. */
  private val KeyColumnGenerator: ColumnGenerator =
    data => List(data.key)

  /**
    * A test column generator function that returns the multi-line help text.
    */
  private val HelpColumnGenerator: ColumnGenerator =
    data => data.attributes.attributes(CliHelpGenerator.AttrHelpText).split(CR).toList

  /**
    * Runs the given ''CliProcessor'' and returns the resulting help context.
    *
    * @param proc      the processor to be executed
    * @param params    the parameters to be used
    * @param optReader optional console reader for the context
    * @return the resulting help context
    */
  private def generateHelpContext(proc: CliProcessor[_], params: Parameters = EmptyParameters,
                                  optReader: Option[ConsoleReader] = None): CliHelpContext = {
    implicit val reader: ConsoleReader = optReader getOrElse DefaultConsoleReader
    val (_, ctx) = ParameterManager.runProcessor(proc, params)
    ctx.helpContext
  }

  /**
    * Helper function to obtain the value of an attribute of an option. Throws
    * an exception if the option or the attribute is not present.
    *
    * @param helpContext the helper context
    * @param optionKey   the option key
    * @param attrKey     the attribute key
    * @return the value of this attribute
    */
  private def fetchAttribute(helpContext: CliHelpContext, optionKey: String, attrKey: String): String =
    helpContext.options(optionKey).attributes(attrKey)

  /**
    * Generates the key of the test option with the given index.
    *
    * @param idx the index of the test option
    * @return the key for this option
    */
  private def testKey(idx: Int): String = s"$Key$idx"

  /**
    * Generates test meta data for an option.
    *
    * @param idx the index of the test option
    * @return the test meta data
    */
  private def testOptionMetaData(idx: Int): OptionMetaData = {
    val key = testKey(idx)
    testOptionMetaData(key, HelpText + key)
  }

  /**
    * Generates test meta data based on the given parameters.
    *
    * @param key  the option key
    * @param help the help text
    * @return the resulting meta data
    */
  private def testOptionMetaData(key: String, help: String): OptionMetaData = {
    val attrs = Map(CliHelpGenerator.AttrHelpText -> help,
      CliHelpGenerator.AttrOptionType -> CliHelpGenerator.OptionTypeOption)
    OptionMetaData(key, OptionAttributes(attrs))
  }

  /**
    * Creates a new help context with standard settings and the given options.
    *
    * @param options the options
    * @return the help context
    */
  private def createHelpContext(options: Map[String, OptionAttributes] = Map.empty): CliHelpContext =
    new CliHelpContext(options, SortedSet.empty, None, List.empty)

  /**
    * Generates a help context object that contains a number of test options.
    *
    * @param count the number of options to generate
    * @return the test help context
    */
  private def helpContextWithOptions(count: Int): CliHelpContext = {
    val options = (1 to count).map(testOptionMetaData)
      .map(data => (data.key, data.attributes)).toMap
    createHelpContext(options)
  }

  private def addInputParameter(helpContext: CliHelpContext, multiplicity: String, key: String = Key,
                                index: Int = 1): CliHelpContext =
    helpContext.addInputParameter(index, Some(key), None)
      .addAttribute(CliHelpGenerator.AttrMultiplicity, multiplicity)
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
    fetchAttribute(helpContext, Key, CliHelpGenerator.AttrHelpText) should be(HelpText)
  }

  it should "support a description for a constant value processor" in {
    val FallbackDesc = "This is the fallback value."
    val constProcValue: OptionValue[String] = Success(List("foo"))
    val fallbackProc = constantProcessor(constProcValue, Some(FallbackDesc))
    val proc = optionValue(Key)
      .fallback(fallbackProc)

    val helpContext = generateHelpContext(proc)
    fetchAttribute(helpContext, Key, CliHelpGenerator.AttrFallbackValue) should be(FallbackDesc)
  }

  it should "support a description for constant values" in {
    val ValueDesc = "This is a meaningful default value."
    val valueProc = constantOptionValueWithDesc(Some(ValueDesc), "foo", "bar")
    val proc = optionValue(Key)
      .fallback(valueProc)

    val helpContext = generateHelpContext(proc)
    fetchAttribute(helpContext, Key, CliHelpGenerator.AttrFallbackValue) should be(ValueDesc)
  }

  it should "support skipping a description for a constant value" in {
    val valueProc = constantOptionValueWithDesc(None, "foo", "bar")
    val proc = optionValue(Key)
      .fallback(valueProc)

    val helpContext = generateHelpContext(proc)
    helpContext.hasAttribute(Key, CliHelpGenerator.AttrFallbackValue) shouldBe false
  }

  it should "support a description for constant values via the DSL" in {
    val ValueDesc = "Description of this value."
    val proc = optionValue(Key)
      .fallbackValuesWithDesc(Some(ValueDesc), "foo", "bar", "baz")

    val helpContext = generateHelpContext(proc)
    fetchAttribute(helpContext, Key, CliHelpGenerator.AttrFallbackValue) should be(ValueDesc)
  }

  it should "generate a description for constant values" in {
    val Values = List("val1", "val2", "val3")
    val ValueDesc = s"<${Values.head}, ${Values(1)}, ${Values(2)}>"
    val proc = optionValue(Key)
      .fallbackValues(Values.head, Values.tail: _*)

    val helpContext = generateHelpContext(proc)
    fetchAttribute(helpContext, Key, CliHelpGenerator.AttrFallbackValue) should be(ValueDesc)
  }

  it should "generate a description for a single constant value" in {
    val Value = "test"
    val proc = optionValue(Key)
      .fallbackValues(Value)

    val helpContext = generateHelpContext(proc)
    fetchAttribute(helpContext, Key, CliHelpGenerator.AttrFallbackValue) should be(Value)
  }

  it should "update the help context with a fallback processor" in {
    val paramsMap = Map(Key -> List("true"))
    val params = Parameters(paramsMap, Set.empty)
    val proc = optionValue(Key)
      .toBoolean
      .fallbackValues(false)

    val helpContext = generateHelpContext(proc, params = params)
    fetchAttribute(helpContext, Key, CliHelpGenerator.AttrFallbackValue) should be(false.toString)
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
    val ExpAttrs = Attrs1.attributes + (CliHelpGenerator.AttrHelpText -> HelpText)
    val helpContext = new CliHelpContext(Map(Key -> Attrs1), SortedSet.empty, None, Nil)

    val nextContext = helpContext.addOption(Key, Some(HelpText))
    nextContext.options(Key).attributes should contain allElementsOf ExpAttrs
  }

  it should "set a multiplicity attribute for options with a single value" in {
    val Key2 = Key + "_other"
    val proc1 = optionValue(Key).single
    val proc2 = optionValue(Key2)
    val proc = for {
      v1 <- proc1
      v2 <- proc2
    } yield List(v1, v2)

    val helpContext = generateHelpContext(proc)
    helpContext.options(Key).attributes(CliHelpGenerator.AttrMultiplicity) shouldBe "0..1"
    helpContext.hasAttribute(Key2, CliHelpGenerator.AttrMultiplicity) shouldBe false
  }

  it should "support querying a boolean attribute for a non-existing option" in {
    val helpContext = new CliHelpContext(Map.empty, SortedSet.empty, None, Nil)

    helpContext.hasAttribute(Key, "foo") shouldBe false
  }

  it should "set a multiplicity attribute for mandatory options" in {
    val Key2 = Key + "_optional"
    val proc1 = optionValue(Key).single.mandatory
    val proc2 = optionValue(Key2).single
    val proc = for {
      v1 <- proc1
      v2 <- proc2
    } yield List(v1, v2)

    val helpContext = generateHelpContext(proc)
    helpContext.options(Key).attributes(CliHelpGenerator.AttrMultiplicity) shouldBe "1..1"
  }

  it should "support groups for conditional options" in {
    val procCond = optionValue("condition").isDefined
    val procIf = optionValue("if", Some("help-if"))
    val procElse = optionValue("else", Some("help-else"))
    val procOther = optionValue(Key, Some(HelpText))
    val procCase = conditionalValue(procCond, procIf, procElse, Some("grp-if"), Some("grp-else"))
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

  it should "return the groups of an option if no groups are available" in {
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

  it should "correctly handle the groups of a conditional group processor" in {
    val Key2 = "OtherKey"
    val procG1 = optionValue(Key)
    val procG2 = optionValue(Key2)
    val procGroupSel = constantOptionValue("g1").single.mandatory
    val groupMap = Map("g1" -> procG1, "g2" -> procG2)
    val procCondGroup = conditionalGroupValue(procGroupSel, groupMap)

    val helpContext = generateHelpContext(procCondGroup)
    CliHelpGenerator.isInGroup(helpContext.options(Key), "g1") shouldBe true
    CliHelpGenerator.isInGroup(helpContext.options(Key2), "g2") shouldBe true
    CliHelpGenerator.isInGroup(helpContext.options(Key2), "g1") shouldBe false
  }

  it should "set the multiplicity attribute if it is defined" in {
    val proc = optionValue(Key).multiplicity(1, 4)

    val helpContext = generateHelpContext(proc)
    val attr = helpContext.options(Key)
    attr.attributes(CliHelpGenerator.AttrMultiplicity) should be("1..4")
  }

  it should "handle an unrestricted multiplicity" in {
    val proc = optionValue(Key).multiplicity()

    val helpContext = generateHelpContext(proc)
    val attr = helpContext.options(Key)
    attr.attributes(CliHelpGenerator.AttrMultiplicity) should be("0..*")
  }

  it should "set the option type attribute for a plain option" in {
    val proc = optionValue(Key)

    val helpContext = generateHelpContext(proc)
    fetchAttribute(helpContext, Key, CliHelpGenerator.AttrOptionType) should be(CliHelpGenerator.OptionTypeOption)
  }

  it should "set the option type attribute for an input parameter" in {
    val proc = inputValue(1, optKey = Some(Key))

    val helpContext = generateHelpContext(proc)
    fetchAttribute(helpContext, Key, CliHelpGenerator.AttrOptionType) should be(CliHelpGenerator.OptionTypeInput)
  }

  it should "generate option help texts with default settings" in {
    val Count = 8
    val ExpText = (1 to Count).map(testOptionMetaData).mkString(CR + CR)
    val helpContext = helpContextWithOptions(Count)

    val text = CliHelpGenerator.generateOptionsHelp(helpContext)(TestColumnGenerator)
    text should be(ExpText)
  }

  it should "support changing the newline string" in {
    val Count = 4
    val ExpText = (1 to Count).map(testOptionMetaData).mkString(CR)
    val helpContext = helpContextWithOptions(Count)

    val text = CliHelpGenerator.generateOptionsHelp(helpContext, optNewline = None)(TestColumnGenerator)
    text should be(ExpText)
  }

  it should "sort options in a case-insensitive manner" in {
    val Count = 4
    val KeyMin = testKey(0).toLowerCase(Locale.ROOT)
    val KeyMax = testKey(Count + 1).toUpperCase(Locale.ROOT)
    val ExpText = testOptionMetaData(KeyMin, HelpText + KeyMin) + CR + CR +
      (1 to Count).map(testOptionMetaData).mkString(CR + CR) + CR + CR +
      testOptionMetaData(KeyMax, HelpText + KeyMax)
    val helpContext = helpContextWithOptions(Count)
      .addOption(KeyMin, Some(HelpText + KeyMin))
      .addOption(KeyMax, Some(HelpText + KeyMax))

    val text = CliHelpGenerator.generateOptionsHelp(helpContext)(TestColumnGenerator)
    text should be(ExpText)
  }

  it should "support a custom sort function for options" in {
    val Count = 8
    val sortFunc: OptionSortFunc = _.sortWith(_.key > _.key) // reverse sort
    val ExpText = (1 to Count).map(testOptionMetaData).reverse.mkString(CR + CR)
    val helpContext = helpContextWithOptions(Count)

    val text = CliHelpGenerator.generateOptionsHelp(helpContext, sortFunc = sortFunc)(TestColumnGenerator)
    text should be(ExpText)
  }

  it should "support filtering the options to generate help information for" in {
    val CountAll = 8
    val CountFiltered = 4
    val ExpText = (1 to CountFiltered).map(testOptionMetaData).mkString(CR + CR)
    val helpContext = helpContextWithOptions(CountAll)
    val filterFunc: OptionFilter = _.key <= testKey(CountFiltered)

    val text = CliHelpGenerator.generateOptionsHelp(helpContext, filterFunc = filterFunc)(TestColumnGenerator)
    text should be(ExpText)
  }

  it should "support multiple columns for the option help" in {
    val helpContext = createHelpContext()
      .addOption(Key, Some(HelpText))
    val ExpText = Key + CliHelpGenerator.DefaultPadding + HelpText

    val text = CliHelpGenerator.generateOptionsHelp(helpContext)(KeyColumnGenerator, HelpColumnGenerator)
    text should be(ExpText)
  }

  it should "align the columns for the option help based on their maximum length" in {
    val ShortHelpText = "short help"
    val helpContext = createHelpContext()
      .addOption(testKey(1), Some(ShortHelpText))
      .addOption(testKey(2), Some(HelpText))
    val ExpText = ShortHelpText + (" " * (HelpText.length - ShortHelpText.length)) +
      CliHelpGenerator.DefaultPadding + testKey(1) + CR + CR +
      HelpText + CliHelpGenerator.DefaultPadding + testKey(2)

    val text = CliHelpGenerator.generateOptionsHelp(helpContext)(HelpColumnGenerator, KeyColumnGenerator)
    text should be(ExpText)
  }

  it should "support multiple lines in columns for option help" in {
    val spaceKey = " " * Key.length
    val helpContext = createHelpContext()
      .addOption(Key, Some("Line1" + CR + "Line2" + CR + "Line3"))
    val ExpText = Key + CliHelpGenerator.DefaultPadding + "Line1" + CR +
      spaceKey + CliHelpGenerator.DefaultPadding + "Line2" + CR +
      spaceKey + CliHelpGenerator.DefaultPadding + "Line3"

    val text = CliHelpGenerator.generateOptionsHelp(helpContext)(KeyColumnGenerator, HelpColumnGenerator)
    text should be(ExpText)
  }

  it should "handle empty cells when generating option help" in {
    val EmptyColumnGenerator: ColumnGenerator = _ => List.empty
    val helpContext = createHelpContext()
      .addOption(Key, None)
    val ExpText = Key + CliHelpGenerator.DefaultPadding

    val text = CliHelpGenerator.generateOptionsHelp(helpContext)(KeyColumnGenerator, EmptyColumnGenerator)
    text should be(ExpText)
  }

  it should "generate options help if no option is matched" in {
    val helpContext = createHelpContext()
      .addOption(Key, Some(HelpText))
    val filter: OptionFilter = _ => false

    val text = CliHelpGenerator.generateOptionsHelp(helpContext, filterFunc = filter)(KeyColumnGenerator)
    text should be("")
  }

  it should "support changing the padding string for the option help table" in {
    val OtherPadding = " | "
    val helpContext = createHelpContext()
      .addOption(Key, Some(HelpText))
    val ExpText = Key + OtherPadding + HelpText

    val text = CliHelpGenerator.generateOptionsHelp(helpContext, padding = OtherPadding)(KeyColumnGenerator,
      HelpColumnGenerator)
    text should be(ExpText)
  }

  it should "provide an attribute ColumnGenerator that handles undefined attributes" in {
    val data = testOptionMetaData(1)
    val generator = CliHelpGenerator.attributeColumnGenerator(testKey(2))

    generator(data) should have size 0
  }

  it should "provide a ColumnGenerator that reads the value of an attribute" in {
    val data = testOptionMetaData(Key, HelpText)
    val generator = CliHelpGenerator.attributeColumnGenerator(CliHelpGenerator.AttrHelpText)

    generator(data) should be(List(HelpText))
  }

  it should "provide a default values ColumnGenerator that returns the original value" in {
    val data = testOptionMetaData(Key, HelpText)
    val generator = CliHelpGenerator.defaultValueColumnGenerator(
      CliHelpGenerator.attributeColumnGenerator(CliHelpGenerator.AttrHelpText), "foo", "bar")

    generator(data) should be(List(HelpText))
  }

  it should "provide a default values ColumnGenerator that returns the defaults if necessary" in {
    val data = testOptionMetaData(1)
    val defaults = List("These", "are", "the", "default", "values")
    val generator = CliHelpGenerator.defaultValueColumnGenerator(
      CliHelpGenerator.attributeColumnGenerator(Key), defaults: _*)

    generator(data) should be(defaults)
  }

  it should "provide a prefix generator that adds prefix data to another one" in {
    val PrefixLines = List("A prefix line", "Another prefix line")
    val PrefixText = ">>"
    val ExpResult = PrefixLines ++ List(">>Line1", ">>Line2")
    val orgGenerator: ColumnGenerator = _ => List("Line1", "Line2")

    val generator = CliHelpGenerator.prefixColumnGenerator(orgGenerator, PrefixLines, Some(PrefixText))
    generator(testOptionMetaData(1)) should be(ExpResult)
  }

  it should "provide a prefix generator that only adds prefix lines" in {
    val data = testOptionMetaData(Key, HelpText)
    val PrefixLines = List("", "p")
    val ExpResult = PrefixLines ++ List(HelpText)
    val orgGenerator = CliHelpGenerator.attributeColumnGenerator(CliHelpGenerator.AttrHelpText)

    val generator = CliHelpGenerator.prefixColumnGenerator(orgGenerator, PrefixLines)
    generator(data) should be(ExpResult)
  }

  it should "provide a prefix generator that returns no data if the wrapped generator yields no results" in {
    val data = testOptionMetaData(42)
    val orgGenerator = CliHelpGenerator.attributeColumnGenerator("nonExistingKey")

    val generator = CliHelpGenerator.prefixColumnGenerator(orgGenerator,
      prefixText = Some("prefix"), prefixLines = List("a", "b", "c"))
    generator(data) should have size 0
  }

  it should "provide a ColumnGenerator that composes the results of other generators" in {
    val attributes = Map("a1" -> "v1", "a2" -> "v2", "a3" -> "v3")
    val data = OptionMetaData(Key, OptionAttributes(attributes))
    val g1 = CliHelpGenerator.attributeColumnGenerator("a1")
    val g2 = CliHelpGenerator.attributeColumnGenerator("a2")
    val g3 = CliHelpGenerator.attributeColumnGenerator("a3")

    val generator = CliHelpGenerator.composeColumnGenerator(g1, g2, g3)
    val result = generator(data)
    result should be(List("v1", "v2", "v3"))
  }

  it should "provide a ColumnGenerator that does line wrapping" in {
    val text =
      """|Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy
         |eirmod tempor invidunt ut labore et dolore magna aliquyam erat, sed diam
         |sea takimata sanctus est Lore ipsum dolor sit amet.""".stripMargin
    val ExpResult = List("Lorem ipsum dolor sit amet,",
      "consetetur sadipscing elitr,",
      "sed diam nonumy",
      "eirmod tempor invidunt ut",
      "labore et dolore magna",
      "aliquyam erat, sed diam",
      "sea takimata sanctus est Lore",
      "ipsum dolor sit amet."
    )
    val data = testOptionMetaData(Key, text)
    val orgGenerator = CliHelpGenerator.attributeColumnGenerator(CliHelpGenerator.AttrHelpText)

    val generator = CliHelpGenerator.wrapColumnGenerator(orgGenerator, 30)
    generator(data) should be(ExpResult)
  }

  it should "provide a line wrapping ColumnGenerator that handles unbreakable strings" in {
    val text = "supercalifragilisticexpialidocious"
    val ExpResult = List("supercalifragilisticexpialidoc", "ious")
    val data = testOptionMetaData(Key, text)
    val orgGenerator = CliHelpGenerator.attributeColumnGenerator(CliHelpGenerator.AttrHelpText)

    val generator = CliHelpGenerator.wrapColumnGenerator(orgGenerator, 30)
    generator(data) should be(ExpResult)
  }

  it should "provide a line wrapping ColumnGenerator that handles line continuation characters" in {
    val text =
      """|Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy \
         |eirmod tempor invidunt ut labore et dolore magna aliquyam erat, sed diam \
         |sea takimata sanctus est Lorem ipsum dolor sit amet.""".stripMargin
    val ExpResult = List(
      "Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed",
      "diam nonumy eirmod tempor invidunt ut labore et dolore magna",
      "aliquyam erat, sed diam sea takimata sanctus est Lorem ipsum",
      "dolor sit amet."
    )
    val data = testOptionMetaData(Key, text)
    val orgGenerator = CliHelpGenerator.attributeColumnGenerator(CliHelpGenerator.AttrHelpText)

    val generator = CliHelpGenerator.wrapColumnGenerator(orgGenerator, 60)
    generator(data) should be(ExpResult)
  }

  it should "provide a ColumnGenerator for the option name" in {
    val data = testOptionMetaData(Key, HelpText)

    val generator = CliHelpGenerator.optionNameColumnGenerator()
    generator(data) should contain only (CliHelpGenerator.DefaultOptionPrefix + Key)
  }

  it should "support customizing the prefixes for the option name column generator" in {
    val Prefix = "/"
    val data = testOptionMetaData(Key, HelpText)

    val generator = CliHelpGenerator.optionNameColumnGenerator(Prefix)
    generator(data) should contain only (Prefix + Key)
  }

  it should "provide a ColumnGenerator for the multiplicity of command line options" in {
    val Multiplicity = "many"
    val attributes = Map(CliHelpGenerator.AttrMultiplicity -> Multiplicity)
    val data = OptionMetaData(Key, OptionAttributes(attributes))

    val generator = CliHelpGenerator.multiplicityColumnGenerator
    generator(data) should contain only Multiplicity
  }

  it should "provide a multiplicity ColumnGenerator that uses the correct default multiplicity" in {
    val data = testOptionMetaData(1)

    val generator = CliHelpGenerator.multiplicityColumnGenerator
    generator(data) should contain only Multiplicity.UnspecifiedMultiplicityString
  }

  it should "provide a special sort function for input parameters" in {
    val helpContext = createHelpContext()
      .addInputParameter(1, Some("source"), None)
      .addInputParameter(2, Some("destination"), None)
      .addInputParameter(3, Some("flags"), None)
    val ExpResult = List(OptionMetaData("source", helpContext.options("source")),
      OptionMetaData("destination", helpContext.options("destination")),
      OptionMetaData("flags", helpContext.options("flags")))

    val result = CliHelpGenerator.inputParamSortFunc(helpContext)(helpContext.optionMetaData.toSeq)
    result should be(ExpResult)
  }

  it should "handle non-input parameters in the special sort function" in {
    val helpContext = createHelpContext()
      .addInputParameter(1, Some("source"), None)
      .addInputParameter(2, Some("destination"), None)
      .addOption(Key, Some(HelpText))
    val ExpResult = List(OptionMetaData("source", helpContext.options("source")),
      OptionMetaData("destination", helpContext.options("destination")),
      OptionMetaData(Key, helpContext.options(Key)))

    val result = CliHelpGenerator.inputParamSortFunc(helpContext)(helpContext.optionMetaData.toSeq)
    result should be(ExpResult)
  }

  it should "provide a filter function that filters for options" in {
    val Key2 = "otherOptionKey"
    val helpContext = createHelpContext()
      .addOption(Key, Some(HelpText))
      .addInputParameter(1, Some("ignored"), None)
      .addOption(Key2, Some("other help"))
    val ExpResult = List(OptionMetaData(Key2, helpContext.options(Key2)),
      OptionMetaData(Key, helpContext.options(Key)))

    val result = helpContext.optionMetaData.filter(CliHelpGenerator.OptionsFilterFunc)
    result should contain theSameElementsAs ExpResult
  }

  it should "provide a filter function that filters for input parameters" in {
    val Key2 = "testInput"
    val helpContext = createHelpContext()
      .addInputParameter(1, Some(Key), None)
      .addOption("ignored", None)
      .addInputParameter(2, Some(Key2), Some("text"))
    val ExpResult = List(OptionMetaData(Key2, helpContext.options(Key2)),
      OptionMetaData(Key, helpContext.options(Key)))

    val result = helpContext.optionMetaData.filter(CliHelpGenerator.InputParamsFilterFunc)
    result should contain theSameElementsAs ExpResult
  }

  it should "provide a filter function that accepts elements from a given group" in {
    val Group = "TheGroup"
    val helpContext = createHelpContext()
      .addOption("ignored", Some("test"))
      .startGroup(Group)
      .addOption(Key, Some(HelpText))
      .endGroup()
      .startGroup("otherGroup")
      .addOption("ignored2", None)
    val ExpResult = OptionMetaData(Key, helpContext.options(Key))

    val result = helpContext.optionMetaData.filter(CliHelpGenerator.groupFilterFunc(Group))
    result should contain only ExpResult
  }

  it should "provide a filter function that accepts elements belonging to multiple groups" in {
    val Group1 = "IncludeGroup1"
    val Group2 = "IncludeGroup2"
    val helpContext = createHelpContext()
      .addOption("ignored1", Some("ignored help 1"))
      .startGroup(Group2)
      .addOption("ignored2", None)
      .startGroup(Group1)
      .addOption(Key, Some(HelpText))
    val ExpResult = OptionMetaData(Key, helpContext.options(Key))

    val result = helpContext.optionMetaData.filter(CliHelpGenerator.groupFilterFunc(Group1, Group2))
    result should contain only ExpResult
  }

  it should "provide a filter function that accepts elements not assigned to any group" in {
    val helpContext = createHelpContext()
      .addOption(Key, Some(HelpText))
      .startGroup("anyGroup")
      .addOption("ignored", Some("test"))
    val ExpResult = OptionMetaData(Key, helpContext.options(Key))

    val result = helpContext.optionMetaData.filter(CliHelpGenerator.UnassignedGroupFilterFunc)
    result should contain only ExpResult
  }

  it should "allow combining filters with AND semantics" in {
    val helpContext = createHelpContext()
      .addOption(Key, Some(HelpText))
      .addInputParameter(1, None, None)
      .startGroup("anyGroup")
      .addOption("ignored", Some("foo"))
    val ExpResult = OptionMetaData(Key, helpContext.options(Key))

    val filter = CliHelpGenerator.andFilter(CliHelpGenerator.OptionsFilterFunc,
      CliHelpGenerator.UnassignedGroupFilterFunc)
    val result = helpContext.optionMetaData.filter(filter)
    result should contain only ExpResult
  }

  it should "allow combining filters with OR semantics" in {
    val KeyInput = "myInput"
    val Group = "ImportantGroup"
    val helpContext = createHelpContext()
      .addOption("ignored", Some("foo"))
      .addInputParameter(1, Some(KeyInput), None)
      .startGroup(Group)
      .addOption(Key, Some(HelpText))
    val ExpResult = List(OptionMetaData(KeyInput, helpContext.options(KeyInput)),
      OptionMetaData(Key, helpContext.options(Key)))

    val filter = CliHelpGenerator.orFilter(CliHelpGenerator.InputParamsFilterFunc,
      CliHelpGenerator.groupFilterFunc(Group))
    val result = helpContext.optionMetaData.filter(filter)
    result should contain theSameElementsAs ExpResult
  }

  it should "support negating a filter" in {
    val helpContext = createHelpContext()
      .addOption("ignored", Some("test"))
      .startGroup("anyGroup")
      .addOption(Key, Some(HelpText))
    val ExpResult = OptionMetaData(Key, helpContext.options(Key))
    val filter = CliHelpGenerator.negate(CliHelpGenerator.UnassignedGroupFilterFunc)

    val result = helpContext.optionMetaData.filter(filter)
    result should contain only ExpResult
  }

  it should "generate the overview of an input parameter with multiplicity 1..1" in {
    val helpContext = addInputParameter(createHelpContext(), "1..1")

    val result = CliHelpGenerator.generateInputParamsOverview(helpContext)
    result should contain only "<" + Key + ">"
  }

  it should "generate the overview of an input parameter if the upper bound is > than the lower bound" in {
    val helpContext = addInputParameter(createHelpContext(), "1..3")
    val ExpResult = s"<${Key}1> [...<${Key}3>]"

    val result = CliHelpGenerator.generateInputParamsOverview(helpContext)
    result should contain only ExpResult
  }

  it should "generate the overview of an input parameter if the upper bound is the lower bound + 1" in {
    val helpContext = addInputParameter(createHelpContext(), "1..2")
    val ExpResult = s"<${Key}1> [<${Key}2>]"

    val result = CliHelpGenerator.generateInputParamsOverview(helpContext)
    result should contain only ExpResult
  }

  it should "generate the overview of an input parameter if the upper bound is unrestricted" in {
    val helpContext = addInputParameter(createHelpContext(), "1..*")
    val ExpResult = s"<$Key> [...]"

    val result = CliHelpGenerator.generateInputParamsOverview(helpContext)
    result should contain only ExpResult
  }

  it should "generate the overview of an input parameter if the lower bound is > 2" in {
    val helpContext = addInputParameter(createHelpContext(), "3..*")
    val ExpResult = s"<${Key}1>...<${Key}3> [...]"

    val result = CliHelpGenerator.generateInputParamsOverview(helpContext)
    result should contain only ExpResult
  }

  it should "generate the overview of an input parameter if the lower bound is 2" in {
    val helpContext = addInputParameter(createHelpContext(), "2..3")
    val ExpResult = s"<${Key}1> <${Key}2> [<${Key}3>]"

    val result = CliHelpGenerator.generateInputParamsOverview(helpContext)
    result should contain only ExpResult
  }

  it should "generate the overview of an optional input parameter" in {
    val helpContext = addInputParameter(createHelpContext(), "0..*")
    val ExpResult = s"[<$Key> ...]"

    val result = CliHelpGenerator.generateInputParamsOverview(helpContext)
    result should contain only ExpResult
  }

  it should "generate the overview for multiple input parameters" in {
    val Key2 = "source"
    val helpContext = addInputParameter(
      addInputParameter(createHelpContext(), "1..1", key = Key2),
      "1..*", index = 2)
    val ExpResult = List(s"<$Key2>", s"<$Key> [...]")

    val result = CliHelpGenerator.generateInputParamsOverview(helpContext)
    result should contain theSameElementsInOrderAs ExpResult
  }

  it should "generate the overview of an input parameter if no multiplicity is available" in {
    val helpContext = createHelpContext()
      .addInputParameter(1, Some(Key), None)
    val ExpResult = s"[<$Key> ...]"

    val result = CliHelpGenerator.generateInputParamsOverview(helpContext)
    result should contain only ExpResult
  }

  it should "support customizing the symbols for the overview of input parameters" in {
    val symbols = InputParamOverviewSymbols(optionalPrefix = "{", optionalSuffix = "}",
      keyPrefix = "(", keySuffix = ")", ellipsis = "_")
    val helpContext = addInputParameter(createHelpContext(), "3..*")
    val ExpResult = s"(${Key}1)_(${Key}3) {_}"

    val result = CliHelpGenerator.generateInputParamsOverview(helpContext, symbols)
    result should contain only ExpResult
  }
}
