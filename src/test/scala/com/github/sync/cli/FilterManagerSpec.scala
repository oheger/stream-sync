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

import java.time.{Instant, LocalDateTime, ZoneId}

import com.github.sync.SyncTypes._
import com.github.sync._
import com.github.sync.cli.FilterManager._
import com.github.sync.cli.ParameterManager.{ParameterContext, ParameterExtractionException, Parameters}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success, Try}

object FilterManagerSpec {
  /**
    * Constant for an element that can be used when no element-specific checks
    * are executed.
    */
  private val Element = FsFolder("", 0)

  /** Regular expression to parse a date time string. */
  private val RegTime =
    """(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""".r

  /**
    * Converts a string with a date-time to an Instant object.
    *
    * @param s the date-time string
    * @return the ''Instant''
    */
  private def toInstant(s: String): Instant = {
    s match {
      case RegTime(year, month, day, hour, min, sec) =>
        LocalDateTime.of(year.toInt, month.toInt, day.toInt, hour.toInt, min.toInt, sec.toInt)
          .atZone(ZoneId.systemDefault()).toInstant
    }
  }

  /**
    * Generates an element with the given name and last modification time.
    *
    * @param name the name
    * @param time the modification time
    * @return the element
    */
  private def elementWithTime(name: String, time: Instant): FsElement =
    FsFile(name, 0, time, 42)

  /**
    * Convenience function to create a sync operation with some default values.
    *
    * @param action  the action of the operation
    * @param level   the level
    * @param element the subject element
    * @return the newly created operation
    */
  private def createOperation(action: SyncAction, level: Int, element: FsElement = Element): SyncOperation =
    SyncOperation(element, action, level, element.originalUri, element.originalUri)

  /**
    * Helper function to run a parse operation for filter parameters on the
    * arguments specified.
    *
    * @param args the command line arguments
    * @return the result of the parse operation
    */
  private def parseFilters(args: Parameters): Try[(SyncFilterData, ParameterContext)] =
    ParameterManager.tryProcessor(FilterManager.filterDataProcessor, args)(DefaultConsoleReader)
}

/**
  * Test class for ''FilterManager''.
  */
class FilterManagerSpec extends AnyFlatSpec with Matchers with AsyncTestHelper {

  import FilterManagerSpec._

  /**
    * Parses command line options related to filters and expects a success
    * result. The result and the updated parameter context are returned.
    *
    * @param args the command line arguments
    * @return the result of the parse operation
    */
  private def parseFiltersSuccess(args: Parameters): (SyncFilterData, ParameterContext) =
    parseFilters(args) match {
      case Success(value) => value
      case r => fail("Unexpected parse result: " + r)
    }

  /**
    * Expects a failed result from a parsing operation. The exception of the
    * expected type is extracted and returned.
    *
    * @param result the result to be checked
    * @return the extracted exception
    */
  private def expectParsingFailure(result: Try[_]): ParameterExtractionException =
    result match {
      case Failure(exception: ParameterExtractionException) =>
        exception
      case r => fail("Unexpected result: " + r)
    }

  /**
    * Expects a failed result from a parsing operation and does some checks on
    * the exception. It is checked whether the tried result is actually failed
    * with a ''ParameterExtractionException'' that has a specific error
    * message and some default properties.
    *
    * @param result the result to be checked
    * @param msg    texts to be expected in the exception message
    * @return the extracted exception
    */
  private def expectAndCheckParsingFailure(result: Try[_], msg: String*): ParameterExtractionException = {
    val exception = expectParsingFailure(result)
    exception.failures should have size 1
    msg foreach { m =>
      exception.getMessage should include(m)
    }
    exception
  }

  /**
    * Checks that a given filter expression is rejected.
    *
    * @param expression the expression to be checked
    */
  private def expectInvalidFilterExpression(expression: String): Unit = {
    val args = Map(FilterManager.ArgOverrideFilter -> List(expression))
    val exception = expectAndCheckParsingFailure(parseFilters(args))
    exception.failures.head.key should be(FilterManager.ArgOverrideFilter)
  }

  /**
    * Helper method to check whether a single filter expression is correctly
    * processed. The method invokes the filter manager to parse a list with the
    * given single expression. Then it invokes the result with the check
    * operations.
    *
    * @param expression the filter expression to be parsed
    * @param opAccepted the operation to be accepted
    * @param opRejected the operation to be rejected
    * @return the resulting list with parsed filters
    */
  private def checkParsedFilterExpression(expression: String, opAccepted: => SyncOperation,
                                          opRejected: => SyncOperation): Unit = {
    val argsMap = Map(FilterManager.ArgCreateFilter -> List(expression))
    checkParseFilterArguments(argsMap, List(opAccepted), List(opRejected))
  }

