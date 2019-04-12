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

import java.time.{Instant, LocalDateTime, ZoneId}

import com.github.sync.SyncTypes._
import com.github.sync._
import com.github.sync.cli.FilterManager.{SyncFilterData, SyncOperationFilter}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

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
}

/**
  * Test class for ''FilterManager''.
  */
class FilterManagerSpec extends FlatSpec with Matchers with AsyncTestHelper {

  import FilterManagerSpec._

  /**
    * Expects a failed future from a parsing operation. It is checked whether
    * the future is actually failed with an ''IllegalArgumentException'' that
    * has a specific error message.
    *
    * @param future the future to be checked
    * @param msg    text to be expected in the exception message
    */
  private def expectParsingFailure(future: Future[_], msg: String): Unit = {
    val exception = expectFailedFuture[IllegalArgumentException](future)
    exception.getMessage should include(msg)
  }

  /**
    * Checks that a given filter expression is rejected.
    *
    * @param expression the expression to be checked
    */
  private def expectInvalidFilterExpression(expression: String): Unit = {
    val futResult = FilterManager.parseFilterOption(List(expression), ActionOverride, Nil)
    expectParsingFailure(futResult, expression)
  }

  /**
    * Checks whether a filter accepts and rejects operations as expected.
    *
    * @param filter     the filter to be checked
    * @param opAccepted the operation to be accepted
    * @param opRejected the operation to be rejected
    */
  private def checkFilter(filter: SyncOperationFilter, opAccepted: => SyncOperation,
                          opRejected: => SyncOperation): Unit = {
    filter(opAccepted) shouldBe true
    filter(opRejected) shouldBe false
  }

  /**
    * Helper method to check whether a single filter expression is correctly
    * processed. The method invokes the filter manager to parse a list with the
    * given single expression. Then it invokes the result with the check
    * operations.
    *
    * @param expression    the filter expression to be parsed
    * @param opAccepted    the operation to be accepted
    * @param opRejected    the operation to be rejected
    * @param commonFilters the list with common filters
    * @return the resulting list with parsed filters
    */
  private def checkParsedFilterExpression(expression: String, opAccepted: => SyncOperation,
                                          opRejected: => SyncOperation,
                                          commonFilters: List[SyncOperationFilter] = Nil):
  List[SyncOperationFilter] = {
    val result = futureResult(FilterManager.parseFilterOption(List(expression), ActionCreate,
      commonFilters))
    val filterExpressions = result(ActionCreate)
    val filter = filterExpressions.head
    checkFilter(filter, opAccepted, opRejected)
    filterExpressions
  }

  /**
    * Helper method to check whether valid filter parameters can be parsed. The
    * method processes the given map with filter expressions. It then checks
    * whether the resulting filter data accepts and rejects the expected
    * options. It is also checked whether all filter parameters have been
    * removed from the parameters map.
    *
    * @param filterArgs  the map with filter arguments
    * @param acceptedOps options expected to be accepted
    * @param rejectedOps options expected to be rejected
    */
  private def checkParseFilterArguments(filterArgs: Map[String, Iterable[String]],
                                        acceptedOps: List[SyncOperation],
                                        rejectedOps: List[SyncOperation]): Unit = {
    val otherParam = "foo" -> List("bar")
    val args = filterArgs + otherParam
    val expNextArgs = Map(otherParam)
    val (nextArgs, filterData) = futureResult(FilterManager.parseFilters(args))
    acceptedOps foreach (op => FilterManager.applyFilter(op, filterData) shouldBe true)
    rejectedOps foreach (op => FilterManager.applyFilter(op, filterData) shouldBe false)
    nextArgs should be(expNextArgs)
  }

  "FilterManager" should "parse an empty list of filter definitions" in {
    val result = futureResult(FilterManager.parseFilterOption(Nil, ActionCreate, Nil))

    result should be(Map(ActionCreate -> Nil))
  }

  it should "return a failed future for an unknown filter expression" in {
    expectInvalidFilterExpression("not supported filter expression")
  }

