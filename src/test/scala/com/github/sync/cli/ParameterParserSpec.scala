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

import java.io.IOException
import java.nio.file.Path

import com.github.sync.FileTestHelper
import com.github.sync.cli.ParameterParser.{OptionPrefixes, ParameterParseException}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success, Try}

object ParameterParserSpec {
  /** Name of an option that references parameter files. */
  private val FileOption = "param-file"
}

/**
  * Test class for ''ParameterParser''.
  */
class ParameterParserSpec extends AnyFlatSpec with Matchers with FileTestHelper {

  import ParameterParserSpec._

  /**
    * Extracts the map with parameters from the given tried result; fails for
    * other results.
    *
    * @param result the result
    * @return the map with parameters
    */
  private def extractParametersMap(result: Try[ParameterParser.ParametersMap]): ParameterParser.ParametersMap =
    result match {
      case Success(value) => value
      case r => fail("Unexpected result: " + r)
    }

  /**
    * Invokes the parameter parser on the given sequence with arguments and
    * expects a successful result. The resulting map is returned.
    *
    * @param args the sequence with arguments
    * @return the resulting parameters map
    */
  private def parseParametersSuccess(args: Seq[String]): ParameterParser.ParametersMap =
    extractParametersMap(ParameterParser.parseParameters(args, optFileOption = Some(FileOption)))

  /**
    * Invokes the parameter parser on the given sequence with arguments and
    * expects a failure result. The causing exception is returned.
    *
    * @param args the sequence with arguments
    * @return the exception causing the failure
    */
  private def parseParametersFailure(args: Seq[String]): Throwable =
    ParameterParser.parseParameters(args, optFileOption = Some(FileOption)) match {
      case Failure(exception) => exception
      case r => fail("Unexpected result: " + r)
    }

  /**
    * Creates a temporary file that contains the given parameter strings.
    *
    * @param args the parameters to store in the file
    * @return the path to the newly created file
    */
  private def createParameterFile(args: String*): Path =
    createDataFile(parameterFileContent(args: _*))

  /**
    * Generates the content of a parameters file from the given parameter
    * strings.
    *
    * @param args the parameters to store in the file
    * @return the content of the parameter file as string
    */
  private def parameterFileContent(args: String*): String =
    args.mkString("\r\n")

  /**
    * Adds a parameter to read the given file to a parameter list.
    *
    * @param path    the path to the file to be read
    * @param argList the original parameter list
    * @return the parameter list with the file parameter added
    */
  private def appendFileParameter(path: Path, argList: List[String]): List[String] =
    "--" + FileOption :: path.toString :: argList

  "OptionPrefixes" should "return a function to check for an option" in {
    val Options = List("--foo", "-test", "/switch", "<<<<yes", "//double")
    val NonOptions = List("+o1", "o2", "*o3")
    val prefixes = OptionPrefixes("-", "/", "<<<<", "--")

    val optionFunc = prefixes.isOptionFunc
    Options.forall(optionFunc) shouldBe true
    NonOptions.find(optionFunc) should be(None)
  }

  it should "return a function that removes an option prefix" in {
    val PrefixStrings = List("--", "/")
    val Key = "the-option"
    val prefixes = OptionPrefixes(PrefixStrings)

    val extractor = prefixes.extractorFunc
    PrefixStrings foreach { p =>
      val arg = p + Key
      extractor(arg) should be(Key)
    }
  }

  it should "return an extractor function that handles invalid keys" in {
    val Key = "+-unknownOption"
    val prefixes = OptionPrefixes("--", "++")

    prefixes.extractorFunc(Key) should be(Key)
  }

  it should "return an extractor function that can deal with option prefixes of different lengths" in {
    val Key = "option"
    val prefixes = OptionPrefixes(List("-", "--"))

    val extractor = prefixes.extractorFunc
    extractor("--" + Key) should be(Key)
  }

  "ParameterParser" should "parse an empty sequence of arguments" in {
    val params = parseParametersSuccess(Nil)

    params should have size 0
  }

  it should "correctly parse non-option parameters" in {
    val args = List("uri1", "uri2")
    val expArgMap = Map(ParameterParser.InputOption -> args)

    val params = parseParametersSuccess(args)
    params should be(expArgMap)
  }

  it should "correctly parse arguments with options" in {
    val args = Array("--opt1", "opt1Val1", "--opt2", "opt2Val1", "--opt1", "opt1Val2")
    val expArgMap = Map("opt1" -> List("opt1Val1", "opt1Val2"),
      "opt2" -> List("opt2Val1"))

    val params = parseParametersSuccess(args)
    params should be(expArgMap)
  }

