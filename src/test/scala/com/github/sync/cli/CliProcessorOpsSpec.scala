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
import java.nio.file.{Path, Paths}

import com.github.sync.cli.ParameterManager._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success, Try}

object CliProcessorOpsSpec {
  /** Key for the option containing multiple numeric values. */
  private val KeyNumbers = "--numbers"

  /** Key for an option that yields a single numeric value. */
  private val KeyAnswer = "--answer"

  /** Key for an option that yields a single boolean value. */
  private val KeyFlag = "--flag"

  /** Key for an option containing a single path value. */
  private val KeyPath = "--path"

  /** A key for an option that does not exist. */
  private val UndefinedKey = "--non-existing-option"

  /** The numeric values for ''KeyNumbers''. */
  private val NumberValues = List(0, 8, 15)

  /** The number value of the ''answer'' option. */
  private val NumberValue = 42

  /** The value of the option with a path value. */
  private val PathValue = Paths get "test"

  /** A map with test parameters used by the tests. */
  private val TestParameters: Parameters = Map(KeyNumbers -> NumberValues.map(_.toString),
    KeyAnswer -> List(NumberValue.toString),
    KeyFlag -> List("true"),
    KeyPath -> List(PathValue.toString))

  /**
    * Executes the given processor on the test parameters.
    *
    * @param proc       the processor
    * @param parameters the parameters
    * @tparam A the result type of the processor
    * @return the result produced by the processor
    */
  private def runProcessor[A](proc: CliProcessor[A], parameters: Parameters = TestParameters): A =
    ParameterManager.runProcessor(proc, parameters)(DefaultConsoleReader)._1

  /**
    * Creates a [[ParamModel]] object from the given components.
    *
    * @param triedNumbers the numbers component
    * @param triedAnswer  the answer component
    * @param triedFlag    the flag component
    * @param triedPath    the path component
    * @return a ''Try'' with the resulting model
    */
  private def createModel(triedNumbers: Try[Iterable[Int]], triedAnswer: Try[Int], triedFlag: Try[Boolean],
                          triedPath: Try[Option[Path]]): Try[ParamModel] =
    createRepresentation(triedNumbers, triedAnswer, triedFlag, triedPath) { (nums, a, f, p) =>
      ParamModel(nums.toList, a, f, p)
    }

  /**
    * Returns a processor that extracts a [[ParamModel]] object.
    *
    * @return the processor
    */
  private def paramModelProcessor(): CliProcessor[Try[ParamModel]] =
    for {
      procNumbers <- optionValue(KeyNumbers).toInt
      procAnswer <- optionValue(KeyAnswer).toInt.single.mandatory
      procFlag <- optionValue(KeyFlag).toBoolean.single.mandatory
      procPath <- optionValue(KeyPath).toPath.single
    } yield createModel(procNumbers, procAnswer, procFlag, procPath)

  /**
    * Creates a test component for a given index.
    *
    * @param index the index
    * @return the test component with this index
    */
  private def createComponent(index: Int): String = s"testValue$index"

  /**
    * Creates a successful ''Try'' with the test component with the given
    * index.
    *
    * @param index the index
    * @return the ''Success'' with this test component
    */
  private def triedComponent(index: Int): Try[String] =
    Success(createComponent(index))

  /**
    * Creates a sequence with test components that can be used to test the
    * creation of a representation.
    *
    * @param count the number of components
    * @return the sequence with the test components
    */
  private def createComponents(count: Int): IndexedSeq[String] =
    (1 to count) map createComponent

  /**
    * A data class that combines the test option values.
    *
    * @param numbers value for the list of numbers
    * @param answer  value for the single number
    * @param flag    value for the flag
    * @param path    value for the path
    */
  case class ParamModel(numbers: List[Int],
                        answer: Int,
                        flag: Boolean,
                        path: Option[Path])

}

/**
  * Test class for the DSL to define complex ''CliProcessor'' objects.
  */
class CliProcessorOpsSpec extends AnyFlatSpec with Matchers {

  import CliProcessorOpsSpec._

  "ParameterManager" should "extract numeric values" in {
    val proc = optionValue(KeyNumbers).toInt

    val result = runProcessor(proc)
    result should be(Success(NumberValues))
  }

