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

import com.github.sync.cli.CliHelpGenerator.CliHelpContext
import com.github.sync.cli.ParameterExtractor.{OptionValue, ParameterContext, Parameters}
import com.github.sync.cli.ParameterParser.ParametersMap
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.SortedSet
import scala.util.{Failure, Random, Success, Try}

object ParameterExtractorSpec {
  /** A test option key. */
  private val Key = "--my-test-option"

  /** A result of a test CLI extractor. */
  private val ExtractorResult = 42

  /** The test value list assigned to the test key.  */
  private val ResultValues = List(ExtractorResult.toString)

  /** Test data for input values passed on the command line. */
  private val InputValues = List("1", "2", "3")

  /** A test option value containing the test result. */
  private val ResultOptionValue: OptionValue[String] = Success(ResultValues)

  /** A test Parameters object for testing CLI extractors. */
  private val TestParameters: Parameters = Map(Key -> ResultValues)

  /** A test parameters object that contains input parameters. */
  private val TestParametersWithInputs: Parameters = TestParameters.parametersMap +
    (ParameterParser.InputOption -> InputValues)

  /** Another test Parameters object representing updated parameters. */
  private val NextParameters = Parameters(Map("bar" -> List("v2", "v3")), Set("x", "y"))

  /** A test ParameterContext object. */
  private val TestContext = ParameterContext(TestParameters,
    new CliHelpGenerator.CliHelpContext(Map.empty, SortedSet.empty, None, Nil),
    DummyConsoleReader)
}

/**
  * Test class for ''ParameterExtractor''.
  */
class ParameterExtractorSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import ParameterExtractor._
  import ParameterExtractorSpec._

  /**
    * Tries to cast the given ''Throwable'' to a
    * ''ParameterExtractionException'' or fails if this is not possible.
    *
    * @param exception the exception
    * @return the ''ParameterExtractionException''
    */
  private def extractionException(exception: Throwable): ParameterExtractionException =
    exception match {
      case pe: ParameterExtractionException => pe
      case e => fail("Unexpected exception: " + e)
    }

  /**
    * Expects that the given ''Try'' is a failure wrapping a
    * ''ParameterExtractionException''. This exception is returned.
    *
    * @param res the tried result
    * @tparam T the result type of the ''Try''
    * @return the exception that was extracted
    */
  private def expectExtractionException[T](res: Try[T]): ParameterExtractionException =
    res match {
      case Failure(exception) => extractionException(exception)
      case r => fail("Unexpected result: " + r)
    }

  /**
    * Checks the properties of an ''ExtractionFailure''.
    *
    * @param failure     the failure object to be checked
    * @param expKey      the expected key
    * @param expParams   the expected parameters map
    * @param expMsgParts strings to be contained in the message
    * @return the ''ExtractionFailure'' unchanged
    */
  private def checkExtractionFailure(failure: ExtractionFailure, expKey: String = Key,
                                     expParams: ParametersMap = TestParameters.parametersMap)
                                    (expMsgParts: String*): ExtractionFailure = {

    failure.key should be(expKey)
    failure.context.parameters.parametersMap should be(expParams)
    expMsgParts foreach { part =>
      failure.message should include(part)
    }
    failure
  }

  /**
    * Checks the ''ExtractionFailure'' of a ''ParameterExtractionException''.
    * It is expected that the exception contains only a single failure. The
    * properties of this failure are checked.
    *
    * @param exception   the exception to be checked
    * @param expKey      the expected key
    * @param expParams   the expected parameters map
    * @param expMsgParts strings to be contained in the message
    * @return the ''ExtractionFailure'' unchanged
    */
  private def checkExtractionException(exception: ParameterExtractionException, expKey: String = Key,
                                       expParams: ParametersMap = NextParameters.parametersMap)
                                      (expMsgParts: String*): ExtractionFailure = {
    exception.failures should have size 1
    checkExtractionFailure(exception.failures.head, expKey, expParams)(expMsgParts: _*)
  }

  "Parameters" should "be creatable from a parameters map" in {
    val paramMap = Map("foo" -> List("v1", "v2"), "bar" -> List("v3"))

    val params: Parameters = paramMap
    params.parametersMap should be(paramMap)
    params.accessedParameters should have size 0
    params.allKeysAccessed shouldBe false
  }

  it should "report an empty map as fully accessed" in {
    val params = Parameters(Map.empty, Set.empty)

    params.allKeysAccessed shouldBe true
  }

  it should "report parameters as fully consumed if the set contains more keys" in {
    val params = Parameters(Map("foo" -> List("v1")), Set("foo", "bar"))

    params.allKeysAccessed shouldBe true
  }

  it should "return the keys that have not been accessed" in {
    val paramMap = Map("foo" -> List("v1", "v2"), "bar" -> List("v3"), "baz" -> List("v4"))
    val params = Parameters(paramMap, Set.empty)

    val params2 = params.keyAccessed("baz")
    params2.notAccessedKeys should contain only("foo", "bar")
  }

  it should "support marking multiple keys as accessed" in {
    val paramMap = Map("foo" -> List("v1", "v2"), "bar" -> List("v3"), "baz" -> List("v4"),
      "blub" -> List("v5"))
    val params = Parameters(paramMap, Set.empty)

    val params2 = params.keysAccessed(List("baz", "foo"))
    params2.notAccessedKeys should contain only("bar", "blub")
  }

  it should "not create a new object if an already accessed key is marked as accessed" in {
    val params = Parameters(Map("foo" -> List("v")), Set("bar"))

    val params2 = params.keyAccessed("bar")
    params2 should be theSameInstanceAs params
  }

  "ParameterExtractionException" should "not allow creating an instance without failures" in {
    intercept[IllegalArgumentException] {
      ParameterExtractionException(Nil)
    }
  }

  it should "generate a message from the failures" in {
    val failure1 = ExtractionFailure(Key, "Message 1", TestContext)
    val failure2 = ExtractionFailure(Key + "_other", "Other message", TestContext)
    val ExpMsg = failure1.key + ": " + failure1.message + ", " +
      failure2.key + ": " + failure2.message

    val exception = ParameterExtractionException(List(failure1, failure2))
    exception.getMessage should be(ExpMsg)
  }

  /**
    * Creates a generic test Cli extractor that checks the context passed to it
    * and returns a defined result.
    *
    * @param value          the value to be returned by the extractor
    * @param expParameters  the expected parameters
    * @param nextParameters the updated parameters
    * @param expReader      the expected console reader
    * @tparam A the type of the value
    * @return the test extractor
    */
  private def testExtractor[A](value: A, expParameters: Parameters = TestParameters,
                               nextParameters: Parameters = NextParameters)
                              (implicit expReader: ConsoleReader): CliExtractor[A] = CliExtractor(context => {
    context.parameters should be(expParameters)
    context.reader should be(expReader)
    (value, context.update(nextParameters, context.helpContext))
  }, Some(Key))

  "ParameterExtractor" should "support running a CliExtractor" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val ext = testExtractor(ExtractorResult)

    val (res, next) = ParameterExtractor.runExtractor(ext, TestParameters)
    res should be(ExtractorResult)
    next.parameters should be(NextParameters)
  }

  it should "run an extractor yielding a Try if execution is successful" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val ext = testExtractor[Try[Int]](Success(ExtractorResult))

    ParameterExtractor.tryExtractor(ext, TestParameters) match {
      case Success((res, next)) =>
        res should be(ExtractorResult)
        next.parameters should be(NextParameters)
      case f => fail("Unexpected result: " + f)
    }
  }

  it should "run an extractor yielding a Try if execution fails" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val exception = new IllegalArgumentException("Wrong parameters")
    val ext = testExtractor[Try[Int]](Failure(exception))

    ParameterExtractor.tryExtractor(ext, TestParameters) match {
      case Failure(ex) =>
        ex should be(exception)
      case s => fail("Unexpected result: " + s)
    }
  }

  it should "wrap a function in a Try" in {
    val triedResult = ParameterExtractor.paramTry(TestContext, Key)(ExtractorResult)

    triedResult should be(Success(ExtractorResult))
  }

  it should "catch the exception thrown by a function and wrap it" in {
    val exception = new IOException("Fatal error")

    val triedResult = ParameterExtractor.paramTry[String](TestContext, Key)(throw exception)
    checkExtractionException(expectExtractionException(triedResult),
      expParams = TestParameters.parametersMap)("java.io.IOException - " + exception.getMessage)
  }

  it should "handle a ParameterExtractionException thrown within a Try in a special way" in {
    val exception = ParameterExtractionException(ExtractionFailure(Key, "Some error", TestContext))

    val triedResult = ParameterExtractor.paramTry[String](TestContext, Key)(throw exception)
    expectExtractionException(triedResult) should be theSameInstanceAs exception
  }

  it should "provide a constant extractor" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val extractor = ParameterExtractor.constantExtractor(ExtractorResult)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    res should be(ExtractorResult)
    next.parameters should be(TestParameters)
  }

  it should "provide an extractor returning a constant option value with only a single value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val extractor = ParameterExtractor.constantOptionValue(ExtractorResult.toString)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(TestParameters)
    res.get should contain only ExtractorResult.toString
  }

  it should "provide an extractor returning a constant option value with multiple values" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val items = List("foo", "bar", "baz", "more")
    val extractor = ParameterExtractor.constantOptionValue(items.head, items.tail: _*)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(TestParameters)
    res.get should be(items)
  }

  it should "provide a fallback extractor if the first extractor yields a value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val ext1 = testExtractor(ResultOptionValue)
    val ext2 = consoleReaderValue("someKey", password = true)
    val extractor = ParameterExtractor.withFallback(ext1, ext2)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res.get should be(ResultValues)
    Mockito.verifyZeroInteractions(consoleReader)
  }

  it should "provide a fallback extractor if the first extractor yields an empty value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val nextNextParameters = Parameters(Map("next" -> List("v4", "v5")), Set("x", "y", "z"))
    val ext1 = testExtractor[OptionValue[String]](Success(List.empty))
    val ext2 = testExtractor(ResultOptionValue, expParameters = NextParameters, nextParameters = nextNextParameters)
    val extractor = ParameterExtractor.withFallback(ext1, ext2)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(nextNextParameters)
    res.get should be(ResultValues)
  }

  it should "provide a fallback extractor if the first extractor yields a Failure" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val exception = new IllegalArgumentException("Invalid option")
    val optionValue: OptionValue[String] = Failure(exception)
    val ext1 = testExtractor(optionValue)
    val ext2 = constantExtractor(ResultOptionValue)
    val extractor = ParameterExtractor.withFallback(ext1, ext2)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(optionValue)
  }

  it should "provide an extractor to extract a single option value if there is exactly one value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val ext = testExtractor(ResultOptionValue)
    val extractor = ParameterExtractor.asSingleOptionValue(ext)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(Some(ExtractorResult.toString)))
  }

  it should "provide an extractor to extract a single option value if the value is undefined" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val EmptyValue: OptionValue[String] = Success(Nil)
    val ext = testExtractor(EmptyValue)
    val extractor = ParameterExtractor.asSingleOptionValue(ext)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(None))
  }

  it should "provide an extractor to extract a single option value if there are multiple values" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val MultiValue: OptionValue[String] = Success(List("v1", "v2"))
    val ext = testExtractor(MultiValue)
    val extractor = ParameterExtractor.asSingleOptionValue(ext)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    val paramEx = expectExtractionException(res)
    checkExtractionException(paramEx)("multiple values", MultiValue.toString)
  }

  it should "provide a mapping extractor that handles a failed result" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val FailedValue: OptionValue[String] = Failure(new Exception("Failed"))
    val ext = testExtractor(FailedValue)
    val extractor = ParameterExtractor.mapped(ext)(_.toInt)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(FailedValue)
  }

  it should "provide a mapping extractor that handles an empty result" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val EmptyResult: OptionValue[String] = Success(None)
    val ext = testExtractor(EmptyResult)
    val extractor = ParameterExtractor.mapped(ext)(_ => throw new IllegalArgumentException("Nope"))

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(EmptyResult)
  }

  it should "provide a mapping extractor that handles a defined result" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val Result: OptionValue[String] = Success(Some(ExtractorResult.toString))
    val ext = testExtractor(Result)
    val extractor = ParameterExtractor.mapped(ext)(_.toInt)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(List(ExtractorResult)))
  }

  it should "provide a mapping extractor that handles an exception thrown by the mapping function" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val Result: OptionValue[String] = Success(Some("Not a number!"))
    val ext = testExtractor(Result)
    val extractor = ParameterExtractor.mapped(ext)(_.toInt)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    checkExtractionException(expectExtractionException(res))(classOf[NumberFormatException].getName)
  }

  it should "provide a mapping extractor that passes the ParameterContext to the mapping function" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val IntValues = List(17, 21, 44, 127)
    val StrValues = IntValues map (_.toString)
    val Result: OptionValue[Int] = Success(IntValues)
    val ext = testExtractor(Result)
    val extractor = ParameterExtractor.mappedWithContext(ext) { (i, ctx) =>
      val res = i.toString
      val nextHelpCtx = ctx.helpContext.addOption(res, None)
      (res, ctx.copy(helpContext = nextHelpCtx))
    }

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(StrValues))
    next.helpContext.options.keys should contain allOf(StrValues.head, StrValues.tail.head, StrValues.drop(2): _*)
  }

  it should "provide a mapping extractor that collects multiple mapping errors" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val InvalidValues = List("xy", "noNumber", "1234abc")
    val ValidNumbers = List("17", "21", "44", "127")
    val random = new Random
    val Values = random.shuffle(ValidNumbers ::: InvalidValues)
    val Result: OptionValue[String] = Success(Values)
    val ext = testExtractor(Result)
    val extractor = ParameterExtractor.mapped(ext)(_.toInt)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    checkExtractionException(expectExtractionException(res))(InvalidValues: _*)
  }

  it should "provide a mapping extractor with fallbacks if the value is defined" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val Result: OptionValue[String] = Success(Some(ExtractorResult.toString))
    val ext = testExtractor(Result)
    val extractor = ParameterExtractor.mappedWithFallback(ext, 1, 2, 3)(_.toInt)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(List(ExtractorResult)))
  }

  it should "provide a mapping extractor with fallbacks if the value is undefined" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val extractor = ParameterExtractor.mappedWithFallback(ParameterExtractor.emptyExtractor[String],
      1, 2, 3)(_.toInt)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(TestParameters)
    res should be(Success(List(1, 2, 3)))
  }

  it should "provide an extractor that converts an option value to int" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val StrValue: OptionValue[String] = Try(Some(ExtractorResult.toString))
    val ext = testExtractor(StrValue)
    val extractor = ParameterExtractor.asIntOptionValue(ext)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(List(ExtractorResult)))
  }

  it should "provide an extractor that converts an option value to int and handles errors" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val NoIntValue = "not a valid number"
    val StrValue: OptionValue[String] = Try(Some(NoIntValue))
    val ext = testExtractor(StrValue)
    val extractor = ParameterExtractor.asIntOptionValue(ext)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    checkExtractionException(expectExtractionException(res))(classOf[NumberFormatException].getName, NoIntValue)
  }

  /**
    * Helper method for testing a boolean conversion.
    *
    * @param value     the original string option value
    * @param expResult the expected result
    */
  private def checkBooleanConversion(value: String, expResult: Boolean): Unit = {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val StrValue: OptionValue[String] = Try(Some(value))
    val ext = testExtractor(StrValue)
    val extractor = ParameterExtractor.asBooleanOptionValue(ext)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(List(expResult)))
  }

  it should "provide an extractor that converts an option to boolean if the result is true" in {
    checkBooleanConversion("true", expResult = true)
  }

  it should "provide an extractor that converts an option to boolean if the result is false" in {
    checkBooleanConversion("false", expResult = false)
  }

  it should "provide an extractor that converts an option to boolean ignoring case" in {
    checkBooleanConversion("TruE", expResult = true)
  }

  it should "provide an extractor that converts an option to boolean and handles errors" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val StrValue = "not a valid boolean"
    val ValueOption: OptionValue[String] = Try(Some(StrValue))
    val ext = testExtractor(ValueOption)
    val extractor = ParameterExtractor.asBooleanOptionValue(ext)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    checkExtractionException(expectExtractionException(res))(StrValue)
  }

  it should "provide an extractor that converts string values to lower case" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val ValueOption: OptionValue[String] = Try(Some("This Is a TEST String"))
    val ext = testExtractor(ValueOption)
    val extractor = ParameterExtractor.asLowerCase(ext)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(List("this is a test string")))
  }

  it should "provide an extractor that converts string values to upper case" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val ValueOption: OptionValue[String] = Try(Some("This Is a TEST String"))
    val ext = testExtractor(ValueOption)
    val extractor = ParameterExtractor.asUpperCase(ext)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(List("THIS IS A TEST STRING")))
  }

  it should "provide an extractor that does a mapping of enum values" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val mapping = Map("foo" -> 1, "bar" -> 2, "baz" -> 3)
    val values = mapping.keys.toList
    val results = values map (mapping(_))
    val ValueOption: OptionValue[String] = Success(values)
    val ext = testExtractor(ValueOption)
    val extractor = ParameterExtractor.asEnum(ext)(mapping.get)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res.get.toList should contain theSameElementsInOrderAs results
  }

  it should "provide an enum extractor that handles invalid literals" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val mappingFunc: String => Option[Int] = _ => None
    val Value = "foo"
    val ValueOption: OptionValue[String] = Try(Some(Value))
    val ext = testExtractor(ValueOption)
    val extractor = ParameterExtractor.asEnum(ext)(mappingFunc)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    checkExtractionException(expectExtractionException(res))("enum", Value)
  }

  it should "provide an extractor that returns a mandatory value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val ValueOption: SingleOptionValue[Int] = Success(Some(ExtractorResult))
    val ext = testExtractor(ValueOption)
    val extractor = ParameterExtractor.asMandatory(ext)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(ExtractorResult))
  }

  it should "provide an extractor that fails if an option does not have a value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val ValueOption: SingleOptionValue[Int] = Success(None)
    val ext = testExtractor(ValueOption)
    val extractor = ParameterExtractor.asMandatory(ext)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    checkExtractionException(expectExtractionException(res))("no value")
  }

  it should "provide an extractor that reads from the console" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val Result = "enteredFromUser"
    when(consoleReader.readOption(Key, password = true)).thenReturn(Result)
    val extractor = ParameterExtractor.consoleReaderValue(Key, password = true)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(TestParameters)
    res.get should contain only Result
  }

  it should "evaluate the password flag of the console reader extractor" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val Result = "enteredFromUser"
    when(consoleReader.readOption(Key, password = false)).thenReturn(Result)
    val extractor = ParameterExtractor.consoleReaderValue(Key, password = false)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(TestParameters)
    res.get should contain only Result
  }

  it should "provide an extractor that yields an empty option value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]

    val (res, next) = ParameterExtractor.runExtractor(ParameterExtractor.emptyExtractor[Int], TestParameters)
    next.parameters should be(TestParameters)
    res should be(ParameterExtractor.emptyOptionValue)
    res.get should have size 0
  }

  it should "provide a conditional extractor that executes the if case" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val nextNextParameters = Parameters(Map("next" -> List("v4", "v5")), Set("x", "y", "z"))
    val condExt: CliExtractor[Try[Boolean]] = testExtractor(Success(true))
    val ifExt = testExtractor(ResultOptionValue, expParameters = NextParameters, nextParameters = nextNextParameters)
    val extractor = ParameterExtractor.conditionalValue(condExt, ifExt)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(nextNextParameters)
    res should be(ResultOptionValue)
  }

  it should "provide a condition extractor that executes the else case" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val nextNextParameters = Parameters(Map("next" -> List("v4", "v5")), Set("x", "y", "z"))
    val condExt: CliExtractor[Try[Boolean]] = testExtractor(Success(false))
    val elseExt = testExtractor(ResultOptionValue, expParameters = NextParameters,
      nextParameters = nextNextParameters)
    val extractor = ParameterExtractor.conditionalValue(condExt, ParameterExtractor.emptyExtractor, elseExt)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(nextNextParameters)
    res should be(ResultOptionValue)
  }

  it should "provide a condition extractor that handles failures" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val exception = new Exception("failed")
    val condExt: CliExtractor[Try[Boolean]] = testExtractor(Failure(exception))
    val extractor = ParameterExtractor.conditionalValue(condExt, ifExt = ParameterExtractor.emptyExtractor[String],
      elseExt = ParameterExtractor.emptyExtractor[String])

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Failure(exception))
  }

  it should "provide a conditional group extractor that executes the correct group" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val nextNextParameters = Parameters(Map("next" -> List("v4", "v5")), Set("x", "y", "z"))
    val groupExt: CliExtractor[Try[String]] = testExtractor(Success("foo"))
    val activeExt = testExtractor(ResultOptionValue, expParameters = NextParameters,
      nextParameters = nextNextParameters)
    val otherExt = constantOptionValue("anotherResult")
    val groupMap = Map("foo" -> activeExt, "bar" -> otherExt)
    val extractor = conditionalGroupValue(groupExt, groupMap)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(nextNextParameters)
    res should be(ResultOptionValue)
  }

  it should "provide a conditional group extractor that handles a failure of the group selector" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val failedResult = Failure(new Exception("failure group"))
    val groupExt: CliExtractor[Try[String]] = testExtractor(failedResult)
    val groupMap = Map("foo" -> constantOptionValue("ignored"))
    val extractor = conditionalGroupValue(groupExt, groupMap)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(failedResult)
  }

  it should "provide a conditional group extractor that fails if the group cannot be resolved" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val GroupName = "foo"
    val groupExt: CliExtractor[Try[String]] = testExtractor(Success(GroupName))
    val groupMap = Map("bar" -> constantOptionValue("ignored"))
    val extractor = conditionalGroupValue(groupExt, groupMap)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters should be(NextParameters)
    checkExtractionException(expectExtractionException(res),
      expParams = TestParameters.parametersMap)(s"'$GroupName''")
  }

  it should "provide an extractor that checks whether an option is defined if the option has a value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val extractor = ParameterExtractor.isDefinedExtractor(Key)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters.accessedParameters should contain only Key
    res should be(Success(true))
  }

  it should "provide an extractor that checks whether an option is defined if the option has no value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val OtherKey = "undefinedOption"
    val extractor = ParameterExtractor.isDefinedExtractor(OtherKey)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParameters)
    next.parameters.accessedParameters should contain only OtherKey
    res should be(Success(false))
  }

  it should "provide an extractor that extracts a single input value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val extractor = ParameterExtractor.inputValue(0)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParametersWithInputs)
    next.parameters.accessedParameters should contain only ParameterParser.InputOption
    res.get should contain only InputValues.head
  }

  it should "provide an extractor that extracts multiple input values" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val extractor = ParameterExtractor.inputValues(0, 1)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParametersWithInputs)
    next.parameters.accessedParameters should contain only ParameterParser.InputOption
    res.get.toList should contain theSameElementsInOrderAs InputValues.take(2)
  }

  it should "provide an extractor that extracts multiple input values and handles the last check" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val extractor = ParameterExtractor.inputValues(0, InputValues.size - 1, last = true)

    val (res, next) = ParameterExtractor.runExtractor(extractor, TestParametersWithInputs)
    next.parameters.accessedParameters should contain only ParameterParser.InputOption
    res.get.toList should contain theSameElementsInOrderAs InputValues
  }

  it should "interpret a negative value for the to index of an input value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val extractor = ParameterExtractor.inputValues(1, -1)

    val (res, _) = ParameterExtractor.runExtractor(extractor, TestParametersWithInputs)
    res.get.toList should contain theSameElementsInOrderAs InputValues.drop(1)
  }

  it should "interpret a negative value for the from index of an input value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val extractor = ParameterExtractor.inputValue(-2)

    val (res, _) = ParameterExtractor.runExtractor(extractor, TestParametersWithInputs)
    res.get should contain only InputValues(1)
  }

  it should "yield a failure if the index of an input value is too small" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val extractor = ParameterExtractor.inputValue(-InputValues.size - 1)

    val (res, _) = ParameterExtractor.runExtractor(extractor, TestParametersWithInputs)
    checkExtractionException(expectExtractionException(res), expKey = ParameterParser.InputOption,
      expParams = TestParametersWithInputs.parametersMap)("-1")
  }

  it should "yield a failure if the index of an input value is too big" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val extractor = ParameterExtractor.inputValues(1, InputValues.size, optKey = Some(Key))

    val (res, _) = ParameterExtractor.runExtractor(extractor, TestParametersWithInputs)
    checkExtractionException(expectExtractionException(res), expKey = ParameterParser.InputOption,
      expParams = TestParametersWithInputs.parametersMap)("few input arguments")
  }

  it should "yield a failure if too many input parameters have been specified" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val extractor = ParameterExtractor.inputValue(1, Some("destination"), last = true)

    val res = ParameterExtractor.tryExtractor(extractor, TestParametersWithInputs)
    checkExtractionException(expectExtractionException(res), expKey = ParameterParser.InputOption,
      expParams = TestParametersWithInputs.parametersMap)("at most 2")
  }

  it should "check whether all parameters have been consumed" in {
    val Key2 = "otherKey1"
    val Key3 = "otherKey2"
    val parameters = Parameters(TestParameters.parametersMap +
      (Key2 -> List("v1", "v2")) + (Key3 -> List("v3")), Set(Key, Key2, Key3))
    val context = TestContext.copy(parameters = parameters)

    val validatedContext = ParameterExtractor.checkParametersConsumed(context)
    validatedContext should be(Success(context))
  }

  it should "detect parameters that have not been consumed" in {
    val Key2 = "otherKey1"
    val Key3 = "otherKey2"
    val parameters = Parameters(TestParameters.parametersMap +
      (Key2 -> List("v1", "v2")) + (Key3 -> List("v3")), Set(Key))
    val context = TestContext.copy(parameters = parameters)

    val validatedContext = ParameterExtractor.checkParametersConsumed(context)
    validatedContext match {
      case Failure(exception: ParameterExtractionException) =>
        exception.failures should have size 2
        exception.failures.map(_.key) should contain only(Key2, Key3)
        exception.failures.forall(_.message.contains("Unexpected")) shouldBe true
        exception.failures.forall(_.context == context) shouldBe true
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "add failures to the help context" in {
    val Key2 = "someOtherKey"
    val Key3 = "oneMoreKey"
    val Key4 = "additionalKey"
    val helpContext = new CliHelpContext(Map.empty, SortedSet.empty, None, Nil)
      .addOption(Key, Some("Help1"))
      .addOption(Key2, None)
      .addOption(Key3, Some("Help3"))
    val failure1 = ExtractionFailure(Key, "error1", TestContext)
    val failure3 = ExtractionFailure(Key3, "error3", TestContext)
    val failure4 = ExtractionFailure(Key4, "error4", TestContext)

    val updHelpCtx = ParameterExtractor.addFailuresToHelpContext(helpContext, List(failure1, failure3, failure4))
    updHelpCtx.options(Key2) should be(helpContext.options(Key2))
    val attr1 = updHelpCtx.options(Key)
    attr1.attributes(CliHelpGenerator.AttrHelpText) should be("Help1")
    attr1.attributes(CliHelpGenerator.AttrErrorMessage) should be(failure1.message)
    val attr3 = updHelpCtx.options(Key3)
    attr3.attributes(CliHelpGenerator.AttrErrorMessage) should be(failure3.message)
    val attr4 = updHelpCtx.options(Key4)
    attr4.attributes(CliHelpGenerator.AttrOptionType) should be(CliHelpGenerator.OptionTypeOption)
    attr4.attributes(CliHelpGenerator.AttrErrorMessage) should be(failure4.message)
  }
}
