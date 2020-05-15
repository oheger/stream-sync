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

import com.github.sync.cli.ParameterExtractor.{CliExtractor, ParameterExtractionException}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success, Try}

object ParameterManagerSpec {
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
  private val TestExtractor = ParameterManager.wrapTryExtractor(CliExtractor(context => {
    if (context.reader == DefaultConsoleReader) {
      val nextContext = context.update(context.parameters.keyAccessed(TestOptionKey),
        context.helpContext.addOption(TestOptionKey, Some(HelpTestOption)))
      (context.parameters.parametersMap, nextContext)
    } else (Map.empty, context)
  }))
}

/**
  * Test class for ''ParameterManager''.
  */
class ParameterManagerSpec extends AnyFlatSpec with Matchers {

  import ParameterManagerSpec._

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
    val (res, context) = triedResult(ParameterManager.processCommandLine(args, TestExtractor,
      checkUnconsumedParameters = false))
    res should be(ExpParamsMap)
    context.parameters.parametersMap should be(ExpParamsMap)
    context.parameters.accessedParameters should contain only TestOptionKey
    context.helpContext.options(TestOptionKey).attributes(CliHelpGenerator.AttrHelpText) should be(HelpTestOption)
  }

  it should "check for unconsumed parameters" in {
    val args = List("--" + TestOptionKey, TestOptionValue, "--foo", "value")

    val exception = failedResult(ParameterManager.processCommandLine(args, TestExtractor))
    exception.failures should have size 1
    exception.failures.head.key should be("foo")
  }

  it should "handle a failed extractor" in {
    val args = List("--" + TestOptionKey, TestOptionValue)
    val extractor = ParameterExtractor.optionValue(TestOptionKey)
      .toInt

    val exception = failedResult(ParameterManager.processCommandLine(args, extractor))
    exception.failures should have size 1
    exception.failures.head.key should be(TestOptionKey)
    exception.failures.head.message should include("NumberFormatException")
    exception.parameterContext.parameters.parametersMap.keys should contain(TestOptionKey)
    val helpContext = exception.parameterContext.helpContext
    helpContext.options.keys should contain(TestOptionKey)
  }

  it should "combine failures of the extractor with failures for unconsumed parameters" in {
    val args = List("--" + TestOptionKey, TestOptionValue, "--unknownOption", "shouldFail")
    val ext1 = ParameterExtractor.optionValue(TestOptionKey).toInt
    val ext2 = ParameterExtractor.optionValue("missing").single.mandatory
    val extractor = for {
      v1 <- ext1
      v2 <- ext2
    } yield ParameterExtractor.createRepresentation(v1, v2) {
      (_, _)
    }

    val exception = failedResult(ParameterManager.processCommandLine(args, extractor))
    exception.failures.map(_.key) should contain only(TestOptionKey, "missing", "unknownOption")
  }

  it should "add all failures to the help context in the resulting exception" in {
    val args = List("--" + TestOptionKey, TestOptionValue, "--unknownOption", "shouldFail")
    val extractor = ParameterExtractor.optionValue(TestOptionKey).toInt

    val exception = failedResult(ParameterManager.processCommandLine(args, extractor))
    val helpContext = exception.parameterContext.helpContext
    helpContext.options(TestOptionKey).attributes.keys should contain(CliHelpGenerator.AttrErrorMessage)
  }

  it should "support customizing the parsing function" in {
    val args = List("/" + TestOptionKey, TestOptionValue)
    val key = TestOptionKey.toLowerCase(Locale.ROOT)
    val extractor = ParameterExtractor.optionValue(key).single.mandatory
    val prefixes = ParameterParser.OptionPrefixes("/")
    val keyExtractor = prefixes.extractorFunc andThen (s => s.toLowerCase(Locale.ROOT))

    val parseFunc = ParameterManager.parsingFunc(optionFunc = prefixes.isOptionFunc,
      keyExtractor = keyExtractor)
    val (res, _) = triedResult(ParameterManager.processCommandLine(args, extractor, parser = parseFunc))
    res should be(TestOptionValue)
  }

  it should "support loading parameter files" in {
    val FileOption = "file"
    val args = List("--" + TestOptionKey, TestOptionValue, "--" + FileOption, "someFile.txt")

    val parseFunc = ParameterManager.parsingFunc(optFileOption = Some(FileOption))
    val result = ParameterManager.processCommandLine(args, TestExtractor, parseFunc,
      checkUnconsumedParameters = false)
    // TODO check details of resulting exception when available
    result.isFailure shouldBe true
  }
}