  it should "extract a single numeric value" in {
    val proc = optionValue(KeyAnswer).toInt.single

    val result = runProcessor(proc)
    result should be(Success(Some(NumberValue)))
  }

  it should "extract a single mandatory numeric value" in {
    val proc = optionValue(KeyAnswer).toInt.single.mandatory

    val result = runProcessor(proc)
    result should be(Success(NumberValue))
  }

  it should "extract a single flag value" in {
    val proc = optionValue(KeyFlag).toBoolean.single.mandatory

    val result = runProcessor(proc)
    result should be(Success(true))
  }

  it should "extract a single path value" in {
    val proc = optionValue(KeyPath).toPath.single.mandatory

    val result = runProcessor(proc)
    result should be(Success(PathValue))
  }

  it should "support mapping the values extracted by a processor" in {
    val mapFunc: Int => Int = _ + 1
    val intProc = optionValue(KeyNumbers).toInt
    val proc = intProc mapTo mapFunc
    val expectedValues = NumberValues map mapFunc

    val result = runProcessor(proc)
    result should be(Success(expectedValues))
  }

  it should "support checking whether an option is defined" in {
    val proc = optionValue(KeyAnswer).isDefined

    val result = runProcessor(proc)
    result should be(Success(true))
  }

  it should "support checking an option is defined if it is not" in {
    val proc = optionValue(UndefinedKey).isDefined

    val result = runProcessor(proc)
    result should be(Success(false))
  }

