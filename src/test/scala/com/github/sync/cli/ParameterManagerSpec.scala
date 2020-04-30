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

import com.github.sync.cli.ParameterManager.{OptionValue, Parameters}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.util.{Failure, Success, Try}

object ParameterManagerSpec {
  /** A test option key. */
  private val Key = "--my-test-option"

  /** A result of a test CLI processor. */
  private val ProcessorResult = 42

  /** The test value list assigned to the test key.  */
  private val ResultValues = List(ProcessorResult.toString)

  /** Test data for input values passed on the command line. */
  private val InputValues = List("1", "2", "3")

  /** A test option value containing the test result. */
  private val ResultOptionValue: OptionValue[String] = Success(ResultValues)

  /** A test Parameters object for testing CLI processors. */
  private val TestParameters: Parameters = Map(Key -> ResultValues)

  /** A test parameters object that contains input parameters. */
  private val TestParametersWithInputs: Parameters = TestParameters.parametersMap +
    (ParameterManager.InputOption -> InputValues)

  /** Another test Parameters object representing updated parameters. */
  private val NextParameters = Parameters(Map("bar" -> List("v2", "v3")), Set("x", "y"))
}

/**
  * Test class for ''ParameterManager''. Note that the major part of the
  * functionality provided by ''ParameterManager'' is tested together with the
  * Sync-specific functionality.
  */
class ParameterManagerSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import ParameterManager._
  import ParameterManagerSpec._

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

  /**
    * Creates a generic test Cli processor that checks the context passed to it
    * and returns a defined result.
    *
    * @param value          the value to be returned by the processor
    * @param expParameters  the expected parameters
    * @param nextParameters the updated parameters
    * @param expReader      the expected console reader
    * @tparam A the type of the value
    * @return the test processor
    */
  private def testProcessor[A](value: A, expParameters: Parameters = TestParameters,
                               nextParameters: Parameters = NextParameters)
                              (implicit expReader: ConsoleReader): CliProcessor[A] = CliProcessor(context => {
    context.parameters should be(expParameters)
    context.reader should be(expReader)
    (value, context.update(nextParameters, context.helpContext))
  }, Some(Key))

  "ParametersManager" should "support running a CliProcessor" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val proc = testProcessor(ProcessorResult)

    val (res, next) = ParameterManager.runProcessor(proc, TestParameters)
    res should be(ProcessorResult)
    next.parameters should be(NextParameters)
  }

  it should "run a processor yielding a Try if execution is successful" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val proc = testProcessor[Try[Int]](Success(ProcessorResult))

    ParameterManager.tryProcessor(proc, TestParameters) match {
      case Success((res, next)) =>
        res should be(ProcessorResult)
        next.parameters should be(NextParameters)
      case f => fail("Unexpected result: " + f)
    }
  }

  it should "run a processor yielding a Try if execution fails" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val exception = new IllegalArgumentException("Wrong parameters")
    val proc = testProcessor[Try[Int]](Failure(exception))

    ParameterManager.tryProcessor(proc, TestParameters) match {
      case Failure(ex) =>
        ex should be(exception)
      case s => fail("Unexpected result: " + s)
    }
  }

  it should "wrap a function in a Try" in {
    val triedResult = ParameterManager.paramTry(Key)(ProcessorResult)

    triedResult should be(Success(ProcessorResult))
  }

  it should "catch the exception thrown by a function and wrap it" in {
    val exception = new IOException("Fatal error")

    val triedResult = ParameterManager.paramTry[String](Key)(throw exception)
    triedResult match {
      case Failure(ex) =>
        ex shouldBe a[IllegalArgumentException]
        ex.getCause should be(exception)
        ex.getMessage should be(Key + ": java.io.IOException - " + exception.getLocalizedMessage)
      case s => fail("Unexpected result: " + s)
    }
  }

  it should "provide a constant processor" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.constantProcessor(ProcessorResult)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    res should be(ProcessorResult)
    next.parameters should be(TestParameters)
  }

  it should "provide a processor returning a constant option value with only a single value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.constantOptionValue(ProcessorResult.toString)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(TestParameters)
    res.get should contain only ProcessorResult.toString
  }

  it should "provide a processor returning a constant option value with multiple values" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val items = List("foo", "bar", "baz", "more")
    val processor = ParameterManager.constantOptionValue(items.head, items.tail: _*)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(TestParameters)
    res.get should be(items)
  }

  it should "provide a fallback processor if the first processor yields a value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val proc1 = testProcessor(ResultOptionValue)
    val proc2 = consoleReaderValue("someKey", password = true)
    val processor = ParameterManager.withFallback(proc1, proc2)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res.get should be(ResultValues)
    Mockito.verifyZeroInteractions(consoleReader)
  }

  it should "provide a fallback processor if the first processor yields an empty value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val nextNextParameters = Parameters(Map("next" -> List("v4", "v5")), Set("x", "y", "z"))
    val proc1 = testProcessor[OptionValue[String]](Success(List.empty))
    val proc2 = testProcessor(ResultOptionValue, expParameters = NextParameters, nextParameters = nextNextParameters)
    val processor = ParameterManager.withFallback(proc1, proc2)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(nextNextParameters)
    res.get should be(ResultValues)
  }

  it should "provide a fallback processor if the first processor yields a Failure" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val exception = new IllegalArgumentException("Invalid option")
    val optionValue: OptionValue[String] = Failure(exception)
    val proc1 = testProcessor(optionValue)
    val proc2 = constantProcessor(ResultOptionValue)
    val processor = ParameterManager.withFallback(proc1, proc2)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(optionValue)
  }

  it should "provide a processor to extract a single option value if there is exactly one value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val proc = testProcessor(ResultOptionValue)
    val processor = ParameterManager.asSingleOptionValue(proc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(Some(ProcessorResult.toString)))
  }

  it should "provide a processor to extract a single option value if the value is undefined" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val EmptyValue: OptionValue[String] = Success(Nil)
    val proc = testProcessor(EmptyValue)
    val processor = ParameterManager.asSingleOptionValue(proc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(None))
  }

  it should "provide a processor to extract a single option value if there are multiple values" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val MultiValue: OptionValue[String] = Success(List("v1", "v2"))
    val proc = testProcessor(MultiValue)
    val processor = ParameterManager.asSingleOptionValue(proc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res match {
      case Failure(exception) =>
        exception.getMessage should include(Key)
        exception.getMessage should include("multiple values")
        exception.getMessage should include(MultiValue.toString)
        exception shouldBe a[IllegalArgumentException]
      case s => fail("Unexpected result: " + s)
    }
  }

  it should "provide a mapping processor that handles a failed result" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val FailedValue: OptionValue[String] = Failure(new Exception("Failed"))
    val proc = testProcessor(FailedValue)
    val processor = ParameterManager.mapped(proc)(_.toInt)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(FailedValue)
  }

  it should "provide a mapping processor that handles an empty result" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val EmptyResult: OptionValue[String] = Success(None)
    val proc = testProcessor(EmptyResult)
    val processor = ParameterManager.mapped(proc)(_ => throw new IllegalArgumentException("Nope"))

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(EmptyResult)
  }

  it should "provide a mapping processor that handles a defined result" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val Result: OptionValue[String] = Success(Some(ProcessorResult.toString))
    val proc = testProcessor(Result)
    val processor = ParameterManager.mapped(proc)(_.toInt)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(List(ProcessorResult)))
  }

  it should "provide a mapping processor that handles an exception thrown by the mapping function" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val Result: OptionValue[String] = Success(Some("Not a number!"))
    val proc = testProcessor(Result)
    val processor = ParameterManager.mapped(proc)(_.toInt)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(Key)
        exception.getCause shouldBe a[NumberFormatException]
      case s => fail("Unexpected result: " + s)
    }
  }

  it should "provide a mapping processor with fallbacks if the value is defined" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val Result: OptionValue[String] = Success(Some(ProcessorResult.toString))
    val proc = testProcessor(Result)
    val processor = ParameterManager.mappedWithFallback(proc, 1, 2, 3)(_.toInt)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(List(ProcessorResult)))
  }

  it should "provide a mapping processor with fallbacks if the value is undefined" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.mappedWithFallback(ParameterManager.emptyProcessor[String],
      1, 2, 3)(_.toInt)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(TestParameters)
    res should be(Success(List(1, 2, 3)))
  }

  it should "provide a processor that converts an option value to int" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val StrValue: OptionValue[String] = Try(Some(ProcessorResult.toString))
    val proc = testProcessor(StrValue)
    val processor = ParameterManager.asIntOptionValue(proc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(List(ProcessorResult)))
  }

  it should "provide a processor that converts an option value to int and handles errors" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val StrValue: OptionValue[String] = Try(Some("not a valid number"))
    val proc = testProcessor(StrValue)
    val processor = ParameterManager.asIntOptionValue(proc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(Key)
        exception.getCause shouldBe a[NumberFormatException]
      case s => fail("Unexpected result: " + s)
    }
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
    val proc = testProcessor(StrValue)
    val processor = ParameterManager.asBooleanOptionValue(proc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(List(expResult)))
  }

  it should "provide a processor that converts an option to boolean if the result is true" in {
    checkBooleanConversion("true", expResult = true)
  }

  it should "provide a processor that converts an option to boolean if the result is false" in {
    checkBooleanConversion("false", expResult = false)
  }

  it should "provide a processor that converts an option to boolean ignoring case" in {
    checkBooleanConversion("TruE", expResult = true)
  }

  it should "provide a processor that converts an option to boolean and handles errors" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val StrValue = "not a valid boolean"
    val ValueOption: OptionValue[String] = Try(Some(StrValue))
    val proc = testProcessor(ValueOption)
    val processor = ParameterManager.asBooleanOptionValue(proc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(Key)
        exception.getMessage should include(StrValue)
      case s => fail("Unexpected result: " + s)
    }
  }

  it should "provide a processor that converts string values to lower case" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val ValueOption: OptionValue[String] = Try(Some("This Is a TEST String"))
    val proc = testProcessor(ValueOption)
    val processor = ParameterManager.asLowerCase(proc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(List("this is a test string")))
  }

  it should "provide a processor that converts string values to upper case" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val ValueOption: OptionValue[String] = Try(Some("This Is a TEST String"))
    val proc = testProcessor(ValueOption)
    val processor = ParameterManager.asUpperCase(proc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(List("THIS IS A TEST STRING")))
  }

  it should "provide a processor that does a mapping of enum values" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val mapping = Map("foo" -> 1, "bar" -> 2, "baz" -> 3)
    val values = mapping.keys.toList
    val results = values map (mapping(_))
    val ValueOption: OptionValue[String] = Success(values)
    val proc = testProcessor(ValueOption)
    val processor = ParameterManager.asEnum(proc)(mapping.get)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res.get.toList should contain theSameElementsInOrderAs results
  }

  it should "provide an enum processor that handles invalid literals" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val mappingFunc: String => Option[Int] = _ => None
    val Value = "foo"
    val ValueOption: OptionValue[String] = Try(Some(Value))
    val proc = testProcessor(ValueOption)
    val processor = ParameterManager.asEnum(proc)(mappingFunc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(Key)
        exception.getMessage should include(Value)
      case s => fail("Unexpected result: " + s)
    }
  }

  it should "provide a processor that returns a mandatory value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val ValueOption: SingleOptionValue[Int] = Success(Some(ProcessorResult))
    val proc = testProcessor(ValueOption)
    val processor = ParameterManager.asMandatory(proc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Success(ProcessorResult))
  }

  it should "provide a processor that fails if an option does not have a value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val ValueOption: SingleOptionValue[Int] = Success(None)
    val proc = testProcessor(ValueOption)
    val processor = ParameterManager.asMandatory(proc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(Key)
        exception.getMessage should include("no value")
      case s => fail("Unexpected result: " + s)
    }
  }

  it should "provide a processor that reads from the console" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val Result = "enteredFromUser"
    when(consoleReader.readOption(Key, password = true)).thenReturn(Result)
    val processor = ParameterManager.consoleReaderValue(Key, password = true)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(TestParameters)
    res.get should contain only Result
  }

  it should "evaluate the password flag of the console reader processor" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val Result = "enteredFromUser"
    when(consoleReader.readOption(Key, password = false)).thenReturn(Result)
    val processor = ParameterManager.consoleReaderValue(Key, password = false)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(TestParameters)
    res.get should contain only Result
  }

  it should "provide a processor that yields an empty option value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]

    val (res, next) = ParameterManager.runProcessor(ParameterManager.emptyProcessor[Int], TestParameters)
    next.parameters should be(TestParameters)
    res should be(ParameterManager.emptyOptionValue)
    res.get should have size 0
  }

  it should "provide a conditional processor that executes the if case" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val nextNextParameters = Parameters(Map("next" -> List("v4", "v5")), Set("x", "y", "z"))
    val condProc: CliProcessor[Try[Boolean]] = testProcessor(Success(true))
    val ifProc = testProcessor(ResultOptionValue, expParameters = NextParameters, nextParameters = nextNextParameters)
    val processor = ParameterManager.conditionalValue(condProc, ifProc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(nextNextParameters)
    res should be(ResultOptionValue)
  }

  it should "provide a condition processor that executes the else case" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val nextNextParameters = Parameters(Map("next" -> List("v4", "v5")), Set("x", "y", "z"))
    val condProc: CliProcessor[Try[Boolean]] = testProcessor(Success(false))
    val elseProc = testProcessor(ResultOptionValue, expParameters = NextParameters,
      nextParameters = nextNextParameters)
    val processor = ParameterManager.conditionalValue(condProc, ParameterManager.emptyProcessor, elseProc)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(nextNextParameters)
    res should be(ResultOptionValue)
  }

  it should "provide a condition processor that handles failures" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val exception = new Exception("failed")
    val condProc: CliProcessor[Try[Boolean]] = testProcessor(Failure(exception))
    val processor = ParameterManager.conditionalValue(condProc, ifProc = ParameterManager.emptyProcessor[String],
      elseProc = ParameterManager.emptyProcessor[String])

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(Failure(exception))
  }

  it should "provide a conditional group processor that executes the correct group" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val nextNextParameters = Parameters(Map("next" -> List("v4", "v5")), Set("x", "y", "z"))
    val groupProc: CliProcessor[Try[String]] = testProcessor(Success("foo"))
    val activeProc = testProcessor(ResultOptionValue, expParameters = NextParameters,
      nextParameters = nextNextParameters)
    val otherProc = constantOptionValue("anotherResult")
    val groupMap = Map("foo" -> activeProc, "bar" -> otherProc)
    val processor = conditionalGroupValue(groupProc, groupMap)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(nextNextParameters)
    res should be(ResultOptionValue)
  }

  it should "provide a conditional group processor that handles a failure of the group selector" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val failedResult = Failure(new Exception("failure group"))
    val groupProc: CliProcessor[Try[String]] = testProcessor(failedResult)
    val groupMap = Map("foo" -> constantOptionValue("ignored"))
    val processor = conditionalGroupValue(groupProc, groupMap)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res should be(failedResult)
  }

  it should "provide a conditional group processor that fails if the group cannot be resolved" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val GroupName = "foo"
    val groupProc: CliProcessor[Try[String]] = testProcessor(Success(GroupName))
    val groupMap = Map("bar" -> constantOptionValue("ignored"))
    val processor = conditionalGroupValue(groupProc, groupMap)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters should be(NextParameters)
    res match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(Key)
        exception.getMessage should include(s"'$GroupName''")
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "provide a processor that checks whether an option is defined if the option has a value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.isDefinedProcessor(Key)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters.accessedParameters should contain only Key
    res should be(Success(true))
  }

  it should "provide a processor that checks whether an option is defined if the option has no value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val OtherKey = "undefinedOption"
    val processor = ParameterManager.isDefinedProcessor(OtherKey)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next.parameters.accessedParameters should contain only OtherKey
    res should be(Success(false))
  }

  it should "provide a processor that extracts a single input value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.inputValue(0)

    val (res, next) = ParameterManager.runProcessor(processor, TestParametersWithInputs)
    next.parameters.accessedParameters should contain only ParameterManager.InputOption
    res.get should contain only InputValues.head
  }

  it should "provide a processor that extracts multiple input values" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.inputValues(0, 1)

    val (res, next) = ParameterManager.runProcessor(processor, TestParametersWithInputs)
    next.parameters.accessedParameters should contain only ParameterManager.InputOption
    res.get.toList should contain theSameElementsInOrderAs InputValues.take(2)
  }

  it should "provide a processor that extracts multiple input values and handles the last check" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.inputValues(0, InputValues.size - 1, last = true)

    val (res, next) = ParameterManager.runProcessor(processor, TestParametersWithInputs)
    next.parameters.accessedParameters should contain only ParameterManager.InputOption
    res.get.toList should contain theSameElementsInOrderAs InputValues
  }

  it should "interpret a negative value for the to index of an input value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.inputValues(1, -1)

    val (res, _) = ParameterManager.runProcessor(processor, TestParametersWithInputs)
    res.get.toList should contain theSameElementsInOrderAs InputValues.drop(1)
  }

  it should "interpret a negative value for the from index of an input value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.inputValue(-2)

    val (res, _) = ParameterManager.runProcessor(processor, TestParametersWithInputs)
    res.get should contain only InputValues(1)
  }

  it should "yield a failure if the index of an input value is too small" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.inputValue(-InputValues.size - 1)

    val (res, _) = ParameterManager.runProcessor(processor, TestParametersWithInputs)
    res match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(ParameterManager.InputOption)
        exception.getMessage should include("-1")
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "yield a failure if the index of an input value is too big" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.inputValues(1, InputValues.size, optKey = Some(Key))

    val (res, _) = ParameterManager.runProcessor(processor, TestParametersWithInputs)
    res match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(ParameterManager.InputOption)
        exception.getMessage should include(s"$Key")
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "yield a failure if too many input parameters have been specified" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.inputValue(1, Some("destination"), last = true)

    val res = ParameterManager.tryProcessor(processor, TestParametersWithInputs)
    res match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(ParameterManager.InputOption)
        exception.getMessage should include("at most 2")
      case r => fail("Unexpected result: " + r)
    }
  }
}