  /**
    * Helper method to check whether valid filter parameters can be parsed. The
    * method processes the given map with filter expressions. It then checks
    * whether the resulting filter data accepts and rejects the expected
    * options. It is also checked whether all filter parameters have been
    * marked as accessed.
    *
    * @param filterArgs  the map with filter arguments
    * @param acceptedOps options expected to be accepted
    * @param rejectedOps options expected to be rejected
    */
  private def checkParseFilterArguments(filterArgs: ParameterManager.ParametersMap,
                                        acceptedOps: List[SyncOperation],
                                        rejectedOps: List[SyncOperation]): Unit = {
    val otherParam = "foo" -> List("bar")
    val args = filterArgs + otherParam
    val (filterData, nextContext) = parseFiltersSuccess(args)
    acceptedOps foreach (op => FilterManager.applyFilter(op, filterData) shouldBe true)
    rejectedOps foreach (op => FilterManager.applyFilter(op, filterData) shouldBe false)
    nextContext.parameters.accessedParameters should contain only(ArgCreateFilter, ArgOverrideFilter, ArgRemoveFilter,
      ArgCommonFilter, ArgActionFilter)
  }

  "FilterManager" should "parse an empty list of filter definitions" in {
    val expData = SyncFilterData(Map(ActionCreate -> Nil, ActionOverride -> Nil,
      ActionRemove -> Nil))
    val params = Parameters(Map.empty, Set.empty)
    val (filterData, _) = parseFiltersSuccess(params)

    filterData should be(expData)
  }

  it should "return a failure for an unknown filter expression" in {
    expectInvalidFilterExpression("not supported filter expression")
  }

  it should "parse a min-level filter expression" in {
    val Expression = "min-level:1"
    val opAccepted = createOperation(ActionCreate, 1)

    checkParsedFilterExpression(Expression, opAccepted, opAccepted.copy(level = 0))
  }

  it should "support = as expression separator" in {
    val Expression = "min-level=2"
    val opAccepted = createOperation(ActionCreate, 2)

    checkParsedFilterExpression(Expression, opAccepted, opAccepted.copy(level = 1))
  }

  it should "only accept a numeric min-level" in {
    expectInvalidFilterExpression("min-level:nonNumber")
  }