  it should "report a failure for an undefined mandatory option" in {
    val proc = optionValue(UndefinedKey).single.mandatory

    val result = runProcessor(proc)
    result match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(UndefinedKey)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "support checking for the multiplicity of a processor" in {
    val proc = optionValue(KeyNumbers).toInt.multiplicity(atMost = NumberValues.size)

    val result = runProcessor(proc)
    result should be(Success(NumberValues))
  }

  it should "report a failure if not enough values are present" in {
    val proc = optionValue(KeyAnswer).multiplicity(atLeast = 2)

    val result = runProcessor(proc)
    result match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(KeyAnswer)
        exception.getMessage should include("at least 2")
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "report a failure if too many values are present" in {
    val proc = optionValue(KeyNumbers).multiplicity(atMost = NumberValues.size - 1)

    val result = runProcessor(proc)
    result match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(KeyNumbers)
        exception.getMessage should include("at most " + (NumberValues.size - 1))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "support setting a fallback value for an option" in {
    val proc = optionValue(UndefinedKey).fallback(constantOptionValue(NumberValue.toString))
      .toInt.single.mandatory

    val result = runProcessor(proc)
    result should be(Success(NumberValue))
  }

  it should "support setting fallback values for an option" in {
    val proc = optionValue(UndefinedKey)
      .toInt
      .fallbackValues(NumberValues.head, NumberValues.tail: _*)

    val result = runProcessor(proc)
    result should be(Success(NumberValues))
  }

  it should "support combining multiple options to a data object" in {
    val ExpModel = ParamModel(NumberValues, NumberValue, flag = true, Some(PathValue))
    val proc = paramModelProcessor()

    val result = runProcessor(proc)
    result should be(Success(ExpModel))
  }

  it should "handle failures when combining multiple options" in {
    val params: Parameters = Map(KeyNumbers -> ("noNumber" :: NumberValues.map(_.toString)),
      KeyAnswer -> List("xy"),
      KeyFlag -> List("undefined"))
    val proc = paramModelProcessor()

    val result = runProcessor(proc, params)
    result match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(KeyNumbers)
        exception.getMessage should include(KeyAnswer)
        exception.getMessage should include(KeyFlag)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "create a representation from two components" in {
    val c1 = Success(NumberValue)
    val c2 = Success(PathValue)

    val triedValues = createRepresentation(c1, c2)(List(_, _))
    triedValues should be(Success(List(NumberValue, PathValue)))
  }

  it should "handle failures when creating a representation from components" in {
    val exception1 = new IOException("failure 1")
    val exception2 = new IllegalStateException("failure 2")
    val c1 = Failure(exception1)
    val c2 = Failure(exception2)

    val triedValues = createRepresentation(c1, c2)((_, _) => throw new IllegalStateException("Failure"))
    triedValues match {
      case Failure(exception) =>
        exception.getMessage should include(exception1.getMessage)
        exception.getMessage should include(exception2.getMessage)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "create a representation from three components" in {
    val triedValues = createRepresentation(triedComponent(1), triedComponent(2),
      triedComponent(3))(List(_, _, _))

    triedValues.get should contain theSameElementsInOrderAs createComponents(3)
  }

  it should "create a representation from four components" in {
    val triedValues = createRepresentation(triedComponent(1), triedComponent(2),
      triedComponent(3), triedComponent(4))(List(_, _, _, _))

    triedValues.get should contain theSameElementsInOrderAs createComponents(4)
  }

  it should "create a representation from five components" in {
    val triedValues = createRepresentation(triedComponent(1), triedComponent(2),
      triedComponent(3), triedComponent(4), triedComponent(5))(List(_, _, _, _, _))

    triedValues.get should contain theSameElementsInOrderAs createComponents(5)
  }

  it should "create a representation from six components" in {
    val triedValues = createRepresentation(triedComponent(1), triedComponent(2),
      triedComponent(3), triedComponent(4), triedComponent(5),
      triedComponent(6))(List(_, _, _, _, _, _))

    triedValues.get should contain theSameElementsInOrderAs createComponents(6)
  }

  it should "create a representation from seven components" in {
    val triedValues = createRepresentation(triedComponent(1), triedComponent(2),
      triedComponent(3), triedComponent(4), triedComponent(5),
      triedComponent(6), triedComponent(7))(List(_, _, _, _, _, _, _))

    triedValues.get should contain theSameElementsInOrderAs createComponents(7)
  }

  it should "create a representation from eight components" in {
    val triedValues = createRepresentation(triedComponent(1), triedComponent(2),
      triedComponent(3), triedComponent(4), triedComponent(5),
      triedComponent(6), triedComponent(7), triedComponent(8))(List(_, _, _, _, _, _, _, _))

    triedValues.get should contain theSameElementsInOrderAs createComponents(8)
  }

  it should "create a representation from nine components" in {
    val triedValues = createRepresentation(triedComponent(1), triedComponent(2),
      triedComponent(3), triedComponent(4), triedComponent(5),
      triedComponent(6), triedComponent(7), triedComponent(8),
      triedComponent(9))(List(_, _, _, _, _, _, _, _, _))

    triedValues.get should contain theSameElementsInOrderAs createComponents(9)
  }

  it should "create a representation from ten components" in {
    val triedValues = createRepresentation(triedComponent(1), triedComponent(2),
      triedComponent(3), triedComponent(4), triedComponent(5),
      triedComponent(6), triedComponent(7), triedComponent(8),
      triedComponent(9), triedComponent(10))(List(_, _, _, _, _, _, _, _, _, _))

    triedValues.get should contain theSameElementsInOrderAs createComponents(10)
  }

  it should "create a representation from eleven components" in {
    val triedValues = createRepresentation(triedComponent(1), triedComponent(2),
      triedComponent(3), triedComponent(4), triedComponent(5),
      triedComponent(6), triedComponent(7), triedComponent(8),
      triedComponent(9), triedComponent(10),
      triedComponent(11))(List(_, _, _, _, _, _, _, _, _, _, _))

    triedValues.get should contain theSameElementsInOrderAs createComponents(11)
  }

  it should "create a representation from twelve components" in {
    val triedValues = createRepresentation(triedComponent(1), triedComponent(2),
      triedComponent(3), triedComponent(4), triedComponent(5),
      triedComponent(6), triedComponent(7), triedComponent(8),
      triedComponent(9), triedComponent(10),
      triedComponent(11), triedComponent(12))(List(_, _, _, _, _, _, _, _, _, _, _, _))

    triedValues.get should contain theSameElementsInOrderAs createComponents(12)
  }

  it should "support the access to input parameters" in {
    val parameters: Parameters = Map(InputOption -> List("1", "2", "3"))
    val processor = inputValue(-2)
      .toInt
      .single
      .mandatory

    val result = runProcessor(processor, parameters)
    result.get should be(2)
  }
}
