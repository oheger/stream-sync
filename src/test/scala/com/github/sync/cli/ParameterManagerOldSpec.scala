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

import com.github.sync.cli.ParameterExtractorOld.{CliExtractor, ParameterExtractionException}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success, Try}

object ParameterManagerOldSpec {
  /** A key for a test option. */
  private val TestOptionKey = "theTestOption"

  /** Test value of the test option. */
  private val TestOptionValue = "testValue"

  /** A help text for the test option. */
  private val HelpTestOption = "This is the help text for the test option."

  /**
    * A test extractor that can be used by test cases. The extractor returns
    * the current parameters map as result. It also adds the test option to the
    * map of accessed options and to the help context.
    */
  private val TestExtractor = ParameterManagerOld.wrapTryExtractor(CliExtractor(context => {
    if (context.reader == DefaultConsoleReaderOld) {
      val nextContext = context.update(context.parameters.keyAccessed(TestOptionKey),
        context.helpContext.addOption(TestOptionKey, Some(HelpTestOption)))
      (context.parameters.parametersMap, nextContext)
    } else (Map.empty, context)
  }))
}

/**
  * Test class for ''ParameterManager''.
  */
class ParameterManagerOldSpec extends AnyFlatSpec with Matchers {

  import ParameterManagerOldSpec._

  /**
    * Checks whether the given ''Try'' has a success result and returns it.
    *
    * @param triedRes the tried result
    * @tparam A the type of the result
    * @return the success result
    */
  private def triedResult[A](triedRes: Try[A]): A =
    triedRes match {
      case Success(value) => value
      case r => fail("Unexpected result: " + r)
    }

  /**
    * Checks whether the given ''Try'' has failed with a parameter extraction
    * exception and returns it.
    *
    * @param triedRes the tried result
    * @return the exception that has been extracted
    */
  private def failedResult(triedRes: Try[_]): ParameterExtractionException =
    triedRes match {
      case Failure(exception: ParameterExtractionException) => exception
      case r => fail("Unexpected result: " + r)
    }

  "ParameterManager" should "process a simple command line with defaults" in {
    val args = List("--" + TestOptionKey, TestOptionValue, "--foo", "bar", "--foo", "baz")
    val ExpParamsMap = Map(TestOptionKey -> List(TestOptionValue),
      "foo" -> List("bar", "baz"))

    import scala.language.existentials
    val (res, context) = triedResult(ParameterManagerOld.processCommandLine(args, TestExtractor,
      checkUnconsumedParameters = false))
    res should be(ExpParamsMap)
    context.parameters.parametersMap should be(ExpParamsMap)
    context.parameters.accessedParameters should contain only TestOptionKey
    context.helpContext.options(TestOptionKey).attributes(CliHelpGenerator.AttrHelpText) should be(HelpTestOption)
  }

  it should "check for unconsumed parameters" in {
    val args = List("--" + TestOptionKey, TestOptionValue, "--foo", "value")

    val exception = failedResult(ParameterManagerOld.processCommandLine(args, TestExtractor))
    exception.failures should have size 1
    exception.failures.head.key should be("foo")
  }

  it should "handle a failed extractor" in {
    val args = List("--" + TestOptionKey, TestOptionValue)
    val extractor = ParameterExtractorOld.optionValue(TestOptionKey)
      .toInt

    val exception = failedResult(ParameterManagerOld.processCommandLine(args, extractor))
    exception.failures should have size 1
    exception.failures.head.key should be(TestOptionKey)
    exception.failures.head.message should include("NumberFormatException")
    exception.parameterContext.parameters.parametersMap.keys should contain(TestOptionKey)
    val helpContext = exception.parameterContext.helpContext
    helpContext.options.keys should contain(TestOptionKey)
  }

  it should "combine failures of the extractor with failures for unconsumed parameters" in {
    val args = List("--" + TestOptionKey, TestOptionValue, "--unknownOption", "shouldFail")
    val ext1 = ParameterExtractorOld.optionValue(TestOptionKey).toInt
    val ext2 = ParameterExtractorOld.optionValue("missing").single.mandatory
    val extractor = for {
      v1 <- ext1
      v2 <- ext2
    } yield ParameterExtractorOld.createRepresentation(v1, v2) {
      (_, _)
    }

    val exception = failedResult(ParameterManagerOld.processCommandLine(args, extractor))
    exception.failures.map(_.key) should contain only(TestOptionKey, "missing", "unknownOption")
  }

  it should "add all failures to the help context in the resulting exception" in {
    val args = List("--" + TestOptionKey, TestOptionValue, "--unknownOption", "shouldFail")
    val extractor = ParameterExtractorOld.optionValue(TestOptionKey).toInt

    val exception = failedResult(ParameterManagerOld.processCommandLine(args, extractor))
    val helpContext = exception.parameterContext.helpContext
    helpContext.options(TestOptionKey).attributes.keys should contain(CliHelpGenerator.AttrErrorMessage)
  }

  it should "support customizing the parsing function" in {
    val args = List("/" + TestOptionKey, TestOptionValue)
    val key = TestOptionKey.toLowerCase(Locale.ROOT)
    val extractor = ParameterExtractorOld.optionValue(key).single.mandatory
    val prefixes = ParameterParserOld.OptionPrefixes("/")
    val keyExtractor = prefixes.extractorFunc andThen (s => s.toLowerCase(Locale.ROOT))

    val parseFunc = ParameterManagerOld.parsingFunc(optionFunc = prefixes.isOptionFunc,
      keyExtractor = keyExtractor)
    val (res, _) = triedResult(ParameterManagerOld.processCommandLine(args, extractor, parser = parseFunc))
    res should be(TestOptionValue)
  }

  it should "support loading parameter files and handle corresponding exceptions" in {
    val FileOption = "file"
    val FileName = "someFile.txt"
    val args = List("--" + TestOptionKey, TestOptionValue, "--" + FileOption, FileName)
    val extractor = ParameterExtractorOld.optionValue(TestOptionKey, help = Some(HelpTestOption))

    val parseFunc = ParameterManagerOld.parsingFunc(optFileOption = Some(FileOption))
    val exception = failedResult(ParameterManagerOld.processCommandLine(args, extractor, parseFunc,
      checkUnconsumedParameters = false))
    exception.failures should have size 1
    val failure = exception.failures.head
    failure.key should be(FileOption)
    failure.message should include(FileName)
    val context = exception.parameterContext
    context.parameters.parametersMap.keys should contain(TestOptionKey)
    val helpContext = context.helpContext
    val testOptionAttrs = helpContext.options(TestOptionKey)
    testOptionAttrs.attributes(CliHelpGenerator.AttrHelpText) should be(HelpTestOption)
    val fileOptionAttrs = helpContext.options(FileOption)
    fileOptionAttrs.attributes.keys should contain(CliHelpGenerator.AttrErrorMessage)
  }
}