  it should "parse a max-level filter expression" in {
    val Expression = "max-level:5"
    val opAccepted = createOperation(ActionCreate, 5)
    val opRejected = createOperation(ActionCreate, 6)

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "add the common filters to parsed filter expressions" in {
    val params = Map(FilterManager.ArgOverrideFilter -> List("min-level:2"),
      FilterManager.ArgCommonFilter -> List("max-level:3"))
    val acceptedOps = List(createOperation(ActionOverride, level = 2),
      createOperation(ActionOverride, level = 3))
    val rejectedOps = List(createOperation(ActionOverride, level = 1),
      createOperation(ActionOverride, level = 4))

    checkParseFilterArguments(params, acceptedOps, rejectedOps)
  }

  it should "accept operations if no filters are defined" in {
    val op = createOperation(ActionCreate, 0)

    FilterManager.applyFilter(op, SyncFilterData(Map.empty)) shouldBe true
  }

  it should "filter out a sync op based on the filter configuration" in {
    val params = Map(ArgOverrideFilter -> List("min-level:3"))
    val op = createOperation(ActionOverride, 2)

    checkParseFilterArguments(params, Nil, List(op))
  }

  it should "accept a sync op that matches all filter criteria" in {
    val params = Map(ArgOverrideFilter -> List("min-level:1", "max-level:2"))
    val op = createOperation(ActionOverride, 2)

    checkParseFilterArguments(params, List(op), Nil)
  }

  it should "only accept a sync condition if all filter criteria are fulfilled" in {
    val params = Map(ArgOverrideFilter -> List("min-level:1", "max-level:2"))
    val op = createOperation(ActionOverride, 3)

    checkParseFilterArguments(params, Nil, List(op))
  }

  it should "take the correct action into account when accepting sync operations" in {
    val params = Map(ArgOverrideFilter -> List("min-level:1", "max-level:2"))
    val op = createOperation(ActionCreate, 3)

    checkParseFilterArguments(params, List(op), Nil)
  }

  it should "parse arguments with valid filter expressions" in {
    val args = Map(FilterManager.ArgCreateFilter -> List("min-level:1", "max-level:2"),
      FilterManager.ArgOverrideFilter -> List("min-level:2", "max-level:4"),
      FilterManager.ArgRemoveFilter -> List("min-level:3"))
    val acceptedOps = List(createOperation(ActionCreate, 1),
      createOperation(ActionOverride, 4),
      createOperation(ActionRemove, 3))
    val rejectedOps = List(createOperation(ActionCreate, 0),
      createOperation(ActionCreate, 3),
      createOperation(ActionOverride, 5),
      createOperation(ActionRemove, 2))

    checkParseFilterArguments(args, acceptedOps, rejectedOps)
  }

  it should "parse the argument with common filter expressions" in {
    val args = Map(FilterManager.ArgCommonFilter -> List("min-level:2", "max-level:4"),
      FilterManager.ArgRemoveFilter -> List("min-level:3", "max-level=3"))
    val acceptedOps = List(createOperation(ActionCreate, 2),
      createOperation(ActionOverride, 4),
      createOperation(ActionRemove, 3))
    val rejectedOps = List(createOperation(ActionCreate, 1),
      createOperation(ActionOverride, 5),
      createOperation(ActionRemove, 2))

    checkParseFilterArguments(args, acceptedOps, rejectedOps)
  }

  it should "detect invalid expressions when parsing filter parameters" in {
    val InvalidExpression = "in-valid-filter:true"
    val args = Map(FilterManager.ArgCommonFilter -> List("min-level:2", "max-level:4"),
      FilterManager.ArgRemoveFilter -> List(InvalidExpression, "max-level=3"))

    val triedResult = parseFilters(args)
    expectAndCheckParsingFailure(triedResult, InvalidExpression)
  }

  it should "support a filter to disable specific action types" in {
    val args = Map(FilterManager.ArgActionFilter -> List("actioncreate", "actionoverride"))
    val acceptedOps = List(createOperation(ActionCreate, 1),
      createOperation(ActionOverride, 0))
    val rejectedOps = List(createOperation(ActionRemove, 0))

    checkParseFilterArguments(args, acceptedOps, rejectedOps)
  }

  it should "ignore case for action type filters" in {
    val args = Map(FilterManager.ArgActionFilter -> List("actionCreate", "ActionREMOVE"))
    val acceptedOps = List(createOperation(ActionCreate, 1),
      createOperation(ActionRemove, 0))
    val rejectedOps = List(createOperation(ActionOverride, 0))

    checkParseFilterArguments(args, acceptedOps, rejectedOps)
  }

  it should "support comma-separated action type filters" in {
    val args = Map(FilterManager.ArgActionFilter -> List("actionCreate , ActionOverride"))
    val acceptedOps = List(createOperation(ActionCreate, 1),
      createOperation(ActionOverride, 0))
    val rejectedOps = List(createOperation(ActionRemove, 0))

    checkParseFilterArguments(args, acceptedOps, rejectedOps)
  }

  it should "handle invalid action type names in the action type filter" in {
    val invalidTypes = List("unknown_type", "other_unknown_type")
    val expMessages = invalidTypes.map(t => "action type: " + t)
    val args = Map(FilterManager.ArgActionFilter -> ("actionCreate" :: invalidTypes))

    expectAndCheckParsingFailure(parseFilters(args), expMessages: _*)
  }

  it should "handle invalid action type names in a single action type filter expression" in {
    val invalidTypes = "unknown_type,other_unknown_type"
    val args = Map(FilterManager.ArgActionFilter -> List("actionCreate, " + invalidTypes))

    expectAndCheckParsingFailure(parseFilters(args), "action types: " + invalidTypes)
  }

  it should "parse a simple exclude filter expression" in {
    val Expression = "exclude:/foo"
    val opAccepted = createOperation(ActionCreate, 1, FsFolder("/bar", 0))
    val opRejected = createOperation(ActionCreate, 1, FsFolder("/foo", 0))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "support ? characters in exclusion filters" in {
    val Expression = "exclude:/?oo"
    val opAccepted = createOperation(ActionCreate, 1, FsFolder("/fof", 0))
    val opRejected = createOperation(ActionCreate, 1, FsFolder("/hoo", 0))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "support * characters in exclusion filters" in {
    val Expression = "exclude:*/target/*"
    val opAccepted = createOperation(ActionCreate, 1, FsFolder("/target-folder/test", 0))
    val opRejected = createOperation(ActionCreate, 1, FsFolder("/test/target/sub", 0))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "quote regular glob patterns in filter expressions" in {
    val Expression = "exclude:/foo(1)-(2)"
    val opAccepted = createOperation(ActionCreate, 1, FsFolder("/foo(1)-(3)", 0))
    val opRejected = createOperation(ActionCreate, 1, FsFolder("/foo(1)-(2)", 0))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "match exclusions case insensitive" in {
    val Expression = "exclude:*/target/*"
    val opAccepted = createOperation(ActionCreate, 1, FsFolder("/test/targetTest/test", 0))
    val opRejected = createOperation(ActionCreate, 1, FsFolder("/test/TARGET/test", 0))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "support inclusion filters" in {
    val Expression = "include:*/tar?et/*-bak"
    val opAccepted = createOperation(ActionCreate, 1, FsFolder("/foo/TARSET/more/test-bak", 0))
    val opRejected = createOperation(ActionCreate, 1, FsFolder("/hoo/targetDir/test-bak", 0))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "support a date-after filter with full date and time" in {
    val timeStr = "2018-08-29T21:27:12"
    val Expression = "date-after:" + timeStr
    val opAccepted = createOperation(ActionCreate, 0,
      elementWithTime("ok", toInstant(timeStr)))
    val opRejected = createOperation(ActionCreate, 0,
      elementWithTime("fail", toInstant("2018-08-29T21:27:11")))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "accept folders when using a date-after filter" in {
    val timeStr = "2018-08-29T22:03:08"
    val Expression = "date-after:" + timeStr
    val opAccepted = createOperation(ActionCreate, 0)
    val opRejected = createOperation(ActionCreate, 0,
      elementWithTime("e", toInstant("2018-08-29T22:00:00")))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "support a date-before filter with full date and time" in {
    val timeStr = "2018-08-29T22:05:04"
    val Expression = "date-before:" + timeStr
    val opAccepted = createOperation(ActionCreate, 0,
      elementWithTime("ok", toInstant("2018-08-29T22:05:03")))
    val opRejected = createOperation(ActionCreate, 0,
      elementWithTime("!", toInstant(timeStr)))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "accept folders when using a date-before filter" in {
    val timeStr = "2018-08-29T22:09:45"
    val Expression = "date-before:" + timeStr
    val opAccepted = createOperation(ActionCreate, 0)
    val opRejected = createOperation(ActionCreate, 0,
      elementWithTime("e", toInstant("2018-08-29T22:10:00")))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "support date-after filters without a time" in {
    val Expression = "date-after=2018-08-29"
    val opAccepted = createOperation(ActionCreate, 0,
      elementWithTime("ok", toInstant("2018-08-29T00:00:00")))
    val opRejected = createOperation(ActionCreate, 0,
      elementWithTime("!", toInstant("2018-08-28T23:59:59")))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "support date-before filters without a time" in {
    val Expression = "date-before=2018-08-30"
    val opAccepted = createOperation(ActionCreate, 0,
      elementWithTime("ok", toInstant("2018-08-29T23:59:59")))
    val opRejected = createOperation(ActionCreate, 0,
      elementWithTime("!", toInstant("2018-08-30T00:00:00")))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "deal with invalid dates" in {
    val Expression = "date-before:9999-99-99T99:99:99"
    val args = Map(FilterManager.ArgCommonFilter -> List(Expression))

    expectAndCheckParsingFailure(parseFilters(args), Expression)
  }

  it should "collect all invalid filter expressions" in {
    val InvalidEx1 = "max-level:xy"
    val InvalidEx2 = "un-supported:true"
    val InvalidEx3 = "date-before:9999-99-99T99:99:99"
    val params = Map(FilterManager.ArgCreateFilter -> List("min-level:1", InvalidEx1),
      FilterManager.ArgOverrideFilter -> List(InvalidEx2, "max-level:5", InvalidEx3),
      FilterManager.ArgRemoveFilter -> List("min-level:15"))

    val exception = expectParsingFailure(parseFilters(params))
    val failuresMap = exception.failures.map(f => (f.key, f.message)).toMap
    failuresMap.keys should contain only(FilterManager.ArgCreateFilter, FilterManager.ArgOverrideFilter)
    failuresMap(FilterManager.ArgCreateFilter) should include(InvalidEx1)
    failuresMap(FilterManager.ArgOverrideFilter) should include(InvalidEx2)
    failuresMap(FilterManager.ArgOverrideFilter) should include(InvalidEx3)
  }
}