  it should "add the common filters to the result when parsing filter expressions" in {
    val commonFilters = List[SyncOperationFilter](_ => true, op => op.level == 0)
    val result = FilterManager.parseFilterOption(Nil, ActionRemove, commonFilters)

    futureResult(result) should be(Map(ActionRemove -> commonFilters))
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

  it should "add the common filters to parsed filter expressions" in {
    val Expression = "min-level:1"
    val filter1: SyncOperationFilter = _ => true
    val filter2: SyncOperationFilter = op => op.level == 0
    val commonFilters = List[SyncOperationFilter](filter1, filter2)
    val opAccepted = createOperation(ActionCreate, 1)

    val allFilters = checkParsedFilterExpression(Expression, opAccepted,
      opAccepted.copy(level = 0), commonFilters)
    allFilters should contain allOf(filter1, filter2)
  }

  it should "parse a max-level filter expression" in {
    val Expression = "max-level:5"
    val opAccepted = createOperation(ActionRemove, 5)
    val opRejected = createOperation(ActionRemove, 6)

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "parse all filter expressions" in {
    val commonFilter: SyncOperationFilter = op => op.element.relativeUri == "/foo"
    val Expression1 = "min-level:1"
    val Expression2 = "max-level:4"
    val opAcceptedMin = createOperation(ActionRemove, 1)
    val opRejectedMin = createOperation(ActionRemove, 0)
    val opAcceptedMax = createOperation(ActionRemove, 4)
    val opRejectedMax = createOperation(ActionRemove, 5)

    val allFilters = futureResult(FilterManager.parseFilterOption(List(Expression1, Expression2),
      ActionRemove, List(commonFilter)))(ActionRemove)
    checkFilter(allFilters(1), opAcceptedMin, opRejectedMin)
    checkFilter(allFilters.head, opAcceptedMax, opRejectedMax)
    allFilters should contain(commonFilter)
  }

  it should "accept operations if no filters are defined" in {
    val op = createOperation(ActionCreate, 0)

    FilterManager.applyFilter(op, SyncFilterData(Map.empty)) shouldBe true
  }

  it should "filter out a sync op based on the filter configuration" in {
    val filterData = SyncFilterData(futureResult(FilterManager.parseFilterOption(
      List("min-level:3"), ActionOverride, Nil)))
    val op = createOperation(ActionOverride, 2)

    FilterManager.applyFilter(op, filterData) shouldBe false
  }

  it should "accept a sync op that matches all filter criteria" in {
    val Expressions = List("min-level:1", "max-level:2")
    val filterData = SyncFilterData(futureResult(FilterManager.parseFilterOption(Expressions,
      ActionOverride, Nil)))
    val op = createOperation(ActionOverride, 2)

    FilterManager.applyFilter(op, filterData) shouldBe true
  }

  it should "only accept a sync condition if all filter criteria are fulfilled" in {
    val Expressions = List("min-level:1", "max-level:2")
    val filterData = SyncFilterData(futureResult(FilterManager.parseFilterOption(Expressions,
      ActionOverride, Nil)))
    val op = createOperation(ActionOverride, 3)

    FilterManager.applyFilter(op, filterData) shouldBe false
  }

  it should "take the correct action into account when accepting sync operations" in {
    val Expressions = List("min-level:1", "max-level:2")
    val filterData = SyncFilterData(futureResult(FilterManager.parseFilterOption(Expressions,
      ActionOverride, Nil)))
    val op = createOperation(ActionCreate, 3)

    FilterManager.applyFilter(op, filterData) shouldBe true
  }

  it should "parse arguments without filter expressions" in {
    val expData = SyncFilterData(Map(ActionCreate -> Nil, ActionOverride -> Nil,
      ActionRemove -> Nil))
    val (_, filterData) = futureResult(FilterManager.parseFilters(Map.empty))

    filterData should be(expData)
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

    val futParsed = FilterManager.parseFilters(args)
    expectParsingFailure(futParsed, InvalidExpression)
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
    val args = Map(FilterManager.ArgActionFilter -> ("actionCreate" :: invalidTypes))

    expectParsingFailure(FilterManager.parseFilters(args), invalidTypes.mkString(","))
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
    val opAccepted = createOperation(ActionOverride, 0,
      elementWithTime("ok", toInstant("2018-08-29T00:00:00")))
    val opRejected = createOperation(ActionOverride, 0,
      elementWithTime("!", toInstant("2018-08-28T23:59:59")))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "support date-before filters without a time" in {
    val Expression = "date-before=2018-08-30"
    val opAccepted = createOperation(ActionOverride, 0,
      elementWithTime("ok", toInstant("2018-08-29T23:59:59")))
    val opRejected = createOperation(ActionOverride, 0,
      elementWithTime("!", toInstant("2018-08-30T00:00:00")))

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "deal with invalid dates" in {
    val Expression = "date-before:9999-99-99T99:99:99"
    val args = Map(FilterManager.ArgCommonFilter -> List(Expression))

    expectParsingFailure(FilterManager.parseFilters(args), Expression)
  }
}
