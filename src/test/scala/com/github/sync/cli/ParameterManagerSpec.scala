/*
 * Copyright 2018-2019 The Developers Team.
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

import com.github.sync.cli.ParameterManager.{OptionValue, Parameters}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.util.{Failure, Success, Try}

object ParameterManagerSpec {
  /** A test Parameters object for testing CLI processors. */
  private val TestParameters: Parameters = Map("foo" -> List("v1"))

  /** Another test Parameters object representing updated parameters. */
  private val NextParameters = Parameters(Map("bar" -> List("v2", "v3")), Set("x", "y"))

  /** A result of a test CLI processor. */
  private val ProcessorResult = 42

  /** A test option value containing the test result. */
  private val ResultOptionValue: OptionValue = List(ProcessorResult.toString)
}

/**
  * Test class for ''ParameterManager''. Note that the major part of the
  * functionality provided by ''ParameterManager'' is tested together with the
  * Sync-specific functionality.
  */
class ParameterManagerSpec extends FlatSpec with Matchers with MockitoSugar {

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
    (value, context.update(nextParameters))
  })

  "ParametersManager" should "support running a CliProcessor" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val proc = testProcessor(ProcessorResult)

    val (res, next) = ParameterManager.runProcessor(proc, TestParameters)
    res should be(ProcessorResult)
    next should be(NextParameters)
  }

  it should "run a processor yielding a Try if execution is successful" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val proc = testProcessor[Try[Int]](Success(ProcessorResult))

    ParameterManager.tryProcessor(proc, TestParameters) match {
      case Success((res, next)) =>
        res should be(ProcessorResult)
        next should be(NextParameters)
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

  it should "provide a constant processor" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.constantProcessor(ProcessorResult)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    res should be(ProcessorResult)
    next should be(TestParameters)
  }

  it should "provide a processor returning a constant option value with only a single value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val processor = ParameterManager.constantOptionValue(ProcessorResult.toString)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next should be(TestParameters)
    res should contain only ProcessorResult.toString
  }

  it should "provide a processor returning a constant option value with multiple values" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val items = List("foo", "bar", "baz", "more")
    val processor = ParameterManager.constantOptionValue(items.head, items.tail: _*)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next should be(TestParameters)
    res should be(items)
  }

  it should "provide a fallback processor if the first processor yields a value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val proc1 = testProcessor(ResultOptionValue)
    val proc2 = CliProcessor[OptionValue](context => throw new IllegalArgumentException("Unexpected call!"))
    val processor = ParameterManager.fallback(proc1, proc2)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next should be(NextParameters)
    res should be(ResultOptionValue)
  }

  it should "provide a fallback processor if the first processor yields an empty value" in {
    implicit val consoleReader: ConsoleReader = mock[ConsoleReader]
    val nextNextParameters = Parameters(Map("next" -> List("v4", "v5")), Set("x", "y", "z"))
    val proc1 = testProcessor[OptionValue](List.empty)
    val proc2 = testProcessor(ResultOptionValue, expParameters = NextParameters, nextParameters = nextNextParameters)
    val processor = ParameterManager.fallback(proc1, proc2)

    val (res, next) = ParameterManager.runProcessor(processor, TestParameters)
    next should be(nextNextParameters)
    res should be(ResultOptionValue)
  }
}