  it should "ignore an option that is the last argument" in {
    val undefOption = "--undefinedOption"
    val args = List("--opt1", "optValue", undefOption)
    val expArgsMap = Map("opt1" -> List("optValue"))

    val params = parseParametersSuccess(args)
    params should be(expArgsMap)
  }

  it should "ignore an option that is the last argument, but keep its other values" in {
    val Key = "strangeOption"
    val TestOption = "--" + Key
    val args = List(TestOption, "v1", TestOption, "v2", TestOption)
    val expArgsMap = Map(Key -> List("v1", "v2"))

    val params = parseParametersSuccess(args)
    params should be(expArgsMap)
  }

  it should "support different option prefixes" in {
    val args = List("/TestOption", "TestValue", "--FOO", "BAR", "testUri")
    val expArgMap = Map("TestOption" -> List("TestValue"),
      "FOO" -> List("BAR"),
      ParameterParser.InputOption -> List("testUri"))
    val optionPrefixes = OptionPrefixes("/", "--")

    val params = extractParametersMap(ParameterParser.parseParameters(args,
      isOptionFunc = optionPrefixes.isOptionFunc, keyExtractor = optionPrefixes.extractorFunc))
    params should be(expArgMap)
  }

  it should "add the content of parameter files to command line options" in {
    val OptionName1 = "foo"
    val OptionName2 = "test"
    val Opt1Val1 = "bar"
    val Opt1Val2 = "baz"
    val Opt2Val = "true"
    val uri1 = "testUri1"
    val uri2 = "testUri2"
    val args = appendFileParameter(createParameterFile("--" + OptionName1, Opt1Val1, uri1),
      appendFileParameter(createParameterFile("--" + OptionName2, Opt2Val),
        "--" + OptionName1 :: Opt1Val2 :: uri2 :: Nil))

    val argsMap = parseParametersSuccess(args)
    argsMap(OptionName1) should contain only(Opt1Val1, Opt1Val2)
    argsMap(OptionName2) should contain only Opt2Val
    argsMap.keys should not contain FileOption
  }

  it should "parse parameter files defined in another parameter file" in {
    val OptionName1 = "top-level"
    val Option1Value = "onCommandLine"
    val OptionName2 = "level1"
    val Option2Value = "inFirstFile"
    val OptionName3 = "deep"
    val Option3Value = "inNestedFile"
    val nestedFile = createParameterFile("--" + OptionName3, Option3Value)
    val args = appendFileParameter(
      createParameterFile("--" + FileOption, nestedFile.toString,
        "--" + OptionName2, Option2Value), "--" + OptionName1 :: Option1Value :: Nil)
    val expArgs = Map(OptionName1 -> List(Option1Value),
      OptionName2 -> List(Option2Value),
      OptionName3 -> List(Option3Value))

    val argsMap = parseParametersSuccess(args)
    argsMap should be(expArgs)
  }

  it should "deal with cyclic references in parameter files" in {
    val file1 = createFileReference()
    val file3 = createParameterFile("--" + FileOption, file1.toString, "--op3", "v3")
    val file2 = createParameterFile("--" + FileOption, file3.toString, "--op2", "v2")
    writeFileContent(file1, parameterFileContent("--" + FileOption, file2.toString,
      "--op1", "v1", "--" + FileOption, file2.toString))
    val args = appendFileParameter(file1, Nil)
    val expArgs = Map("op1" -> List("v1"), "op2" -> List("v2"), "op3" -> List("v3"))

    val argsMap = parseParametersSuccess(args)
    argsMap should be(expArgs)
  }

  it should "ignore empty lines in parameter files" in {
    val args = appendFileParameter(createParameterFile("--foo", "bar", "", "--foo", "baz"),
      "--test" :: "true" :: Nil)

    val argsMap = parseParametersSuccess(args)
    argsMap.keys should contain only("foo", "test")
  }

  it should "handle an exception when reading a parameter file" in {
    val FileName = "non_existing_file.txt"
    val args = List("--op1", "don't care", "--" + FileOption, FileName)
    val expArgs = Map("op1" -> List("don't care"))

    val exception = parseParametersFailure(args)
    exception match {
      case e: ParameterParseException =>
        e.getMessage should include(FileName)
        e.getCause shouldBe a[IOException]
        e.fileOption should be(FileOption)
        e.currentParameters should be(expArgs)
      case e => fail("Unexpected exception: " + e)
    }
  }
}
